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
import java.util.function.Predicate;

/**
 * [TSON-SCHEMA] §5.10's parameters: where each may stand, and the bound each is written with.
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
 * <p><b>A bound is written.</b> A declaration writes one after the name ({@code <T: text>}), which must name a type;
 * otherwise the parameter is unbounded. Nothing about a parameter is inferred from its uses. A parameter passed as an
 * argument to a bounded parameter of another template must itself be written with a bound that IS-A that one, so
 * {@code rebox => <T> { b: boxed<T> }} over {@code boxed => <T: text> { v: T }} is refused at {@code rebox}. Each
 * declaration is checked once, against the bounds its callees wrote, so no order among templates is needed. A
 * constructor's slot judges the argument that arrives when the record is applied, so a parameter passed to one
 * ({@code meta.namespace<K text>}) needs no bound.
 *
 * <p><b>A lifted form is looked through.</b> Its own parameters are the lift's, which no author wrote and which carry
 * no bound, so a parameter reaching {@code boxed} inside {@code [boxed<T>]} is judged at the declaration that wrote it,
 * against {@code boxed}'s bound, and the lifted form is never a callee.
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
     * Checks every local open entry's parameters at its declaration, and returns the entries refused: where each
     * parameter stands, that each written bound names a type, and that each parameter passed to a bounded parameter
     * is written with a bound that IS-A it.
     *
     * <p>{@code entries} is the <b>whole namespace</b>, imports included, because a local template may pass a
     * parameter to an imported one, whose recorded bounds are what its author wrote. {@code local} is the subset this
     * schema produced: those are checked, and the only names a failure is reported against, since an imported entry
     * was checked in its own schema. {@code synthetic} says which open entries are lifted forms, looked through
     * rather than judged as callees. {@code meta} is where a constructor's vocabulary is read.
     */
    static Set<String> check(Map<String, Top> entries, Set<String> local, Predicate<String> synthetic,
                             Function<String, Top> meta, FailureReporter reporter) {
        Set<String> refused = new LinkedHashSet<>();
        Map<String, List<TypeRef>> written = new LinkedHashMap<>();
        entries.forEach((name, definition) -> {
            if (!OpenEntry.isOpen(definition) || !local.contains(name)) {
                return;
            }
            try {
                written.put(name, applications(definition, meta));
                for (TemplateParam parameter : OpenEntry.parameters(definition)) {
                    parameter.bound().ifPresent(bound -> requireType(bound, entries::get));
                }
            } catch (SchemaValidationException e) {
                refused.add(name);
                reporter.report(name, e);
            }
        });
        Callees callees = new Callees(entries, local, synthetic, written, Set.copyOf(refused), meta);
        written.forEach((name, applications) -> {
            if (synthetic.test(name)) {
                return; // its parameters are the lift's, judged at the declaration that wrote the form
            }
            Map<String, TemplateParam> mine = new LinkedHashMap<>();
            OpenEntry.parameters(entries.get(name)).forEach(p -> mine.put(p.name(), p));
            try {
                for (TypeRef application : applications) {
                    callees.passedOn(application, mine::get, new LinkedHashSet<>());
                }
            } catch (SchemaValidationException e) {
                refused.add(name);
                reporter.report(name, e);
            }
        });
        return refused;
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
        Walk walk = new Walk(parameters, meta, true, new ArrayList<>());
        if (walk.resolve(RECORD) instanceof RecordBody vocabulary) {
            walk.payload(payload.coreValue(), RECORD, vocabulary);
        }
    }

    /**
     * One open entry's applications, once where each of its parameters stands is checked: a held template's text
     * walked against its constructor's vocabulary ({@link Walk}), which yields the applications written at a slot
     * naming a type, or a record template's references read off the model, whose labels need no check.
     */
    private static List<TypeRef> applications(Top open, Function<String, Top> meta) {
        if (open instanceof RecordTemplateBody template) {
            return template.references().stream().filter(reference -> !reference.arguments().isEmpty()).toList();
        }
        List<TypeRef> found = new ArrayList<>();
        Walk.over(HeldBody.of((TemplateBody) open), OpenEntry.parameterNames(open), meta, found);
        return found;
    }

    /** Where a declaration whose parameters are refused is reported, entry by entry. */
    @FunctionalInterface
    interface FailureReporter {
        void report(String entryName, SchemaValidationException error);
    }

    // ── A parameter passed on ────────────────────────────────────────────────────────────────────

    /**
     * The applications a declaration writes, judged against the bounds their heads wrote. {@code written} holds the
     * applications the first pass found in each local open entry; an imported lifted form is walked when reached. A
     * head already refused is not judged against: its verdict is in.
     */
    private record Callees(Map<String, Top> entries, Set<String> local, Predicate<String> synthetic,
                           Map<String, List<TypeRef>> written, Set<String> refused, Function<String, Top> meta) {

        /**
         * Each argument of {@code application} that is one of the declaration's parameters ({@code parameterOf}
         * maps a name to it, or to {@code null}), against the head's parameter at that position. A lifted head is
         * looked through, its own parameters mapped to the arguments it was given, and a nested application is judged
         * in turn. {@code through} holds the lifted heads already looked through on this path.
         */
        void passedOn(TypeRef application, Function<String, TemplateParam> parameterOf, Set<String> through) {
            Top head = entries.get(application.name());
            List<TemplateParam> heads = OpenEntry.isOpen(head) && !refused.contains(application.name())
                    ? OpenEntry.parameters(head) : List.of();
            boolean lifted = synthetic.test(application.name());
            Map<String, TemplateParam> renamed = new LinkedHashMap<>();
            for (int i = 0; i < application.arguments().size(); i++) {
                TypeRef argument = application.arguments().get(i);
                TemplateParam mine = argument.arguments().isEmpty() ? parameterOf.apply(argument.name()) : null;
                if (mine == null) {
                    passedOn(argument, parameterOf, through);
                } else if (i < heads.size() && lifted) {
                    renamed.put(heads.get(i).name(), mine);
                } else if (i < heads.size()) {
                    TemplateParam theirs = heads.get(i);
                    theirs.bound().ifPresent(bound -> requireBound(mine, application.name(), theirs.name(), bound));
                }
            }
            if (!renamed.isEmpty() && through.add(application.name())) {
                List<TypeRef> inner = written.get(application.name());
                for (TypeRef nested : inner != null ? inner : applications(head, meta)) {
                    passedOn(nested, renamed::get, through);
                }
                through.remove(application.name());
            }
        }

        /** {@code mine}, passed to {@code callee}'s {@code theirs}, bounded by {@code bound}: written IS-A it. */
        private void requireBound(TemplateParam mine, String callee, String theirs, TypeRef bound) {
            String required = "'" + callee + "', which declares '" + theirs + ": " + spell(bound) + "'";
            if (mine.bound().isEmpty()) {
                throw new SchemaValidationException("parameter '" + mine.name() + "' is passed to " + required
                        + ", and is written with no bound -- a parameter passed to a bounded parameter is written "
                        + "with a bound that IS-A it, '<" + mine.name() + ": " + spell(bound) + ">' (§5.10)");
            }
            TypeRef own = mine.bound().get();
            if (!isA(new Use(own, true), new Use(bound, local.contains(callee)), entries::get, meta)) {
                throw new SchemaValidationException("parameter '" + mine.name() + "' is declared '" + mine.name()
                        + ": " + spell(own) + "' and passed to " + required + ", and " + spell(own)
                        + " is not a type that IS-A " + spell(bound) + " (§5.10)");
            }
        }
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

    /**
     * One bound, and the namespace its name resolves in first: a local template's bound is the schema's, an imported
     * one's its own schema's, reached among the imports.
     */
    private record Use(TypeRef bound, boolean local) {
    }

    /** Whether {@code a}'s bound IS-A {@code b}'s: equal, or {@code b} among the supertypes {@code a} records. */
    private static boolean isA(Use a, Use b, Function<String, Top> local, Function<String, Top> meta) {
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
     * the slot it is in, as {@code constructor.field}, so a refusal can name it. Each application written at a slot
     * naming a type is added to {@code applications}.
     */
    private record Walk(List<String> parameters, Function<String, Top> meta, boolean recordTemplate,
                        List<TypeRef> applications) {

        static void over(HeldBody held, List<String> parameters, Function<String, Top> meta,
                         List<TypeRef> applications) {
            String head = held.application().typeRef().orElse(null);
            Walk walk = new Walk(parameters, meta, RECORD.equals(head), applications);
            if (head != null && walk.resolve(head) instanceof RecordBody vocabulary) {
                walk.payload(held.application().coreValue(), head, vocabulary);
            }
        }

        private boolean declares(String name) {
            return parameters.contains(name);
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
                        && value instanceof TokenValue token && declares(token.text())) {
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
                case TokenValue token when declares(token.text()) -> token(token.text(), declared, type, slot);
                case ArrayValue array -> elements(array, type, slot);
                case MapValue map when type instanceof MapBody entries -> entries(map, entries, slot);
                case ReferenceValue(TypeRef applied) when namesTypes(declared, type)
                        && !applied.arguments().isEmpty() -> applications.add(applied);
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
