package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * §5.10 materialisation: closes a template application by substituting its arguments into the template's
 * recorded open form, and replaces the application with a reference to the entry that results.
 *
 * <p><b>It runs over the resolved form, not the AST.</b> An application reaches here as a
 * {@link TypeRef} carrying {@code arguments} -- the one thing that shape means (a closed form is always an
 * entry named by a bare reference) -- so substitution is a walk over {@code schema.meta} values, and the
 * entry it produces can record its own {@code source}, which §8.2 keys identity on. Doing it over the AST
 * instead would reuse {@code SchemaDesugarer}'s injection machinery but has no channel for that {@code
 * source}, and would put type-level work back into a phase Tranche A made purely syntactic.
 *
 * <p><b>Scope.</b> A template whose parameters occupy field types and field values. A template whose body
 * writes a §5.3 container sugar form is refused earlier, at the application site, by {@code
 * SchemaDesugarer.checkTemplateApplication} -- those need an open representation of the sugar forms that
 * does not exist yet.
 *
 * <p><b>Identity (§8.2).</b> An instantiation entry is keyed on the flattened application recorded in
 * {@code source}, so two {@code box<text>} anywhere in the schema land on one entry. The derived name is
 * built by {@link #internalName} from the application itself, which is what makes that dedup fall out of
 * naming rather than needing a second table.
 *
 * <p><b>Knot-tying.</b> The memo entry is registered <em>before</em> the body is substituted, so a
 * recursive application reached during substitution ({@code tree<T>} inside {@code tree}, which becomes
 * {@code tree<text>} once {@code T} is bound) finds the entry under construction and references it by name
 * rather than recursing forever.
 */
final class TemplateMaterialiser {

    /**
     * Every entry visible to this schema -- local declarations and merged {@code !!import}s alike. A
     * <em>getter</em> rather than a fixed map, because an application closed on demand during resolution
     * (at a supertype or refinement source) may name a head that has not been resolved yet; the getter is
     * {@code SchemaResolver}'s own memo, so asking for it resolves it, with the circular-composition guard
     * still in front.
     */
    private final DefinitionGetter namespace;

    /** The instantiation entries produced, keyed by their derived internal name, in creation order. */
    private final Map<String, TypeDefinition> materialised = new LinkedHashMap<>();

    /** Applications currently being closed, for the knot-tying memo and the termination guard's chain. */
    private final Set<String> closing = new LinkedHashSet<>();

    /** The author-written head each link of {@link #closing} came from, outermost first. */
    private final List<String> heads = new ArrayList<>();

    /**
     * How deep the closing chain may go before materialisation is abandoned -- a <b>backstop</b>, not the
     * rule.
     *
     * <p>{@code TemplateRegularity} rejects a template that grows its argument on a recursive step where it
     * is <em>declared</em>, so nothing that reaches here should be able to run away: <em>regular</em>
     * recursion ties the knot on its first repeat and never nests, since {@code chain<text>} reaches
     * {@code chain<text>} again and finds it in {@link #closing}. This stays because the alternative
     * failure, if that check ever has a hole, is a {@link StackOverflowError} -- not a diagnosis, and not
     * something the exception policy can classify. One comparison for that is worth paying.
     *
     * <p>Distinct from {@code SPEC-FEEDBACK.md} #25, which is about a type with no finite <em>data</em>
     * model; the regularity rule is about one with no finite <em>type</em> model.
     */
    private static final int MAX_CLOSING_DEPTH = 64;

    /**
     * Where each entry is published as it is built, so the namespace can see it immediately. Load-bearing
     * for the on-demand half: a composition supertype closes an application and then looks the resulting
     * name up through {@code namespaceDefinitions} to absorb its fields, which is the very next thing that
     * happens -- an entry only in this pass's own map would be invisible to it.
     */
    private final BiConsumer<String, TypeDefinition> publish;

    TemplateMaterialiser(DefinitionGetter namespace, BiConsumer<String, TypeDefinition> publish) {
        this.namespace = namespace;
        this.publish = publish;
    }

    /**
     * Closes every application reachable from {@code entries}, returning the instantiation entries it
     * produced; {@code entries} is rewritten in place, each application replaced by a bare reference.
     *
     * <p>The returned map is separate from {@code entries} because the caller decides where the new entries
     * land -- they are local to this schema and carry no source position, being named by derivation rather
     * than declared.
     */
    Map<String, TypeDefinition> materialise(Map<String, TypeDefinition> entries,
            MaterialisationFailureReporter reporter) {
        TemplateMaterialiser pass = this;
        for (Map.Entry<String, TypeDefinition> entry : entries.entrySet()) {
            if (!entry.getValue().parameters().isEmpty()) {
                // A template's own body is open: `chain<T>` inside `chain` awaits substitution and is not an
                // application to close. Closing it here would mint an entry per level, keyed on the literal
                // parameter name.
                continue;
            }
            try {
                entry.setValue(pass.rewrite(entry.getValue()));
            } catch (TsonSchemaValidationException e) {
                if (reporter == null) {
                    throw e;
                }
                // Reported against the entry that wrote the application, and left as it was: an entry still
                // naming an open template is one the linker reports again, but that second complaint is
                // about the same line and does not invent a new problem.
                reporter.reportFailedApplication(entry.getKey(), e);
            }
        }
        return pass.materialised;
    }

    /** Where an application this pass cannot close is reported, entry by entry. */
    @FunctionalInterface
    interface MaterialisationFailureReporter {
        void reportFailedApplication(String entryName, TsonSchemaValidationException error);
    }

    /**
     * The entry a fully-bound application denotes, closing it if this is the first sight of it -- the
     * on-demand half, reached from a supertype or refinement-source position during resolution rather than
     * from the batch pass afterwards. Both share this instance, so an application closed here and the same
     * one met later in a field land on one entry.
     */
    String closeApplication(TypeRef application) {
        return close(application).name();
    }

    /** One definition with every application inside it closed. */
    private TypeDefinition rewrite(TypeDefinition definition) {
        return mapRefs(definition, this::close);
    }

    /**
     * One type-ref with its application closed, or itself when it carries no arguments. Arguments close
     * first, so {@code box<box<text>>} produces the inner entry before the outer one names it.
     */
    private TypeRef close(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeArgument> arguments = new ArrayList<>();
        for (TypeArgument argument : ref.arguments()) {
            arguments.add(argument instanceof TypeArgument.Ref nested
                    ? new TypeArgument.Ref(close(nested.ref()))
                    : argument);
        }
        return TypeRef.of(instantiate(ref.name(), arguments));
    }

    /**
     * The entry name a fully-bound application denotes, creating the entry on first sight. An application
     * whose head names nothing in scope is left for {@code TsonSchemaLinker} to report as an unresolved
     * reference rather than guessed at here.
     */
    private String instantiate(String head, List<TypeArgument> arguments) {
        TypeDefinition template = namespace.getTypeDefinition(head);
        if (template == null) {
            return head; // unresolved head -- the linker's verdict, not this pass's
        }
        List<String> parameters = template.parameters();
        if (parameters.isEmpty()) {
            throw new TsonSchemaValidationException("'" + head + "' declares no type parameters, so '"
                    + head + "<...>' applies arguments to something that takes none (§5.10); drop the "
                    + "argument list");
        }
        if (parameters.size() != arguments.size()) {
            throw new TsonSchemaValidationException("'" + head + "' takes " + parameters.size()
                    + " type argument" + (parameters.size() == 1 ? "" : "s") + " " + parameters + ", but "
                    + arguments.size() + " " + (arguments.size() == 1 ? "was" : "were") + " applied (§5.10)");
        }
        String name = internalName(head, arguments);
        if (materialised.containsKey(name) || !closing.add(name)) {
            return name; // already built, or under construction -- the knot-tying case
        }
        if (closing.size() > MAX_CLOSING_DEPTH) {
            closing.remove(name);
            // Named for the *outermost* head, which is the one the author wrote; the head in hand here is
            // whichever link happened to tip the depth over.
            throw new TsonSchemaValidationException("'" + heads.get(0) + "<...>' does not close: "
                    + "materialising it needs more than " + MAX_CLOSING_DEPTH + " nested instantiations and "
                    + "each one differs from the last, so the arguments are growing rather than repeating and "
                    + "there is no finite set of types to build (§5.10). The chain begins " + chain()
                    + ". A recursive template must reach an argument it has already been applied to");
        }
        heads.add(head);
        try {
            TypeDefinition instantiation = substitute(template, head, parameters, arguments);
            materialised.put(name, instantiation);
            publish.accept(name, instantiation);
            return name;
        } finally {
            closing.remove(name);
            heads.remove(heads.size() - 1);
        }
    }

    /** The first few links of the closing chain, for the termination guard's message. */
    private String chain() {
        List<String> shown = closing.stream().limit(4).toList();
        return String.join(" -> ", shown) + (closing.size() > shown.size() ? " -> ..." : "");
    }

    /**
     * The template's open form with each parameter replaced by its argument, recorded as an instantiation
     * entry: {@code parameters} empty, {@code source} the flattened application §8.2 compares by, and the
     * body rewritten so any application it contained is closed too.
     */
    private TypeDefinition substitute(TypeDefinition template, String head, List<String> parameters,
            List<TypeArgument> arguments) {
        Map<String, TypeArgument> bindings = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            bindings.put(parameters.get(i), arguments.get(i));
        }
        TypeDefinition bound = mapFields(mapRefs(template, ref -> bindRef(ref, bindings)),
                field -> bindValue(field, head, bindings));
        // Only the *body* is closed. `source` records the application as written -- it is the key §8.2
        // compares by, so closing it would replace `box<text>` with the very entry it identifies.
        return new TypeDefinition(Optional.of(new TypeRef(head, arguments)), bound.kind(), List.of(),
                bound.constructor(), bound.supertypes(), bound.subtypes(), bound.disjoint(),
                mapBodyRefs(bound.body(), this::close), Optional.empty(), bound.annotations());
    }

    /**
     * A type-ref with each parameter reference replaced by the argument bound to it.
     *
     * <p><b>It recurses into arguments</b>, which is what makes recursion work: the head of {@code chain<T>}
     * is {@code chain}, not a parameter, so binding only the outer name would leave {@code T} in place and
     * mint an entry per level. §5.10 admits no head abstraction ({@code v: T<...>} is not spellable), so a
     * parameter is always a whole ref -- but it may be a whole ref sitting in an argument list.
     */
    private static TypeRef bindRef(TypeRef ref, Map<String, TypeArgument> bindings) {
        TypeArgument bound = bindings.get(ref.name());
        if (bound != null) {
            if (bound instanceof TypeArgument.Ref reference) {
                return reference.ref();
            }
            // A value argument reaching a type position: the applied signature disagrees with the body's use
            // of the parameter. §5.10 infers a parameter's kind from its use, so this is the author's error.
            throw new TsonSchemaValidationException("'" + ref.name() + "' is used as a type but a value was "
                    + "applied for it (§5.10)");
        }
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeArgument> arguments = new ArrayList<>();
        for (TypeArgument argument : ref.arguments()) {
            arguments.add(argument instanceof TypeArgument.Ref nested
                    ? new TypeArgument.Ref(bindRef(nested.ref(), bindings))
                    : argument);
        }
        return new TypeRef(ref.name(), arguments);
    }

    /**
     * A record field whose {@code value_param} named a parameter, with the bound literal filled in and the
     * route dropped -- the field's state is already what §5.7's parametric rules made it at resolution, so
     * only the value changes.
     */
    private static RecordField bindValue(RecordField field, String head, Map<String, TypeArgument> bindings) {
        if (field.valueParam().isEmpty()) {
            return field;
        }
        String parameter = field.valueParam().get();
        TypeArgument bound = bindings.get(parameter);
        if (bound == null) {
            return field; // a parameter of an enclosing template, still open -- left for its own closing
        }
        if (!(bound instanceof TypeArgument.Value value)) {
            throw new TsonSchemaValidationException("'" + head + "' routes '" + parameter + "' into field '"
                    + field.name() + "' as a value, but a type was applied for it (§5.10)");
        }
        return new RecordField(field.name(), field.type(), field.state(), Optional.of(value.value()),
                Optional.empty(), field.annotations());
    }

    // ── Structural walks ─────────────────────────────────────────────────────────────────────────

    /** Every {@link TypeRef} a definition holds, mapped -- {@code source}, and whatever its body carries. */
    private static TypeDefinition mapRefs(TypeDefinition definition, UnaryOperator<TypeRef> map) {
        return new TypeDefinition(definition.source().map(map), definition.kind(), definition.parameters(),
                definition.constructor(), definition.supertypes(), definition.subtypes(),
                definition.disjoint(), mapBodyRefs(definition.body(), map), definition.position(),
                definition.annotations());
    }

    /**
     * Package-visible so {@code TemplateRegularity} can walk a body by the same code that rewrites one --
     * a body shape added here must not need remembering in a second place.
     */
    static Top mapBodyRefs(Top body, UnaryOperator<TypeRef> map) {
        return switch (body) {
            case RecordBody record -> new RecordBody(record.supertypes(),
                    record.fields().stream().map(field -> new RecordField(field.name(), map.apply(field.type()),
                            field.state(), field.value(), field.valueParam(), field.annotations())).toList(),
                    record.groups());
            case ArrayBody array -> new ArrayBody(map.apply(array.elementType()), array.state(),
                    array.unordered(), array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody mapBody -> new MapBody(map.apply(mapBody.keyType()), map.apply(mapBody.valueType()),
                    mapBody.minItems(), mapBody.maxItems());
            case TupleBody tuple -> new TupleBody(tuple.elements().stream()
                    .map(element -> new TupleElement(map.apply(element.elementType()), element.state())).toList());
            case ChoiceBody choice -> new ChoiceBody(choice.variants().stream().map(map).toList());
            case Reference reference -> new Reference(map.apply(reference.target()));
            default -> body; // an atom body holds no type references
        };
    }

    /** Every {@link RecordField} a definition holds, mapped -- only a record body has any. */
    private static TypeDefinition mapFields(TypeDefinition definition, UnaryOperator<RecordField> map) {
        if (!(definition.body() instanceof RecordBody record)) {
            return definition;
        }
        RecordBody mapped = new RecordBody(record.supertypes(), record.fields().stream().map(map).toList(),
                record.groups());
        return new TypeDefinition(definition.source(), definition.kind(), definition.parameters(),
                definition.constructor(), definition.supertypes(), definition.subtypes(),
                definition.disjoint(), mapped, definition.position(), definition.annotations());
    }

    /**
     * {@code head_arg_arg_hash} -- §8.2's own recommendation for an internal name, "a readable head plus a
     * structural hash", and the same construction {@code SchemaDesugarer} uses for an injected sugar form.
     * The hash runs over a rendering built here, never over a record's {@code toString} (documented as
     * subject to change) or its {@code hashCode} (free to differ between runs): {@code String.hashCode} is
     * specified exactly, so hashing a string built here is deterministic by contract.
     */
    private static String internalName(String head, List<TypeArgument> arguments) {
        StringBuilder readable = new StringBuilder(head);
        StringBuilder canonical = new StringBuilder();
        appendText(canonical.append('A'), head);
        canonical.append('(');
        for (TypeArgument argument : arguments) {
            switch (argument) {
                case TypeArgument.Ref ref -> {
                    readable.append('_').append(ref.ref().name());
                    appendRef(canonical.append('r'), ref.ref());
                }
                case TypeArgument.Value value -> {
                    readable.append('_').append(value.value().text());
                    // The form by name, not ordinal: inserting a constant would renumber every ordinal.
                    appendText(canonical.append('v'), value.value().form().name());
                    appendText(canonical, value.value().text());
                }
            }
        }
        canonical.append(')');
        return readable.append('_').append(String.format("%08x", canonical.toString().hashCode())).toString();
    }

    private static void appendRef(StringBuilder out, TypeRef ref) {
        appendText(out.append('n'), ref.name());
        out.append('(');
        for (TypeArgument argument : ref.arguments()) {
            switch (argument) {
                case TypeArgument.Ref nested -> appendRef(out.append('r'), nested.ref());
                case TypeArgument.Value value -> {
                    appendText(out.append('v'), value.value().form().name());
                    appendText(out, value.value().text());
                }
            }
        }
        out.append(')');
    }

    /** Length-first, so concatenation stays unambiguous whatever the text contains. */
    private static void appendText(StringBuilder out, String text) {
        out.append(text.length()).append(':').append(text);
    }
}
