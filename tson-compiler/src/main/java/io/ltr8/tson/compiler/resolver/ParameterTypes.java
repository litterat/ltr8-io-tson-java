package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * [TSON-SCHEMA] §5.10's parameters, typed by use: each parameter of an open entry gets the type an argument for it
 * is read as ({@code template_param.type}), derived from the positions it stands in, and its kind follows -- a
 * parameter is a {@code TYPE} parameter exactly when that type is {@code type_ref}.
 *
 * <p><b>The slot's declared type is what says which.</b> A held body is the constructor application as written,
 * so the body alone cannot tell a parameter naming a type from one standing for an enum member -- both are bare
 * tokens. What separates them is the constructor's own vocabulary: {@code array.element_type} is typed
 * {@code type_ref}, {@code array.min_items} {@code non_negative_integer}, {@code enum.members} a set of
 * {@code text}. §9 makes that reading general rather than a table of kernel names -- a slot holding a type
 * reference MUST be typed {@code type_ref} -- so an extension meta-schema's own constructors classify by the same
 * walk with nothing added here.
 *
 * <p><b>Four sources of a use type</b>, and the last is why this is not one pass over one declaration:
 *
 * <ul>
 *   <li>a slot typed {@code type_ref} gives {@code type_ref};</li>
 *   <li>a slot whose type resolves to an {@link Atom} gives that declared type;</li>
 *   <li>a <b>routed default or fixed value</b> -- a parameter at {@code record_field.value} -- gives the field's
 *   own declared type, read from the sibling {@code type} slot, and not {@code record_field.value}'s own type
 *   {@code value}, which says nothing. Where that field is typed by a parameter, the use type names the
 *   parameter ({@code <T, N> { w?: T ~ N }} gives {@code N} the type {@code T});</li>
 *   <li>a parameter riding another template's argument list, where meta-kernel's own {@code type_argument} doc
 *   says a parameter of <em>either</em> kind travels on the reference channel. That occurrence says nothing on
 *   its own: the type comes from the callee's parameter at that position, so it is recorded as a dependency and
 *   settled by a fixed point over the whole open-entry set.</li>
 * </ul>
 *
 * <p>Anything else -- a parameter standing where a record, a collection or a choice is declared, as in
 * {@code <T> !enum { members: T }} -- is refused here. §5.10 confines value parameters to scalars and type
 * parameters to references, so a parameter standing for a whole {@code enum_set} is neither, and refusing it
 * at the declaration is what turns "every application of this fails" into "this template is wrong".
 *
 * <p><b>Several uses must agree.</b> A parameter's type is the use type that IS-A every other; uses unordered by
 * IS-A are a resolver error at the declaration, since no argument could satisfy them all. A parameter standing
 * in both a type slot and a value slot is the commonest such case and keeps its own message.
 *
 * <p><b>A parameter the fixed point leaves ungrounded is a type parameter, and that is forced</b> -- being a
 * value parameter <em>means</em> standing in a scalar slot, and a slot is what grounds a parameter. [TSON-SCHEMA]
 * §5.10 states the rule.
 */
final class ParameterTypes {

    /** The kernel entry every type-reference slot is typed by ([TSON-SCHEMA] §9). */
    private static final String TYPE_REF = TemplateParam.TYPE_REF.name();

    /** The kernel record whose {@code value} slot routes a default or fixed value into its sibling's type. */
    private static final String RECORD_FIELD = "record_field";

    /** What a parameter's type makes it (§5.10). */
    enum Kind {
        TYPE, VALUE;

        static Kind of(TypeRef type) {
            return TemplateParam.TYPE_REF.equals(type) ? TYPE : VALUE;
        }
    }

    private ParameterTypes() {
    }

    /**
     * Every open entry's parameter types, by entry name then parameter name.
     *
     * <p>{@code entries} is the <b>whole namespace</b>, imports included, because a local template may route a
     * parameter into an imported one and take its type from there. {@code declared} is the subset this schema
     * wrote, and the only names a failure is reported against: an imported entry resolved in its own schema, and
     * reporting it here would put one document's verdict on another's declaration.
     */
    static Map<String, Map<String, TypeRef>> inferAll(Map<String, TypeDefinition> entries, Set<String> declared,
                                                       Function<String, TypeDefinition> meta,
                                                       FailureReporter reporter) {
        Map<String, Occurrences> observed = new LinkedHashMap<>();
        entries.forEach((name, definition) -> {
            if (!(definition.body() instanceof TemplateBody held)) {
                return;
            }
            Occurrences occurrences = new Occurrences(held.parameterNames());
            try {
                new Walk(occurrences, meta).body(HeldBody.of(held));
            } catch (SchemaValidationException e) {
                if (declared.contains(name)) {
                    reporter.report(name, e);
                }
                return;
            }
            observed.put(name, occurrences);
        });
        return settle(observed, entries, declared, reporter, entries::get, meta);
    }

    /** The same answer as kinds, for the materialiser, which asks only which channel an argument travels on. */
    static Map<String, Map<String, Kind>> kinds(Map<String, Map<String, TypeRef>> types) {
        Map<String, Map<String, Kind>> kinds = new LinkedHashMap<>();
        types.forEach((entry, parameters) -> kinds.put(entry, kindsOf(parameters)));
        return kinds;
    }

    private static Map<String, Kind> kindsOf(Map<String, TypeRef> parameters) {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        parameters.forEach((parameter, type) -> kinds.put(parameter, Kind.of(type)));
        return kinds;
    }

    /**
     * One template's kinds from its own body alone, for a caller with no batch pass behind it.
     *
     * <p>{@link #inferAll} runs once every declaration has resolved, which is after resolution has already closed
     * some applications on demand -- a composition supertype and a refinement source have to absorb the closed
     * entry's fields and cannot wait for the batch. Those closings ask for this instead: the template in hand has
     * resolved, so its own occurrences classify, and only a parameter needing the cross-template fixed point is
     * left undetermined. A body that will not classify yields nothing here and is reported by the batch pass,
     * which is the one that knows which declarations this schema wrote.
     */
    static Map<String, Kind> inferOne(TypeDefinition template, Function<String, TypeDefinition> meta) {
        if (!(template.body() instanceof TemplateBody held)) {
            return Map.of();
        }
        Occurrences occurrences = new Occurrences(held.parameterNames());
        try {
            new Walk(occurrences, meta).body(HeldBody.of(held));
        } catch (SchemaValidationException e) {
            return Map.of();
        }
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (String parameter : occurrences.parameters) {
            List<Use> uses = occurrences.uses.getOrDefault(parameter, List.of());
            if (uses.isEmpty()) {
                continue;
            }
            Set<Kind> seen = new HashSet<>();
            uses.forEach(use -> seen.add(Kind.of(use.type())));
            if (seen.size() != 1) {
                return Map.of();
            }
            kinds.put(parameter, seen.iterator().next());
        }
        return kinds;
    }

    /** Where a declaration whose parameters will not type is reported, entry by entry. */
    @FunctionalInterface
    interface FailureReporter {
        void report(String entryName, SchemaValidationException error);
    }

    // ── The fixed point ──────────────────────────────────────────────────────────────────────────

    /**
     * Deferred occurrences resolved against the types already known, until nothing moves. A parameter riding
     * {@code box<T>}'s argument list takes {@code box}'s own parameter type at that position, and {@code box} may
     * itself be waiting on this one -- §5.10 anticipates the cycle; this pass leaves such a parameter ungrounded,
     * and ungrounded is a type parameter.
     */
    private static Map<String, Map<String, TypeRef>> settle(Map<String, Occurrences> observed,
            Map<String, TypeDefinition> entries, Set<String> declared, FailureReporter reporter,
            Function<String, TypeDefinition> local, Function<String, TypeDefinition> meta) {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Occurrences occurrences : observed.values()) {
                for (Deferred deferred : occurrences.deferred) {
                    if (occurrences.settled.contains(deferred)) {
                        continue;
                    }
                    Occurrences callee = observed.get(deferred.head());
                    if (callee == null) {
                        continue;
                    }
                    List<String> calleeParameters = entries.get(deferred.head()).parameters();
                    if (deferred.index() >= calleeParameters.size()) {
                        occurrences.settled.add(deferred);
                        continue; // an arity error, which the materialiser reports where it is applied
                    }
                    List<Use> calleeUses = callee.uses.getOrDefault(calleeParameters.get(deferred.index()),
                            List.of());
                    if (calleeUses.isEmpty()) {
                        continue;
                    }
                    occurrences.settled.add(deferred);
                    occurrences.uses.computeIfAbsent(deferred.parameter(), ignored -> new ArrayList<>())
                            .addAll(calleeUses);
                    moved = true;
                }
            }
        }
        Map<String, Map<String, TypeRef>> result = new LinkedHashMap<>();
        observed.forEach((name, occurrences) -> {
            try {
                result.put(name, occurrences.types(local, meta));
            } catch (SchemaValidationException e) {
                if (declared.contains(name)) {
                    reporter.report(name, e);
                }
            }
        });
        return result;
    }

    /** One parameter riding another template's argument list, whose type that template's parameter fixes. */
    private record Deferred(String parameter, String head, int index) {
    }

    /**
     * One position's type, and the namespace its name resolves in: a slot's declared type is the applied
     * constructor's, and a field's declared type is the schema's own.
     */
    private record Use(TypeRef type, boolean local) {
    }

    /** What one declaration's occurrences have made of its parameters so far. */
    private static final class Occurrences {

        private final List<String> parameters;
        private final Map<String, List<Use>> uses = new LinkedHashMap<>();
        private final List<Deferred> deferred = new ArrayList<>();
        private final Set<Deferred> settled = new LinkedHashSet<>();

        Occurrences(List<String> parameters) {
            this.parameters = parameters;
        }

        boolean declares(String name) {
            return parameters.contains(name);
        }

        void observe(String parameter, TypeRef type, boolean local) {
            uses.computeIfAbsent(parameter, ignored -> new ArrayList<>()).add(new Use(type, local));
        }

        /** Each parameter's one type: the use type every other use is a supertype of, {@code type_ref} if none. */
        Map<String, TypeRef> types(Function<String, TypeDefinition> local, Function<String, TypeDefinition> meta) {
            Map<String, TypeRef> types = new LinkedHashMap<>();
            for (String parameter : parameters) {
                types.put(parameter, agree(parameter, uses.getOrDefault(parameter, List.of()), local, meta));
            }
            return types;
        }

        private static TypeRef agree(String parameter, List<Use> uses, Function<String, TypeDefinition> local,
                                     Function<String, TypeDefinition> meta) {
            if (uses.isEmpty()) {
                return TemplateParam.TYPE_REF;
            }
            Set<Kind> kinds = new HashSet<>();
            uses.forEach(use -> kinds.add(Kind.of(use.type())));
            if (kinds.size() > 1) {
                throw new SchemaValidationException("parameter '" + parameter + "' stands in both a type position "
                        + "and a value position, so no argument can satisfy both -- §5.10 gives a parameter one "
                        + "kind, inferred from where it is used");
            }
            for (Use candidate : uses) {
                if (uses.stream().allMatch(other -> isA(candidate, other, local, meta))) {
                    return candidate.type();
                }
            }
            Set<String> named = new LinkedHashSet<>();
            uses.forEach(use -> named.add(use.type().toString()));
            throw new SchemaValidationException("parameter '" + parameter + "' is read as " + String.join(" and ",
                    named) + " by the positions it stands in, and none of them IS-A the others, so no argument "
                    + "can satisfy them all -- §5.10 gives a parameter one type, derived from where it is used");
        }

        /** Whether {@code a}'s type IS-A {@code b}'s: equal, or {@code b} is among the supertypes {@code a} records. */
        private static boolean isA(Use a, Use b, Function<String, TypeDefinition> local,
                                   Function<String, TypeDefinition> meta) {
            if (a.type().equals(b.type())) {
                return true;
            }
            if (!a.type().arguments().isEmpty() || !b.type().arguments().isEmpty()) {
                return false;
            }
            TypeDefinition definition = (a.local() ? local : meta).apply(a.type().name());
            return definition != null && definition.supertypes().contains(b.type().name());
        }
    }

    // ── The walk ─────────────────────────────────────────────────────────────────────────────────

    /** One held body walked against the vocabulary of the constructor it applies. */
    private record Walk(Occurrences occurrences, Function<String, TypeDefinition> meta) {

        void body(HeldBody held) {
            String head = held.application().typeRef().orElse(null);
            if (head != null && resolve(head) instanceof RecordBody vocabulary) {
                payload(held.application().coreValue(), head, vocabulary);
            }
        }

        /**
         * A constructor's payload: a record of bindings, or §5.6's positional form -- a record with exactly one
         * unmarked field takes that field's value alone, which is how {@code !enum [a b M]} binds {@code members}.
         */
        private void payload(CoreValue payload, String constructor, RecordBody vocabulary) {
            if (payload instanceof RecordValue wire) {
                record(wire, constructor, vocabulary);
                return;
            }
            List<RecordField> unmarked = vocabulary.fields().stream().filter(f -> !f.optional()).toList();
            if (unmarked.size() == 1) {
                value(payload, unmarked.getFirst().type());
            }
        }

        /** Each written slot walked against the field the constructor declares for it. */
        private void record(RecordValue wire, String constructor, RecordBody vocabulary) {
            for (RecordValue.Field written : wire.fields()) {
                CoreValue value = written.value().value().coreValue();
                if (RECORD_FIELD.equals(constructor) && WireForm.VALUE.equals(written.name())
                        && value instanceof TokenValue token && occurrences.declares(token.text())) {
                    routed(token.text(), wire);
                    continue;
                }
                vocabulary.fields().stream().filter(f -> f.name().equals(written.name())).findFirst()
                        .ifPresent(field -> value(value, field.type()));
            }
        }

        /**
         * A routed default or fixed value: the parameter is read as the field's own declared type, which the
         * author wrote and which resolves in the schema's own namespace -- or, where that type is itself one of
         * this template's parameters, as whatever that parameter's argument names.
         */
        private void routed(String parameter, RecordValue field) {
            Optional<CoreValue> declared = WireForm.field(field, WireForm.TYPE);
            TypeRef type = switch (declared.orElse(null)) {
                case TokenValue token -> TypeRef.of(token.text());
                case RecordValue application when WireForm.isApplication(application) ->
                        WireForm.typeRefOf(application);
                case null, default -> null;
            };
            if (type == null) {
                return; // no type to route into: record_field's own reader refuses the field
            }
            occurrences.observe(parameter, type, true);
        }

        /** One written value against the type its slot declares. */
        private void value(CoreValue written, TypeRef declared) {
            String slot = declared.name();
            Top type = resolve(slot);
            switch (written) {
                case TokenValue token when occurrences.declares(token.text()) -> token(token.text(), declared, type);
                case ArrayValue array -> elements(array, type);
                case MapValue map when type instanceof MapBody entries -> entries(map, entries);
                case RecordValue record when TYPE_REF.equals(slot) -> application(record);
                case RecordValue record when type instanceof RecordBody nested -> record(record, slot, nested);
                default -> {
                }
            }
        }

        /**
         * A map slot: the key against {@code key_type}, the value against {@code value_type}. Both halves, because
         * a parameter reaches either -- core's {@code extern_type => <S, T> !scoped { scope: [EXTERN] schemas:
         * { S => [T] } }} puts one in each, {@code S} in a {@code uri}-typed key and {@code T} inside the
         * {@code [type_name]} the value names.
         */
        private void entries(MapValue map, MapBody declared) {
            for (MapValue.MapEntry entry : map.entries()) {
                value(entry.key().coreValue(), declared.keyType());
                value(entry.value().value().coreValue(), declared.valueType());
            }
        }

        /** A parameter standing at a slot: the slot's declared type is the whole of the verdict. */
        private void token(String parameter, TypeRef declared, Top type) {
            if (TYPE_REF.equals(declared.name()) || type instanceof Atom) {
                occurrences.observe(parameter, declared, false);
            } else {
                throw new SchemaValidationException("parameter '" + parameter + "' stands where '" + declared.name()
                        + "' is declared, which is neither a type reference nor a scalar -- §5.10 binds a value "
                        + "parameter to scalars only and a type parameter to references, so nothing could be "
                        + "applied here");
            }
        }

        private void elements(ArrayValue array, Top type) {
            TypeRef element = switch (type) {
                case ArrayBody arrayBody -> arrayBody.elementType();
                default -> null;
            };
            if (element == null && !(type instanceof TupleBody)) {
                return; // not a collection slot -- a shape error the constructor's own reader reports
            }
            for (int i = 0; i < array.elements().size(); i++) {
                TypeRef declared = element != null ? element
                        : ((TupleBody) type).elements().get(Math.min(i, ((TupleBody) type).elements().size() - 1))
                                .elementType();
                value(array.elements().get(i).value().coreValue(), declared);
            }
        }

        /**
         * A slot typed {@code type_ref} holding an application rather than a bare name. The head is a type name;
         * each argument that names a parameter of this declaration is <b>deferred</b>, since meta-kernel's
         * {@code type_argument} puts a parameter of either kind on the reference channel.
         *
         * <p>The members are read through {@link WireForm}, not by matching {@code "name"} and {@code "arguments"}
         * here: what an application looks like on the wire is one class's answer, and a walk that re-derives it
         * is a second opinion waiting to disagree.
         */
        private void application(RecordValue application) {
            String head = WireForm.field(application, WireForm.NAME)
                    .filter(TokenValue.class::isInstance).map(v -> ((TokenValue) v).text()).orElse(null);
            Optional<CoreValue> arguments = WireForm.field(application, WireForm.ARGUMENTS);
            if (head == null || arguments.isEmpty() || !(arguments.get() instanceof ArrayValue list)) {
                return;
            }
            for (int i = 0; i < list.elements().size(); i++) {
                argument(list.elements().get(i).value().coreValue(), head, i);
            }
        }

        /** One {@code type_argument}: a bare parameter name under {@code name} defers, everything else does not. */
        private void argument(CoreValue argument, String head, int index) {
            if (!(argument instanceof RecordValue record)) {
                return;
            }
            for (RecordValue.Field member : record.fields()) {
                CoreValue value = member.value().value().coreValue();
                if (member.name().equals(WireForm.VALUE)) {
                    continue; // a literal argument says nothing about this declaration's parameters
                }
                if (value instanceof TokenValue token && occurrences.declares(token.text())) {
                    occurrences.deferred.add(new Deferred(token.text(), head, index));
                } else if (value instanceof RecordValue nested) {
                    application(nested); // a nested application: `box<inner<T>>`
                }
            }
        }

        /** A name's resolved body, reference chains followed -- the slot type a written value faces. */
        private Top resolve(String name) {
            TypeDefinition definition = meta.apply(name);
            for (int hops = 0; definition != null && definition.body() instanceof Reference reference
                    && hops < 32; hops++) {
                definition = meta.apply(reference.target().name());
            }
            return definition == null ? null : definition.body();
        }
    }
}
