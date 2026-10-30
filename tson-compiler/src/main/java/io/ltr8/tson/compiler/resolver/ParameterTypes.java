package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ReferenceValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;

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
 * parameter is a {@code TYPE} parameter exactly when that type is {@code type_kind}.
 *
 * <p><b>The slot's declared type is what says which.</b> A held body is the constructor application as written,
 * so the body alone cannot tell a parameter naming a type from one standing for an enum member -- both are bare
 * tokens. What separates them is the constructor's own vocabulary: {@code array.element_type} is typed
 * {@code type_kind}, {@code array.min_items} {@code non_negative_integer}, {@code enum.members} a set of
 * {@code text}. §4.2 makes that reading general rather than a table of kernel names -- every slot that names the
 * type of a value is typed {@code type_kind}, its value a name or an application -- so an extension
 * meta-schema's own constructors classify by the same walk with nothing added here.
 *
 * <p><b>Four sources of a use type</b>, and the last is why this is not one pass over one declaration:
 *
 * <ul>
 *   <li>a slot typed {@code type_kind} gives a type use;</li>
 *   <li>a slot whose type resolves to an {@link Atom} gives that declared type;</li>
 *   <li>a <b>routed default or fixed value</b> -- a parameter at {@code record_field.value} -- gives the field's
 *   own declared type, read from the sibling {@code type} slot, and not {@code record_field.value}'s own type
 *   {@code value}, which says nothing. Where that field is typed by a parameter, the use type names the
 *   parameter ({@code <T, N> { w?: T ~ N }} gives {@code N} the type {@code T});</li>
 *   <li>a parameter riding another template's argument list, where a parameter of <em>either</em> kind is its
 *   name, as any argument is a reference or a token and typed where it lands. That occurrence says nothing on
 *   its own: the type comes from the callee's parameter at that position, so it is recorded as a dependency and
 *   settled by a fixed point over the whole open-entry set.</li>
 * </ul>
 *
 * <p>Anything else -- a parameter standing where a record, a collection or a choice is declared, as in
 * {@code <T> !enum { members: T }} -- is refused here. §5.10 confines value parameters to scalars and type
 * parameters to references, so a parameter standing for a whole {@code enum_set} is neither, and refusing it
 * at the declaration is what turns "every application of this fails" into "this template is wrong".
 *
 * <p><b>A record template admits a parameter in three places only</b> ([TSON-SCHEMA] §5.10): a field's type, a
 * field's default or fixed value, and an argument of an application -- a field type's or a parent's. Anywhere
 * else in a {@code !record} body -- a field name, a group member, a field's {@code optional}, {@code voidable} or
 * {@code role}, the record's {@code extension}, {@code name_type} or {@code discriminators}, a bare parent -- is
 * refused here, because each would leave the family base without a shape its applications share: the base
 * cannot list a field whose name is an argument, or say whether a field is optional when that is one.
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
 * bound and must IS-A any bound the uses carry. One name denotes one entry in the schema's namespace, so {@code
 * <S: text>} over a slot typed by the kernel's {@code text} is the narrowing it reads as.
 *
 * <p><b>An imported template is taken as recorded</b>, not walked again: its schema resolved it, and its recorded
 * parameters carry what its author wrote, which its held body does not.
 *
 * <p><b>A parameter the fixed point leaves ungrounded is a type parameter, and that is forced</b> -- being a
 * value parameter <em>means</em> standing in a scalar slot, and a slot is what grounds a parameter. [TSON-SCHEMA]
 * §5.10 states the rule.
 */
final class ParameterTypes {

    /** The kind every type slot is typed by, {@code kind<type>}. */
    private static final String TYPE_KIND = TemplateParam.TYPE_KIND.name();

    /** Whether a slot declared {@code name} holds a type: the kind of the types, a name or an application. */
    private static boolean typePosition(String name) {
        return TYPE_KIND.equals(name);
    }

    /** The kernel record whose {@code value} slot routes a default or fixed value into its sibling's type. */
    private static final String RECORD_FIELD = "record_field";

    /** The constructor a record template's payload applies. */
    private static final String RECORD = "record";

    /** The one slot of a record template a parameter may stand in as a whole token: a field's type. */
    private static final String FIELD_TYPE = RECORD_FIELD + "." + WireForm.TYPE;

    /** What a parameter's type makes it (§5.10). */
    enum Kind {
        TYPE, VALUE;

        static Kind of(TypeRef type) {
            return typePosition(type.name()) ? TYPE : VALUE;
        }
    }

    /** [TSON-SCHEMA] §4.1's root: an entry IS-A it is a constructor. */
    private static final String TOP = "top";

    private ParameterTypes() {
    }

    /**
     * Whether {@code template}'s value parameters are typed by the applied constructor's slots, read and checked among
     * the schema's imports (§5.10): a template whose body applies a constructor other than {@code record} -- {@code vec
     * => <N> !array { element_type: text  min_items: N }} -- binds its values into the constructor's own structure, so
     * {@code N} is the kernel's {@code non_negative_integer}, and a written narrowing names an imported type too, the
     * constructor's reader being the one that reads the argument. A record template's fields are typed by the schema,
     * so its parameters read in the schema's namespace. A template whose body applies another template takes that
     * template's form. A type parameter's bound is the schema's either way: its argument names a schema type, the way
     * {@code set<T>} captures a local name into the meta's {@code element_type}.
     */
    static boolean readsInStructure(Top template, Function<String, Top> entries) {
        Top current = template;
        for (int hop = 0; hop < 64 && OpenEntry.isOpen(current); hop++) {
            String head = HeldBody.of(current).application().typeRef().orElse(null);
            if (head == null || head.equals("record")) {
                return false;
            }
            Top applied = entries.apply(head);
            if (!OpenEntry.isOpen(applied)) {
                return true;
            }
            current = applied;
        }
        return false;
    }

    /**
     * Every local open entry's parameters, by entry name then parameter name.
     *
     * <p>{@code entries} is the <b>whole namespace</b>, imports included, because a local template may route a
     * parameter into an imported one and take its type from there. {@code local} is the subset this schema
     * produced: those are walked, and the only names a failure is reported against, since an imported entry
     * resolved in its own schema. {@code written} is what each local declaration's parameter list wrote.
     */
    static Map<String, Map<String, TemplateParam>> inferAll(Map<String, Top> entries, Set<String> local,
                                                             Map<String, Map<String, TypeRef>> written,
                                                             Function<String, Top> meta,
                                                             FailureReporter reporter) {
        Map<String, Occurrences> observed = new LinkedHashMap<>();
        Map<String, Map<String, TemplateParam>> recorded = new LinkedHashMap<>();
        entries.forEach((name, definition) -> {
            if (!OpenEntry.isOpen(definition)) {
                return;
            }
            if (!local.contains(name)) {
                Map<String, TemplateParam> params = new LinkedHashMap<>();
                OpenEntry.parameters(definition).forEach(p -> params.put(p.name(), p));
                recorded.put(name, params);
                return;
            }
            Occurrences occurrences = new Occurrences(OpenEntry.parameterNames(definition),
                    written.getOrDefault(name, Map.of()),
                    readsInStructure(definition, entries::get));
            try {
                Walk.over(HeldBody.of(definition), occurrences, meta);
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
    static Map<String, Kind> inferOne(Top template, Function<String, Top> meta) {
        if (!OpenEntry.isOpen(template)) {
            return Map.of();
        }
        Occurrences occurrences = new Occurrences(OpenEntry.parameterNames(template), Map.of(), false);
        try {
            Walk.over(HeldBody.of(template), occurrences, meta);
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

    /**
     * The walk's own refusals, thrown, over a held payload not yet bound: a record template's parameters are
     * confined to its field types and values before {@code record}'s reader is asked to bind them, so a parameter
     * in a scalar slot is refused for where it stands ([TSON-SCHEMA] §5.10) rather than for the token it is.
     */
    static void checkSlots(List<String> parameters, DataValue payload, Function<String, Top> meta) {
        Walk.over(HeldBody.ofTree(parameters, payload), new Occurrences(parameters, Map.of(), false), meta);
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
            Map<String, Map<String, TemplateParam>> recorded, Map<String, Top> entries,
            FailureReporter reporter, Function<String, Top> local, Function<String, Top> meta) {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Occurrences occurrences : observed.values()) {
                for (Deferred deferred : occurrences.deferred) {
                    if (occurrences.settled.contains(deferred)) {
                        continue;
                    }
                    Top callee = entries.get(deferred.head());
                    if (!OpenEntry.isOpen(callee)) {
                        continue;
                    }
                    List<String> calleeParameters = OpenEntry.parameterNames(callee);
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
                    // A value type is read where the callee reads it: the meta's for a constructor template.
                    boolean schemaSide = known.isTypeParameter() ? !recorded.containsKey(deferred.head())
                            : !readsInStructure(callee, entries::get);
                    occurrences.observe(deferred.parameter(), new Use(known.type(), known.bound(), schemaSide));
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
        private final boolean structural;
        private final Map<String, List<Use>> uses = new LinkedHashMap<>();
        private final List<Deferred> deferred = new ArrayList<>();
        private final Set<Deferred> settled = new LinkedHashSet<>();

        Occurrences(List<String> parameters, Map<String, TypeRef> written, boolean structural) {
            this.parameters = parameters;
            this.written = written;
            this.structural = structural;
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
        TemplateParam current(String parameter, Function<String, Top> local,
                              Function<String, Top> meta) {
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
        Map<String, TemplateParam> parameters(Function<String, Top> local,
                                              Function<String, Top> meta) {
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
        private TemplateParam parameter(String parameter, Function<String, Top> local,
                                        Function<String, Top> meta) {
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
                    requireType(declared.get(), local, meta);
                    Use mine = new Use(declared.get(), true);
                    if (inherited.isPresent() && !isA(mine, inherited.get(), local, meta)) {
                        throw new SchemaValidationException("parameter '" + parameter + "' is declared '"
                                + parameter + ": " + spell(declared.get()) + "', and a template it is passed to "
                                + "needs a type that IS-A " + spell(inherited.get().type()) + ", which "
                                + spell(declared.get()) + " is not -- a written bound narrows what the uses "
                                + "require, and never widens it");
                    }
                    return new TemplateParam(parameter, TemplateParam.TYPE_KIND, declared);
                }
                return new TemplateParam(parameter, TemplateParam.TYPE_KIND, inherited.map(Use::type));
            }
            Use derived = narrowest(parameter, "read as", all, local, meta);
            if (declared.isPresent()) {
                if (structural && meta.apply(declared.get().name()) == null) {
                    throw new SchemaValidationException("parameter '" + parameter + "' is declared '" + parameter
                            + ": " + spell(declared.get()) + "', and this template applies a constructor, whose "
                            + "reader reads its value parameters, so a written type names a type this schema "
                            + "imports -- '" + declared.get().name() + "' is not one (§5.10)");
                }
                Use mine = new Use(declared.get(), !structural);
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

        /**
         * A written bound names a type, so its name -- an application's head, where it is one -- resolves in the
         * schema's namespace and names no constructor: a type argument names a type, which IS-A no constructor
         * (§4.1), so a bound naming one would admit no argument.
         */
        private static void requireType(TypeRef bound, Function<String, Top> local,
                                        Function<String, Top> meta) {
            String name = bound.name();
            Top named = local.apply(name);
            if (named == null) {
                throw new SchemaValidationException("the bound '" + spell(bound) + "': '" + name + "' names no "
                        + "type in this schema or its imports (§5.10)");
            }
            if (TOP.equals(name) || TypeChain.supertypesOf(named).contains(TOP)) {
                throw new SchemaValidationException("the bound '" + spell(bound) + "' names '" + name + "', and '"
                        + name + "' is a constructor, not a type (§4.1); a bound names a type, which every argument "
                        + "must IS-A (§5.10)");
            }
        }

        /** The use every other use is a supertype of, or a resolver error where no such use exists. */
        private static Use narrowest(String parameter, String verb, List<Use> uses,
                                     Function<String, Top> local, Function<String, Top> meta) {
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
         * Whether {@code a}'s type IS-A {@code b}'s: equal, or {@code b} among the supertypes {@code a} records.
         */
        static boolean isA(Use a, Use b, Function<String, Top> local, Function<String, Top> meta) {
            if (a.type().equals(b.type())) {
                return true;
            }
            if (!a.type().arguments().isEmpty() || !b.type().arguments().isEmpty()) {
                return false;
            }
            Top definition = lookup(a, local, meta);
            if (definition == null) {
                return false;
            }
            return TypeChain.supertypesOf(definition).contains(b.type().name());
        }

        private static Top lookup(Use use, Function<String, Top> local,
                                             Function<String, Top> meta) {
            String name = use.type().name();
            Top first = (use.local() ? local : meta).apply(name);
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

    /**
     * One held body walked against the vocabulary of the constructor it applies. {@code recordTemplate} says the
     * body applies {@code record}, whose parameters are confined to a field's type and value and to an
     * application's arguments; every walk below carries the slot it is in, as {@code constructor.field}, so a
     * refusal can name it.
     */
    private record Walk(Occurrences occurrences, Function<String, Top> meta, boolean recordTemplate) {

        static void over(HeldBody held, Occurrences occurrences, Function<String, Top> meta) {
            String head = held.application().typeRef().orElse(null);
            Walk walk = new Walk(occurrences, meta, RECORD.equals(head));
            if (head != null && walk.resolve(head) instanceof RecordBody vocabulary) {
                walk.payload(held.application().coreValue(), head, vocabulary);
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
            List<Map.Entry<String, RecordField>> unmarked = vocabulary.fields().entrySet().stream()
                    .filter(f -> !f.getValue().optional()).toList();
            if (unmarked.size() == 1) {
                value(payload, unmarked.getFirst().getValue().type(),
                        constructor + "." + unmarked.getFirst().getKey());
            }
        }

        /** Each written slot walked against the field the constructor declares for it. */
        private void record(RecordValue wire, String constructor, RecordBody vocabulary) {
            for (RecordValue.Field written : wire.fields()) {
                CoreValue value = written.value().coreValue();
                if (RECORD_FIELD.equals(constructor) && WireForm.VALUE.equals(written.name())
                        && value instanceof TokenValue token && occurrences.declares(token.text())) {
                    routed(token.text(), wire);
                    continue;
                }
                Optional.ofNullable(vocabulary.fields().get(written.name()))
                        .ifPresent(field -> value(value, field.type(), constructor + "." + written.name()));
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
                case ReferenceValue applied -> applied.reference();
                case TokenValue token when token.form() == TokenForm.UNQUOTED -> TypeRef.of(token.text());
                case null, default -> null;
            };
            if (type == null) {
                return; // no type to route into: record_field's own reader refuses the field
            }
            occurrences.observe(parameter, type, true);
        }

        /** One written value at {@code slot}, against the type the slot declares. */
        private void value(CoreValue written, TypeRef declared, String slot) {
            Top type = resolve(declared.name());
            switch (written) {
                case TokenValue token when occurrences.declares(token.text()) ->
                        token(token.text(), declared, type, slot);
                case ArrayValue array -> elements(array, type, slot);
                case MapValue map when type instanceof MapBody entries -> entries(map, entries, slot);
                case ReferenceValue applied when typePosition(declared.name()) -> application(applied.reference());
                case RecordValue record when type instanceof RecordBody nested ->
                        record(record, declared.name(), nested);
                default -> {
                }
            }
        }

        /**
         * A map slot: the key against {@code key_type}, the value against {@code value_type}. Both halves, because
         * a parameter reaches either -- one in a key, and another inside the array the value names.
         */
        private void entries(MapValue map, MapBody declared, String slot) {
            for (MapValue.MapEntry entry : map.entries()) {
                value(entry.key().coreValue(), declared.keyType(), "a key of " + slot);
                value(entry.value().coreValue(), declared.valueType(), slot);
            }
        }

        /** A parameter standing at a slot: the slot's declared type is the whole of the verdict. */
        private void token(String parameter, TypeRef declared, Top type, String slot) {
            if (!typePosition(declared.name()) && !(type instanceof Atom)) {
                throw new SchemaValidationException("parameter '" + parameter + "' stands where '" + declared.name()
                        + "' is declared, which is neither a kind of type nor a scalar -- §5.10 binds a value "
                        + "parameter to scalars only and a type parameter to type names, so nothing could be "
                        + "applied here");
            }
            if (recordTemplate && !FIELD_TYPE.equals(slot)) {
                throw new SchemaValidationException("parameter '" + parameter + "' stands at " + slot
                        + " -- a record template's parameters stand only in a field's type, a field's default or "
                        + "fixed value, or an argument of an application, so that every application of it shares "
                        + "one shape (§5.10)");
            }
            occurrences.observe(parameter, declared, false);
        }

        private void elements(ArrayValue array, Top type, String slot) {
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
                value(array.elements().get(i).coreValue(), declared, slot);
            }
        }

        /**
         * A type slot holding an application rather than a bare name, read through {@link WireForm#applicationIn}:
         * what an application looks like on the wire is one class's answer. Each argument that names a parameter of
         * this declaration is <b>deferred</b>: an argument is a reference or a token, typed where it lands, so its
         * type is the head's parameter's at that position. A literal argument says nothing about this declaration's
         * parameters, and a nested application ({@code box<inner<T>>}) is walked in turn.
         */
        private void application(TypeRef application) {
            for (int i = 0; i < application.arguments().size(); i++) {
                if (application.arguments().get(i) instanceof TypeArgument.Ref ref) {
                    if (ref.ref().arguments().isEmpty() && occurrences.declares(ref.ref().name())) {
                        occurrences.deferred.add(new Deferred(ref.ref().name(), application.name(), i));
                    } else {
                        application(ref.ref());
                    }
                }
            }
        }

        /**
         * A name's resolved body, alias chains followed -- the slot type a written value faces. A namespace type
         * faces it as the map its value is ({@link NamespaceBody#read}): {@code record}'s {@code fields} is written
         * as a map.
         */
        private Top resolve(String name) {
            Top definition = meta.apply(name);
            for (int hops = 0; definition instanceof Alias alias && hops < 32; hops++) {
                definition = meta.apply(alias.target().name());
            }
            if (definition instanceof NamespaceBody namespace) {
                return namespace.read();
            }
            return definition;
        }
    }
}
