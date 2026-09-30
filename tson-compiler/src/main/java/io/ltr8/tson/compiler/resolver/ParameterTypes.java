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
import io.ltr8.tson.schema.meta.TypeArgument;
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
 * in both a type slot and a value slot is the commonest such case and keeps its own message. For a type
 * parameter the same rule runs over the <b>bounds</b> its uses carry -- a parameter passed to {@code box<T>}
 * inherits {@code box}'s bound for the position -- so an unannotated parameter is bounded as narrowly as its uses
 * require.
 *
 * <p><b>A written type narrows what the positions give</b> ({@code <T: text, N: int8>}), read by the kind they
 * give: on a value parameter it replaces {@code type} and must IS-A the derived one; on a type parameter it is the
 * bound and must IS-A any bound the uses carry. Two same-named entries with identical bodies -- a core type and
 * the kernel original it copies -- count as one type here, so {@code <N: non_negative_integer>} over a slot typed
 * by the kernel's {@code non_negative_integer} is the narrowing it reads as.
 *
 * <p><b>An imported template is taken as recorded</b>, not walked again: its schema resolved it, and its recorded
 * parameters carry what its author wrote, which its held body does not.
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
     * Every local open entry's parameters, by entry name then parameter name.
     *
     * <p>{@code entries} is the <b>whole namespace</b>, imports included, because a local template may route a
     * parameter into an imported one and take its type from there. {@code local} is the subset this schema
     * produced: those are walked, and the only names a failure is reported against, since an imported entry
     * resolved in its own schema. {@code written} is what each local declaration's parameter list wrote.
     */
    static Map<String, Map<String, TemplateParam>> inferAll(Map<String, TypeDefinition> entries, Set<String> local,
                                                             Map<String, Map<String, TypeRef>> written,
                                                             Function<String, TypeDefinition> meta,
                                                             FailureReporter reporter) {
        Map<String, Occurrences> observed = new LinkedHashMap<>();
        Map<String, Map<String, TemplateParam>> recorded = new LinkedHashMap<>();
        entries.forEach((name, definition) -> {
            if (!(definition.body() instanceof TemplateBody held)) {
                return;
            }
            if (!local.contains(name)) {
                Map<String, TemplateParam> params = new LinkedHashMap<>();
                held.parameters().forEach(p -> params.put(p.name(), p));
                recorded.put(name, params);
                return;
            }
            Occurrences occurrences = new Occurrences(held.parameterNames(), written.getOrDefault(name, Map.of()));
            try {
                new Walk(occurrences, meta).body(HeldBody.of(held));
            } catch (SchemaValidationException e) {
                reporter.report(name, e);
                return;
            }
            observed.put(name, occurrences);
        });
        return settle(observed, recorded, entries, reporter, entries::get, meta);
    }

    /** The same answer as kinds, for the materialiser, which asks only which channel an argument travels on. */
    static Map<String, Map<String, Kind>> kinds(Map<String, Map<String, TemplateParam>> parameters) {
        Map<String, Map<String, Kind>> kinds = new LinkedHashMap<>();
        parameters.forEach((entry, params) -> {
            Map<String, Kind> each = new LinkedHashMap<>();
            params.forEach((name, param) -> each.put(name, Kind.of(param.type())));
            kinds.put(entry, each);
        });
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
        Occurrences occurrences = new Occurrences(held.parameterNames(), Map.of());
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
     * {@code box<T>}'s argument list takes {@code box}'s own parameter at that position -- its type, and its bound
     * -- and {@code box} may itself be waiting on this one; §5.10 anticipates the cycle, and this pass leaves such
     * a parameter ungrounded, which is an unbounded type parameter.
     */
    private static Map<String, Map<String, TemplateParam>> settle(Map<String, Occurrences> observed,
            Map<String, Map<String, TemplateParam>> recorded, Map<String, TypeDefinition> entries,
            FailureReporter reporter, Function<String, TypeDefinition> local, Function<String, TypeDefinition> meta) {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Occurrences occurrences : observed.values()) {
                for (Deferred deferred : occurrences.deferred) {
                    if (occurrences.settled.contains(deferred)) {
                        continue;
                    }
                    TypeDefinition callee = entries.get(deferred.head());
                    if (callee == null) {
                        continue;
                    }
                    List<String> calleeParameters = callee.parameters();
                    if (deferred.index() >= calleeParameters.size()) {
                        occurrences.settled.add(deferred);
                        continue; // an arity error, which the materialiser reports where it is applied
                    }
                    String calleeParameter = calleeParameters.get(deferred.index());
                    TemplateParam known = recorded.containsKey(deferred.head())
                            ? recorded.get(deferred.head()).get(calleeParameter)
                            : observed.containsKey(deferred.head())
                                    ? observed.get(deferred.head()).current(calleeParameter, local, meta)
                                    : null;
                    if (known == null) {
                        continue;
                    }
                    occurrences.settled.add(deferred);
                    occurrences.observe(deferred.parameter(),
                            new Use(known.type(), known.bound(), !recorded.containsKey(deferred.head())));
                    moved = true;
                }
            }
        }
        Map<String, Map<String, TemplateParam>> result = new LinkedHashMap<>();
        observed.forEach((name, occurrences) -> {
            try {
                result.put(name, occurrences.parameters(local, meta));
            } catch (SchemaValidationException e) {
                reporter.report(name, e);
            }
        });
        return result;
    }

    /** One parameter riding another template's argument list, whose type that template's parameter fixes. */
    private record Deferred(String parameter, String head, int index) {
    }

    /**
     * One position's type, the bound it carries where it is a type position, and the namespace its names resolve
     * in first: a slot's declared type is the applied constructor's, and a field's declared type is the schema's.
     */
    private record Use(TypeRef type, Optional<TypeRef> bound, boolean local) {

        Use(TypeRef type, boolean local) {
            this(type, Optional.empty(), local);
        }
    }

    /** What one declaration's occurrences have made of its parameters so far. */
    private static final class Occurrences {

        private final List<String> parameters;
        private final Map<String, TypeRef> written;
        private final Map<String, List<Use>> uses = new LinkedHashMap<>();
        private final List<Deferred> deferred = new ArrayList<>();
        private final Set<Deferred> settled = new LinkedHashSet<>();

        Occurrences(List<String> parameters, Map<String, TypeRef> written) {
            this.parameters = parameters;
            this.written = written;
        }

        boolean declares(String name) {
            return parameters.contains(name);
        }

        void observe(String parameter, TypeRef type, boolean local) {
            observe(parameter, new Use(type, local));
        }

        void observe(String parameter, Use use) {
            uses.computeIfAbsent(parameter, ignored -> new ArrayList<>()).add(use);
        }

        /** One parameter as far as the fixed point has got, or {@code null} while nothing grounds it. */
        TemplateParam current(String parameter, Function<String, TypeDefinition> local,
                              Function<String, TypeDefinition> meta) {
            if (!uses.containsKey(parameter) && !written.containsKey(parameter)) {
                return null;
            }
            try {
                return parameter(parameter, local, meta);
            } catch (SchemaValidationException e) {
                return null; // reported against this declaration when it settles
            }
        }

        /** Every parameter, settled. */
        Map<String, TemplateParam> parameters(Function<String, TypeDefinition> local,
                                              Function<String, TypeDefinition> meta) {
            Map<String, TemplateParam> result = new LinkedHashMap<>();
            for (String parameter : parameters) {
                result.put(parameter, parameter(parameter, local, meta));
            }
            return result;
        }

        /**
         * One parameter: the kind its uses agree on, the narrowest of their types or bounds, then what the
         * declaration wrote, which must narrow that further. With no use at all it is a type parameter, bounded
         * by what was written if anything was.
         */
        private TemplateParam parameter(String parameter, Function<String, TypeDefinition> local,
                                        Function<String, TypeDefinition> meta) {
            List<Use> all = uses.getOrDefault(parameter, List.of());
            Optional<TypeRef> declared = Optional.ofNullable(written.get(parameter));
            Set<Kind> kinds = new HashSet<>();
            all.forEach(use -> kinds.add(Kind.of(use.type())));
            if (kinds.size() > 1) {
                throw new SchemaValidationException("parameter '" + parameter + "' stands in both a type position "
                        + "and a value position, so no argument can satisfy both -- §5.10 gives a parameter one "
                        + "kind, inferred from where it is used");
            }
            if (all.isEmpty() || kinds.contains(Kind.TYPE)) {
                List<Use> bounds = all.stream().filter(use -> use.bound().isPresent())
                        .map(use -> new Use(use.bound().get(), use.local())).toList();
                Optional<Use> inherited = bounds.isEmpty() ? Optional.empty()
                        : Optional.of(narrowest(parameter, "bounded by", bounds, local, meta));
                if (declared.isPresent()) {
                    Use mine = new Use(declared.get(), true);
                    if (inherited.isPresent() && !isA(mine, inherited.get(), local, meta)) {
                        throw new SchemaValidationException("parameter '" + parameter + "' is declared '"
                                + parameter + ": " + spell(declared.get()) + "', and a template it is passed to "
                                + "needs a type that IS-A " + spell(inherited.get().type()) + ", which "
                                + spell(declared.get()) + " is not -- a written bound narrows what the uses "
                                + "require, and never widens it");
                    }
                    return new TemplateParam(parameter, TemplateParam.TYPE_REF, declared);
                }
                return new TemplateParam(parameter, TemplateParam.TYPE_REF, inherited.map(Use::type));
            }
            Use derived = narrowest(parameter, "read as", all, local, meta);
            if (declared.isPresent()) {
                Use mine = new Use(declared.get(), true);
                if (!isA(mine, derived, local, meta)) {
                    throw new SchemaValidationException("parameter '" + parameter + "' is declared '" + parameter
                            + ": " + spell(declared.get()) + "', and the positions it stands in read it as "
                            + spell(derived.type()) + ", which " + spell(declared.get()) + " does not IS-A -- a "
                            + "written type narrows what the positions give, and never replaces it");
                }
                return new TemplateParam(parameter, declared.get());
            }
            return new TemplateParam(parameter, derived.type());
        }

        /** The use every other use is a supertype of, or a resolver error where no such use exists. */
        private static Use narrowest(String parameter, String verb, List<Use> uses,
                                     Function<String, TypeDefinition> local, Function<String, TypeDefinition> meta) {
            for (Use candidate : uses) {
                if (uses.stream().allMatch(other -> isA(candidate, other, local, meta))) {
                    return candidate;
                }
            }
            Set<String> named = new LinkedHashSet<>();
            uses.forEach(use -> named.add(spell(use.type())));
            throw new SchemaValidationException("parameter '" + parameter + "' is " + verb + " "
                    + String.join(" and ", named) + " by the positions it stands in, and none of them IS-A the "
                    + "others, so no argument can satisfy them all -- §5.10 gives a parameter one type, derived "
                    + "from where it is used");
        }

        /**
         * Whether {@code a}'s type IS-A {@code b}'s: equal, {@code b} among the supertypes {@code a} records, or
         * the same name for two entries with the same body -- a core type and the kernel original it copies.
         */
        static boolean isA(Use a, Use b, Function<String, TypeDefinition> local, Function<String, TypeDefinition> meta) {
            if (a.type().equals(b.type())) {
                return true;
            }
            if (!a.type().arguments().isEmpty() || !b.type().arguments().isEmpty()) {
                return false;
            }
            TypeDefinition definition = lookup(a, local, meta);
            if (definition == null) {
                return false;
            }
            if (definition.supertypes().contains(b.type().name())) {
                return true;
            }
            TypeDefinition other = lookup(b, local, meta);
            return other != null && a.type().name().equals(b.type().name())
                    && definition.body().equals(other.body());
        }

        private static TypeDefinition lookup(Use use, Function<String, TypeDefinition> local,
                                             Function<String, TypeDefinition> meta) {
            String name = use.type().name();
            TypeDefinition first = (use.local() ? local : meta).apply(name);
            return first != null ? first : (use.local() ? meta : local).apply(name);
        }
    }

    /** A type-ref as the schema spells it -- {@code text}, {@code box<int32, 3>} -- for a message. */
    static String spell(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return ref.name();
        }
        List<String> arguments = new ArrayList<>();
        for (TypeArgument argument : ref.arguments()) {
            arguments.add(switch (argument) {
                case TypeArgument.Ref nested -> spell(nested.ref());
                case TypeArgument.Value value -> value.value().text();
            });
        }
        return ref.name() + "<" + String.join(", ", arguments) + ">";
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
