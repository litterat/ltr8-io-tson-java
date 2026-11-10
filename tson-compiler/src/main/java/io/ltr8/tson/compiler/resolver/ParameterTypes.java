package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ReferenceValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordTemplateBody;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * [TSON-SCHEMA] §5.10's parameters: where each may stand, and the bound each carries.
 *
 * <p><b>A parameter is a type.</b> It stands only where a type is named -- a slot whose declared type is a reference
 * naming types ({@code meta.type_ref}, or a refinement of it such as {@code meta.identifier_ref}), which in a record
 * template is a field's type -- or as an argument of an application there or in a parent. A parameter anywhere else
 * is refused at the declaration, naming the slot ({@link #at}): a facet ({@code min_items: N}), a field's default or
 * fixed value ({@link #atValue}), an enum member, a size bound, a field name, a group member, a field's {@code
 * optional}, {@code voidable} or {@code role}, the record's {@code extension}, {@code name_type} or {@code
 * discriminators}. Each would leave the template without one shape its applications share, and a field's value in
 * particular is never a parameter, whatever the field's type: a pin on a slot typed {@code meta.type_ref}
 * ({@code <T> meta.array ^ { element_type?: = T }}) is refused with every other parameter at a value.
 *
 * <p><b>The slot's declared type is what says where a type is named.</b> A held body is the constructor application
 * as written, so the body alone cannot tell {@code element_type: T} from {@code min_items: N} -- both are bare
 * tokens. The constructor's own vocabulary does: {@code array.element_type} is typed {@code meta.type_ref}, {@code
 * array.min_items} {@code numeric.count}. §4.2 makes that reading general rather than a table of kernel names, so an
 * extension meta-schema's own constructors are judged by the same walk with nothing added here.
 *
 * <p><b>A bound is written or inherited.</b> A declaration writes one after the name ({@code <T: text>}), which must
 * name a type; a parameter passed to another template inherits that template's bound for the position, so an
 * unannotated parameter is bounded as narrowly as its uses require. Uses carrying bounds unordered by IS-A are
 * refused, and a written bound must IS-A every bound inherited. A parameter riding another template's argument list
 * takes its bound from the callee, which may itself be waiting on this one, so the bounds are settled by a fixed point
 * over the whole open-entry set; what the fixed point leaves ungrounded is unbounded.
 *
 * <p><b>An imported template is taken as recorded</b>, not walked again: its schema resolved it, and its recorded
 * parameters carry what its author wrote, which its held body does not.
 */
final class ParameterTypes {

    /** The kind of the types, {@code meta.ref<type>}: what a slot naming a type is declared as. */
    private static final String TYPE_REF = KernelNames.of("type_ref");

    /** The kernel record whose {@code type} and {@code value} slots a record template's field binds. */
    private static final String RECORD_FIELD = "meta.record_field";

    /** The constructor a record template's payload applies. */
    private static final String RECORD = KernelNames.of("record");

    /** The one slot of a record template a parameter may stand in as a whole token: a field's type. */
    private static final String FIELD_TYPE = RECORD_FIELD + "." + WireForm.TYPE;

    /** A field's value, which is never a parameter. */
    private static final String FIELD_VALUE = RECORD_FIELD + "." + WireForm.VALUE;

    /** [TSON-SCHEMA] §4.1's root: an entry IS-A it is a constructor. */
    private static final String TOP = KernelNames.of("top");

    private ParameterTypes() {
    }

    /** The one refusal: {@code parameter} standing at {@code slot}, which names no type ([TSON-SCHEMA] §5.10). */
    static SchemaValidationException at(String parameter, String slot) {
        return new SchemaValidationException("parameter '" + parameter + "' stands at " + slot + " -- a parameter "
                + "is a type, and stands only at a field's type or as an argument of an application there or in a "
                + "parent (§5.10)");
    }

    /**
     * {@link #at} for a field's default or fixed value: a field's value is never a parameter, whatever the field's
     * type. A value that differs per application is written where the application is declared.
     */
    static SchemaValidationException atValue(String parameter, String field) {
        return new SchemaValidationException("parameter '" + parameter + "' stands at the value of field '" + field
                + "' (" + FIELD_VALUE + ") -- a parameter is a type, and stands only at a field's type or as an "
                + "argument of an application there or in a parent, so a field's value is never one (§5.10). State "
                + "the value where the application is declared: 'name => template<argument> & { " + field
                + "?: = value }'");
    }

    /**
     * Every local open entry's parameters, by entry name then parameter name, each with its written or inherited
     * bound, after checking where each stands.
     *
     * <p>{@code entries} is the <b>whole namespace</b>, imports included, because a local template may pass a
     * parameter to an imported one and inherit its bound from there. {@code local} is the subset this schema
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
                    written.getOrDefault(name, Map.of()));
            try {
                walk(definition, occurrences, meta);
            } catch (SchemaValidationException e) {
                reporter.report(name, e);
                return;
            }
            observed.put(name, occurrences);
        });
        return settle(observed, recorded, entries, reporter, entries::get, meta);
    }

    /**
     * The walk's own refusals, thrown, over a record template's payload not yet bound: its parameters are confined
     * to its field types before {@code record_template}'s reader is asked to bind them, so a parameter at a value or
     * a scalar slot is refused for where it stands ([TSON-SCHEMA] §5.10) rather than for the token it is. The payload
     * is walked as the {@code record} construction the author wrote, so each slot is named as {@code record} and
     * {@code record_field} name it; a field's {@code type_param} is where a parameter belongs, and the parameter list
     * the desugarer stated is no slot of {@code record}'s.
     */
    static void checkSlots(List<String> parameters, DataValue payload, Function<String, Top> meta) {
        Walk walk = new Walk(new Occurrences(parameters, Map.of()), meta, true);
        if (walk.resolve(RECORD) instanceof RecordBody vocabulary) {
            walk.payload(payload.coreValue(), RECORD, vocabulary);
        }
    }

    /**
     * One open entry's occurrences: a held template's text walked against its constructor's vocabulary ({@link
     * Walk}), or a record template's references read off the model, where a parameter as an argument of an
     * application in a field's type or a parent takes the head's parameter's bound at that position. A {@code
     * type_param} carries no bound of its own.
     */
    private static void walk(Top open, Occurrences occurrences, Function<String, Top> meta) {
        if (!(open instanceof RecordTemplateBody template)) {
            Walk.over(HeldBody.of((TemplateBody) open), occurrences, meta);
            return;
        }
        Walk walk = new Walk(occurrences, meta, true);
        template.references().forEach(walk::application);
    }

    /** Where a declaration whose parameters stand where none may is reported, entry by entry. */
    @FunctionalInterface
    interface FailureReporter {
        void report(String entryName, SchemaValidationException error);
    }

    // ── The fixed point ──────────────────────────────────────────────────────────────────────────

    /**
     * Deferred occurrences resolved against the bounds already known, until nothing moves. A parameter riding
     * {@code box<T>}'s argument list inherits {@code box}'s own parameter's bound at that position, and {@code box}
     * may itself be waiting on this one; §5.10 anticipates the cycle, and this pass leaves such a parameter
     * unbounded.
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
                    known.bound().ifPresent(bound -> occurrences.observe(deferred.parameter(),
                            new Use(bound, !recorded.containsKey(deferred.head()))));
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

    /** One parameter riding another template's argument list, whose bound that template's parameter gives. */
    private record Deferred(String parameter, String head, int index) {
    }

    /**
     * One inherited bound, and the namespace its name resolves in first: a local template's bound is the schema's,
     * an imported one's its own schema's, reached among the imports.
     */
    private record Use(TypeRef bound, boolean local) {
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

        void observe(String parameter, Use use) {
            uses.computeIfAbsent(parameter, ignored -> new ArrayList<>()).add(use);
        }

        /**
         * One parameter as far as the fixed point has got: settled where nothing it inherits from is still waiting
         * -- every deferred occurrence of it settled -- and {@code null} until then.
         */
        TemplateParam current(String parameter, Function<String, Top> local, Function<String, Top> meta) {
            boolean waiting = deferred.stream()
                    .anyMatch(each -> each.parameter().equals(parameter) && !settled.contains(each));
            if (waiting && !written.containsKey(parameter)) {
                return null;
            }
            try {
                return parameter(parameter, local, meta);
            } catch (SchemaValidationException e) {
                return null; // reported against this declaration when it settles
            }
        }

        /** Every parameter, settled. */
        Map<String, TemplateParam> parameters(Function<String, Top> local, Function<String, Top> meta) {
            Map<String, TemplateParam> result = new LinkedHashMap<>();
            for (String parameter : parameters) {
                result.put(parameter, parameter(parameter, local, meta));
            }
            return result;
        }

        /**
         * One parameter: the narrowest of the bounds its uses inherit, then what the declaration wrote, which must
         * narrow that further. With nothing inherited and nothing written it is unbounded.
         */
        private TemplateParam parameter(String parameter, Function<String, Top> local, Function<String, Top> meta) {
            List<Use> bounds = uses.getOrDefault(parameter, List.of());
            Optional<Use> inherited = bounds.isEmpty() ? Optional.empty()
                    : Optional.of(narrowest(parameter, bounds, local, meta));
            Optional<TypeRef> declared = Optional.ofNullable(written.get(parameter));
            if (declared.isEmpty()) {
                return new TemplateParam(parameter, inherited.map(Use::bound));
            }
            requireType(declared.get(), local);
            Use mine = new Use(declared.get(), true);
            if (inherited.isPresent() && !isA(mine, inherited.get(), local, meta)) {
                throw new SchemaValidationException("parameter '" + parameter + "' is declared '" + parameter + ": "
                        + spell(declared.get()) + "', and a template it is passed to needs a type that IS-A "
                        + spell(inherited.get().bound()) + ", which " + spell(declared.get()) + " is not -- a "
                        + "written bound narrows what the uses require, and never widens it");
            }
            return new TemplateParam(parameter, declared);
        }

        /**
         * A written bound names a type, so its name -- an application's head, where it is one -- resolves in the
         * schema's namespace and names no constructor: a type argument names a type, which IS-A no constructor
         * (§4.1), so a bound naming one would admit no argument.
         */
        private static void requireType(TypeRef bound, Function<String, Top> local) {
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

        /** The bound every other bound is a supertype of, or a resolver error where no such bound exists. */
        private static Use narrowest(String parameter, List<Use> uses, Function<String, Top> local,
                                     Function<String, Top> meta) {
            for (Use candidate : uses) {
                if (uses.stream().allMatch(other -> isA(candidate, other, local, meta))) {
                    return candidate;
                }
            }
            Set<String> named = new LinkedHashSet<>();
            uses.forEach(use -> named.add(spell(use.bound())));
            throw new SchemaValidationException("parameter '" + parameter + "' is bounded by "
                    + String.join(" and ", named) + " by the templates it is passed to, and none of them IS-A the "
                    + "others, so no argument can satisfy them all (§5.10)");
        }

        /** Whether {@code a}'s bound IS-A {@code b}'s: equal, or {@code b} among the supertypes {@code a} records. */
        static boolean isA(Use a, Use b, Function<String, Top> local, Function<String, Top> meta) {
            if (a.bound().equals(b.bound())) {
                return true;
            }
            if (!a.bound().arguments().isEmpty() || !b.bound().arguments().isEmpty()) {
                return false;
            }
            // An alias is a hop and no second type (§8.3): `text` over `text => text` IS-A what `text` is.
            String aTerminal = terminal(a, local, meta);
            String bTerminal = terminal(b, local, meta);
            if (aTerminal.equals(bTerminal)) {
                return true;
            }
            Top definition = lookup(aTerminal, a.local(), local, meta);
            if (definition == null) {
                return false;
            }
            return TypeChain.supertypesOf(definition).contains(bTerminal)
                    || TypeChain.supertypesOf(definition).contains(b.bound().name());
        }

        /** {@code use}'s name past its alias hops, looked up as {@link #lookup} looks it up. */
        private static String terminal(Use use, Function<String, Top> local, Function<String, Top> meta) {
            return AliasChain.terminal(use.bound().name(), name -> lookup(name, use.local(), local, meta));
        }

        private static Top lookup(String name, boolean preferLocal, Function<String, Top> local,
                                  Function<String, Top> meta) {
            Top first = (preferLocal ? local : meta).apply(name);
            return first != null ? first : (preferLocal ? meta : local).apply(name);
        }
    }

    /** A type-ref as the schema spells it -- {@code text}, {@code box<int32, text>} -- for a message. */
    static String spell(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return ref.name();
        }
        List<String> arguments = new ArrayList<>();
        for (TypeRef argument : ref.arguments()) {
            arguments.add(spell(argument));
        }
        return ref.name() + "<" + String.join(", ", arguments) + ">";
    }

    // ── The walk ─────────────────────────────────────────────────────────────────────────────────

    /**
     * One held body walked against the vocabulary of the constructor it applies. {@code recordTemplate} says the
     * body applies {@code record}, whose one whole-token parameter slot is a field's type; every walk below carries
     * the slot it is in, as {@code constructor.field}, so a refusal can name it.
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
                unmarked.getFirst().getValue().namedType().ifPresent(type -> value(payload, type,
                        constructor + "." + unmarked.getFirst().getKey()));
            }
        }

        /**
         * Each written slot walked against the field the constructor declares for it. A record field's {@code
         * value} is typed by its sibling, so it declares no type to walk against; a parameter there is refused by
         * name, a field's value never being one.
         */
        private void record(RecordValue wire, String constructor, RecordBody vocabulary) {
            for (RecordValue.Field written : wire.fields()) {
                CoreValue value = written.value().coreValue();
                if (RECORD_FIELD.equals(constructor) && WireForm.VALUE.equals(written.name())
                        && value instanceof TokenValue token && occurrences.declares(token.text())) {
                    throw at(token.text(), FIELD_VALUE);
                }
                Optional.ofNullable(vocabulary.fields().get(written.name())).flatMap(RecordField::namedType)
                        .ifPresent(type -> value(value, type, constructor + "." + written.name()));
            }
        }

        /** One written value at {@code slot}, against the type the slot declares. */
        private void value(CoreValue written, TypeRef declared, String slot) {
            Top type = resolve(declared.name());
            switch (written) {
                case TokenValue token when occurrences.declares(token.text()) ->
                        token(token.text(), declared, type, slot);
                case ArrayValue array -> elements(array, type, slot);
                case MapValue map when type instanceof MapBody entries -> entries(map, entries, slot);
                case ReferenceValue(TypeRef applied) when namesTypes(declared, type) -> application(applied);
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

        /**
         * A parameter standing at a slot: admitted only where the slot names a type -- in a record template, only at a
         * field's type -- and refused naming the slot anywhere else.
         */
        private void token(String parameter, TypeRef declared, Top type, String slot) {
            boolean admitted = recordTemplate ? FIELD_TYPE.equals(slot) : namesTypes(declared, type);
            if (!admitted) {
                throw at(parameter, slot);
            }
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
         * An application, at a slot naming a type or in a record template's field type or parent. Each argument
         * that names a parameter of this declaration is <b>deferred</b>: it inherits the head's parameter's bound at
         * that position. A nested application ({@code box<inner<T>>}) is walked in turn.
         */
        private void application(TypeRef application) {
            for (int i = 0; i < application.arguments().size(); i++) {
                TypeRef argument = application.arguments().get(i);
                if (argument.arguments().isEmpty() && occurrences.declares(argument.name())) {
                    occurrences.deferred.add(new Deferred(argument.name(), application.name(), i));
                } else {
                    application(argument);
                }
            }
        }

        /**
         * Whether a slot declared {@code declared}, resolving to {@code type}, names a type: the kind of the types
         * itself, or a reference type refining it, such as {@code meta.identifier_ref}. A reference naming values
         * ({@code meta.ref<method>}) names no type.
         */
        private static boolean namesTypes(TypeRef declared, Top type) {
            return TYPE_REF.equals(declared.name())
                    || type instanceof ReferenceBody && TypeChain.supertypesOf(type).contains(TYPE_REF);
        }

        /**
         * A name's resolved body, alias chains followed -- the slot type a written value faces. A namespace type
         * faces it as the map its value is ({@link NamespaceBody#read}).
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
