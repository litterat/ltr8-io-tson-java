package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.InstanceTemplate;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TemplateArgument;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
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
 * <p><b>Three shapes close here, by three paths.</b> A <b>record</b> template -- parameters occupying field
 * types and field values -- is substituted and kept: the result is still a record, one with its parameters
 * filled in. An <b>open instance</b>, whose body is an {@code instance_template} (what a sugar form over a
 * parameter lifts to), stops being a template altogether once its bindings go concrete: it is the
 * constructor body those bindings always described, so it is bound through that constructor's own reader
 * and the entry carries an ordinary body. See {@link #closeInstanceTemplate}. A <b>reference</b> template --
 * §5.10's partial application, {@code uuid_pair => <B> pair<uuid, B>} -- is neither: it <em>is</em> the
 * application it names with some arguments still open, so applying it composes the two argument lists and
 * closes the result, minting no entry of its own (§5.10: "no intermediate entry per alias hop").
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

    /** The entries produced, keyed by their derived internal name, in creation order. */
    private final Map<String, TypeDefinition> materialised = new LinkedHashMap<>();

    /**
     * Which of {@link #materialised} are <b>synthetic</b> entries rather than instantiation entries -- the
     * closed forms {@link #closeInstanceTemplate} mints, which are indistinguishable from the entry a
     * directly-written {@code [pixel; 1920]} lifts to and are the same entry when both appear (§8.2).
     *
     * <p>§8.2 marks only these: an instantiation entry carries no {@code @synthetic}, its {@code source}
     * being an application where a synthetic's is a bare constructor. The caller reads this set to put the
     * marker on the right keys.
     */
    private final Set<String> synthetics = new LinkedHashSet<>();

    /** Applications currently being closed, for the knot-tying memo and the termination guard's chain. */
    private final Set<String> closing = new LinkedHashSet<>();

    /**
     * Applications of <em>reference</em> templates currently composing. They mint no entry of their own
     * (§5.10: "no intermediate entry per alias hop"), so {@link #closing}'s knot-tying answer -- name the
     * entry under construction -- has nothing to name for them, and an alias that applies itself would hand
     * back a name nothing ever defines. Tracked separately so that case is a diagnosis instead.
     */
    private final Set<String> aliasClosing = new LinkedHashSet<>();

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
     * <p>Distinct from §5.10.1's productivity rule, which is about a type with no finite <em>data</em>
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

    /**
     * How a closed {@code instance_template} becomes an ordinary constructor body -- the constructor's own
     * compiled reader, the same one a written {@code !array { ... }} binds through. Using it is what makes
     * {@code min_items: "two"} an ordinary read error rather than a check this class would have to grow: the
     * bindings a template defers are exactly the ones a closed instance has always had checked for it.
     *
     * <p>{@code null} for a caller with no compiled meta reader to offer -- every hand-built test fixture,
     * and the bootstrap. Closing an open <em>instance</em> template then fails loudly instead of silently
     * producing an entry with an unread body; a record template needs none of this and is unaffected.
     */
    private final DefinitionMetaReader metaReader;

    /**
     * The entry names desugaring generated rather than the author writing them.
     *
     * <p><b>An application of one is machinery, not a use site.</b> Closing an authored template records the
     * application in an instantiation entry, because {@code grid<pixel, 3>} is something someone wrote and
     * §8.2 keys identity on it. Closing a generated open synthetic records nothing: nobody wrote
     * {@code array_p0_p1_p1_06c4e11f<pixel, 3>}, and an entry named for it would key identity on an internal
     * name D6 is explicit must not be relied on.
     */
    private final Set<String> generated;

    TemplateMaterialiser(DefinitionGetter namespace, BiConsumer<String, TypeDefinition> publish) {
        this(namespace, publish, null, Set.of());
    }

    TemplateMaterialiser(DefinitionGetter namespace, BiConsumer<String, TypeDefinition> publish,
            DefinitionMetaReader metaReader, Set<String> generated) {
        this.namespace = namespace;
        this.publish = publish;
        this.metaReader = metaReader;
        this.generated = generated;
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

    /**
     * The subset of what {@link #materialise} returned that is a synthetic entry, whose key carries the
     * derived {@code @synthetic} marker (§8.2). Everything else it returned is an instantiation entry, which
     * deliberately carries none.
     */
    Set<String> syntheticNames() {
        return Set.copyOf(synthetics);
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
     *
     * <p>An application this pass cannot close <b>keeps its argument list</b>, rather than collapsing to its
     * bare head. The list is the evidence the author supplied arguments, and the linker reports on what it is
     * handed: a head stripped of its arguments in a {@code source} position -- the one slot whose lookup falls
     * back to the governing meta's structure namespace -- is found there and then faulted for supplying no
     * arguments, which is the opposite of what the author wrote. Keeping them lets the honest verdict through:
     * a name the type-name namespace does not hold is an unresolved reference, applied or not.
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
        String entry = instantiate(ref.name(), arguments);
        return entry == null ? new TypeRef(ref.name(), arguments, ref.annotations()) : TypeRef.of(entry);
    }

    /**
     * The entry name a fully-bound application denotes, creating the entry on first sight, or {@code null}
     * when the head names nothing in scope -- that is {@code TsonSchemaLinker}'s verdict to give as an
     * unresolved reference, not this pass's to guess at.
     */
    private String instantiate(String head, List<TypeArgument> arguments) {
        TypeDefinition template = namespace.getTypeDefinition(head);
        if (template == null) {
            return null; // unresolved head -- the linker's verdict, not this pass's
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
        if (aliasClosing.contains(name)) {
            throw new TsonSchemaValidationException("'" + head + "<...>' is a reference template whose own "
                    + "body applies it again, so composing it never reaches a type with a body (§5.10). The "
                    + "chain begins " + chain() + ". A reference template must eventually name a declared "
                    + "type; recursion belongs in a record, tuple or choice body, where a field can carry it");
        }
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
            // Both template shapes reach here, so both get the memo, the depth backstop and one publish
            // path. An open *instance* used to short-circuit ahead of all three, which left a template
            // applying itself (`weird => <T> [weird<T>]`) recursing to a StackOverflowError instead of
            // tying the knot.
            if (template.body() instanceof InstanceTemplate open) {
                String formName = closeInstanceTemplate(head, template, open, bind(parameters, arguments));
                if (generated.contains(head)) {
                    // A generated head closing its own intermediate form: the form entry *is* the answer, and
                    // an instantiation naming this head would carry an internal name into identity.
                    return formName;
                }
                TypeDefinition alias = instantiationOf(head, arguments, formName);
                materialised.put(name, alias);
                publish.accept(name, alias);
                return name;
            }
            if (template.body() instanceof Reference) {
                // §5.10 partial application. A reference template *is* the application it names with some
                // arguments still open, so applying it composes the two argument lists and closes the
                // result: `uuid_pair<int32>` is `pair<text, int32>`, the same entry writing that directly
                // denotes. §5.10 is explicit that this mints no intermediate entry per alias hop -- the
                // origin survives in the composed entry's own `source`.
                //
                // The application comes from `source`, not from the body: a reference body holds a bare
                // `type_name` and has nowhere to keep the still-open argument list (see Reference).
                TypeRef application = template.source().orElseThrow(() -> new IllegalStateException(
                        "reference template '" + name + "' has no source to apply -- TypeDefinition.reference "
                                + "records the application there, and nothing else can reconstruct it"));
                aliasClosing.add(name);
                return close(bindRef(application, bind(parameters, arguments))).name();
            }
            TypeDefinition instantiation = substitute(template, head, parameters, arguments);
            materialised.put(name, instantiation);
            publish.accept(name, instantiation);
            return name;
        } finally {
            closing.remove(name);
            aliasClosing.remove(name);
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
        Map<String, TypeArgument> bindings = bind(parameters, arguments);
        TypeDefinition bound = mapFields(mapRefs(template, ref -> bindRef(ref, bindings)),
                field -> bindValue(field, head, bindings));
        // Only the *body* is closed. `source` records the application as written -- it is the key §8.2
        // compares by, so closing it would replace `box<text>` with the very entry it identifies.
        return new TypeDefinition(Optional.of(new TypeRef(head, arguments)), bound.kind(), List.of(),
                bound.constructor(), bound.supertypes(), bound.subtypes(), bound.disjoint(),
                mapBodyRefs(bound.body(), this::close), Optional.empty(), bound.annotations());
    }

    /** Each parameter of the applied signature against the argument applied for it, in order. */
    private static Map<String, TypeArgument> bind(List<String> parameters, List<TypeArgument> arguments) {
        Map<String, TypeArgument> bindings = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            bindings.put(parameters.get(i), arguments.get(i));
        }
        return bindings;
    }

    // ── Closing an open instance (§5.10, D7) ─────────────────────────────────────────────────────

    /**
     * The entry an application of an <b>open instance</b> denotes -- a template whose body is an {@code
     * instance_template} rather than a record. Substituting turns its bindings concrete, and the result is no
     * longer a template at all: it is the constructor body those bindings always described, so it is bound
     * through that constructor's own reader and the entry carries an ordinary body.
     *
     * <p><b>Two entries come out of it, because one cannot carry two identities.</b> The body itself is a
     * closed <em>synthetic</em>, named for the form and sourced to the constructor it builds (§8.2) -- an
     * open synthetic's own name is internal, so keying it on the application would make identity depend on an
     * unstable name, and would leave {@code [text]} written directly and {@code [T]} closed to {@code text}
     * on two entries for one type. But the same closure is also an <em>instantiation</em> of the template,
     * and §8.2 keys that on the flattened application. So this publishes the synthetic and returns a
     * reference entry pointing at it, whose {@code source} is the application.
     *
     * <p>Without the second entry nothing in resolver output records that {@code grid<pixel, 3>} was ever
     * written: the field would name the array directly, and the template's name would vanish. The record
     * template shape gets this for free, since substituting a record yields a record -- structurally distinct
     * from any synthetic, so it can be the instantiation itself.
     */
    private String closeInstanceTemplate(String head, TypeDefinition template, InstanceTemplate open,
            Map<String, TypeArgument> bindings) {
        // Three steps, and the order is the whole of it: replace the `param` bindings, then bind the
        // parameters *inside* an application a binding holds (`tree<p0>` becomes `tree<text>`), and only then
        // close what results. Closing before binding would close `tree<p0>` -- an application of an argument
        // nothing has supplied.
        InstanceTemplate substituted = substituteBindings(open, head, bindings);
        InstanceTemplate bound = (InstanceTemplate) mapBodyRefs(
                mapBodyRefs(substituted, ref -> bindRef(ref, bindings)), this::close);
        String target = bound.target();
        List<RecordValue.Field> fields = new ArrayList<>();
        for (Map.Entry<String, TemplateArgument> binding : bound.bindings().entrySet()) {
            fields.add(new RecordValue.Field(binding.getKey(), wire(head, binding)));
        }
        String formName = SchemaDesugarer.internalName(target, fields);
        if (namespace.getTypeDefinition(formName) != null) {
            return formName; // already built, here or by the desugar phase -- one entry per form, schema-wide
        }
        if (metaReader == null) {
            throw new IllegalStateException("'" + head + "<...>' closes to a '" + target + "' body, and this "
                    + "materialiser was built without a compiled meta reader to bind it through");
        }
        DataValue value = new DataValue(List.of(), Optional.of(target), new RecordValue(fields));
        Top body;
        try {
            body = metaReader.read(target, value);
        } catch (TsonReadException e) {
            // The bindings a template defers are checked here and nowhere else (§8.2): `<T, N> [T; N]` is a
            // fine declaration, and `vector<text, "two">` is where it stops being one.
            throw new TsonSchemaValidationException("'" + head + "<...>' substitutes into a body that is not "
                    + "valid data for '" + target + "', the constructor's own constraint vocabulary -- "
                    + e.getMessage(), e);
        }
        TypeDefinition closed = new TypeDefinition(Optional.of(TypeRef.of(target)), template.kind(), List.of(),
                false, List.of(), List.of(), Optional.empty(), body);
        materialised.put(formName, closed);
        synthetics.add(formName);
        publish.accept(formName, closed);
        return formName;
    }

    /**
     * The instantiation entry for an application whose closure is a synthetic: a reference to that synthetic,
     * sourced to the application itself. {@code Reference} bodies are collapsed by the compiler, so the hop
     * costs nothing at read time -- what it buys is that §8.2's "instantiation entries are keyed on the
     * flattened application recorded in {@code source}" is true of this template shape too, not only of the
     * record one.
     */
    private static TypeDefinition instantiationOf(String head, List<TypeArgument> arguments, String formName) {
        return new TypeDefinition(Optional.of(new TypeRef(head, arguments)), TypeKind.REFERENCE, List.of(),
                false, List.of(), List.of(), Optional.empty(), new Reference(formName));
    }

    /** The same open body with every {@code param} binding replaced by the argument applied for it. */
    private static InstanceTemplate substituteBindings(InstanceTemplate open, String head,
            Map<String, TypeArgument> bindings) {
        Map<String, TemplateArgument> substituted = new LinkedHashMap<>();
        for (Map.Entry<String, TemplateArgument> binding : open.bindings().entrySet()) {
            if (!(binding.getValue() instanceof TemplateArgument.Param parameter)) {
                substituted.put(binding.getKey(), binding.getValue());
                continue;
            }
            TypeArgument argument = bindings.get(parameter.param());
            if (argument == null) {
                // A parameter of an enclosing template, still open: this application is not the one that
                // closes it. Nothing today reaches here -- an application is closed only once every argument
                // is concrete -- and leaving the binding open is what keeps that true rather than assuming it.
                throw new UnsupportedOperationException("'" + head + "<...>' leaves binding '"
                        + binding.getKey() + "' bound to '" + parameter.param() + "', a parameter this "
                        + "application does not supply, and closing an open form onto another open form is "
                        + "not implemented (§5.10)");
            }
            substituted.put(binding.getKey(), argument instanceof TypeArgument.Ref reference
                    ? new TemplateArgument.Ref(reference.ref())
                    : new TemplateArgument.Value(((TypeArgument.Value) argument).value()));
        }
        return new InstanceTemplate(open.target(), substituted);
    }

    /**
     * One closed binding as the wire value the constructor's reader expects: a bare token either way, since
     * a reference in the positional form (§5.6) and a literal are spelled alike. The channel it arrived on is
     * what said which it was, and that question is now answered.
     */
    private static ScopedValue wire(String head, Map.Entry<String, TemplateArgument> binding) {
        TokenValue token = switch (binding.getValue()) {
            case TemplateArgument.Value value -> new TokenValue(value.value().text(),
                    switch (value.value().form()) {
                        case UNQUOTED -> TokenForm.UNQUOTED;
                        case SINGLE_LINE_QUOTED -> TokenForm.SINGLE_LINE_QUOTED;
                        case MULTI_LINE_QUOTED -> TokenForm.MULTI_LINE_QUOTED;
                    });
            case TemplateArgument.Ref reference -> new TokenValue(reference.typeRef().name(), TokenForm.UNQUOTED);
            case TemplateArgument.Param parameter -> throw new IllegalStateException("'" + head
                    + "<...>' still binds '" + binding.getKey() + "' to the parameter '" + parameter.param()
                    + "' after substitution");
        };
        return new ScopedValue(Optional.empty(), new DataValue(List.of(), Optional.empty(), token));
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
            arguments.add(bindArgument(argument, bindings));
        }
        return new TypeRef(ref.name(), arguments);
    }

    /**
     * One argument of an application inside a template body, with a parameter replaced by whatever was bound
     * to it -- <b>on the channel it was bound on</b>, which is what separates this from {@link #bindRef}.
     * An argument list is the one position where a type and a value are equally at home, so a value parameter
     * passed straight through ({@code array_p0<N>} inside {@code <N> { a: [text; N] } }) stays a value; a
     * parameter in any other position is a type by construction, and a value arriving there is the error
     * {@link #bindRef} reports.
     */
    private static TypeArgument bindArgument(TypeArgument argument, Map<String, TypeArgument> bindings) {
        if (!(argument instanceof TypeArgument.Ref nested)) {
            return argument;
        }
        TypeArgument bound = bindings.get(nested.ref().name());
        return bound != null && nested.ref().arguments().isEmpty() ? bound
                : new TypeArgument.Ref(bindRef(nested.ref(), bindings));
    }

    /**
     * A record field whose {@code value_param} named a parameter, with the bound literal filled in, the
     * route dropped, and the state taken to where §5.7 says a concrete value takes it.
     *
     * <p><b>This is where a routed {@code =} becomes fixed</b>, and it is the only place it can be. §5.7
     * puts a parametric {@code = P} in {@code REQUIRED} at the declaration -- "nothing is fixed at
     * declaration, the value does not exist yet" -- and defers the rest to one sentence: "fixation happens
     * downstream, where values are concrete". Here is downstream. Without the promotion the closed entry
     * carries the right value on a field that does not enforce it, so {@code response<order, 201>} accepts
     * a status of 999 where the literal {@code status: int32 = 201} refuses it -- a constraint the author
     * wrote, silently absent from the type it governs.
     *
     * <p><b>The two parametric spellings are told apart by the state they arrived in</b>, which is what
     * makes this recoverable at all: §5.7 sends {@code = P} to {@code REQUIRED} and {@code ~ P} to {@code
     * REQUIRED_DEFAULT}, so a bound {@code REQUIRED} field is a routed {@code =} and nothing else -- an
     * unrouted field never reaches here, {@code value_param} being what selects it. A default stays a
     * default: data may still override it.
     *
     * <p>§5.7 names this downstream outright: "fixation happens at materialisation, where values are
     * concrete" -- a field whose {@code value_param} binds to a concrete argument takes the state its
     * literal spelling would have.
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
        FieldState state = field.state() == FieldState.REQUIRED ? FieldState.REQUIRED_FIXED : field.state();
        return new RecordField(field.name(), field.type(), state, Optional.of(value.value()),
                Optional.empty(), field.annotations());
    }

    // ── Structural walks ─────────────────────────────────────────────────────────────────────────

    /** Every {@link TypeRef} a definition holds, mapped -- {@code source}, and whatever its body carries. */
    private static TypeDefinition mapRefs(TypeDefinition definition, UnaryOperator<TypeRef> map) {
        Optional<TypeRef> source = definition.source().map(map);
        return new TypeDefinition(source, definition.kind(), definition.parameters(),
                definition.constructor(), definition.supertypes(), definition.subtypes(),
                definition.disjoint(), mapAliasBody(definition, source, map), definition.position(),
                definition.annotations());
    }

    /**
     * {@link #mapBodyRefs}, plus the one case a body cannot map for itself: <b>a declaration that aliases an
     * application</b>. {@code created => response<text, 201>} records the application in {@code source} and
     * its head in the {@link Reference} body, a reference body holding a bare {@code type_name} with no
     * arguments of its own. Closing renames what {@code source} denotes -- to the entry just minted for it
     * -- and the body has to follow, since mapping a name that carries no arguments leaves it unchanged and
     * the alias pointing at the open template instead of its instantiation.
     *
     * <p>Only where the body was tracking {@code source} to begin with. A materialised instantiation targets
     * the entry minted for it rather than its own source's head ({@link #instantiationOf}), and that is not
     * a body following anything.
     */
    private static Top mapAliasBody(TypeDefinition definition, Optional<TypeRef> mappedSource,
                                     UnaryOperator<TypeRef> map) {
        Top body = mapBodyRefs(definition.body(), map);
        if (body instanceof Reference reference && definition.source().isPresent() && mappedSource.isPresent()
                && reference.target().equals(definition.source().get().name())
                && !reference.target().equals(mappedSource.get().name())) {
            return new Reference(mappedSource.get().name());
        }
        return body;
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
            // A bare name still substitutes: the head itself may be a parameter. It cannot gain arguments
            // doing so -- a reference body has no channel for them -- so only the name is taken back.
            case Reference reference -> new Reference(map.apply(TypeRef.of(reference.target())).name());
            case InstanceTemplate template -> {
                Map<String, TemplateArgument> bindings = new LinkedHashMap<>();
                template.bindings().forEach((slot, binding) -> bindings.put(slot,
                        binding instanceof TemplateArgument.Ref reference
                                ? new TemplateArgument.Ref(map.apply(reference.typeRef()))
                                : binding));
                yield new InstanceTemplate(template.target(), bindings);
            }
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
