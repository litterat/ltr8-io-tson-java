package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Param;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
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
 * [TSON-SCHEMA] §5.10's parameters: where each may stand, and the bound each is written with.
 *
 * <p><b>A parameter is a type.</b> It stands only at a field's type, or as an argument of an application there or in a
 * parent. A parameter anywhere else is refused at the declaration, naming the slot ({@link #at}): a field's default or
 * fixed value ({@link #atValue}), a size bound, a field name, a group member, a field's {@code optional}, {@code
 * voidable} or {@code role}, the record's {@code extension}, {@code name_type} or {@code discriminators}. Each would
 * leave the open record without one shape its applications share, and a field's value in particular is never a
 * parameter, whatever the field's type: a pin on a slot typed {@code meta.type_ref} ({@code <T> meta.array ^ {
 * element_type?: = T }}) is refused with every other parameter at a value. An open record built from a record body
 * holds a parameter only where it may stand, its fields' types and its parents' arguments being the only references it
 * has; the explicit spelling, {@code <T> !meta.record { … }}, is walked against {@code record}'s own vocabulary before
 * it is bound ({@link #checkSlots}).
 *
 * <p><b>A bound is written.</b> A declaration writes one after the name ({@code <T: text>}), which must name a type;
 * otherwise the parameter is unbounded. Nothing about a parameter is inferred from its uses. A parameter passed as an
 * argument to a bounded parameter of another template must itself be written with a bound that IS-A that one, so
 * {@code rebox => <T> { b: boxed<T> }} over {@code boxed => <T: text> { v: T }} is refused at {@code rebox}. Each
 * declaration is checked once, against the bounds its callees wrote, so no order among templates is needed. A
 * constructor's slot judges the argument that arrives when the record is applied, so a parameter passed to one
 * ({@code meta.map<K text>}, {@code [boxed<T>]} as {@code meta.array<boxed<T>>}) needs no bound, the
 * applications nested in its arguments being judged in turn.
 */
final class ParameterTypes {

    /** The kernel record whose {@code type} and {@code value} slots an open record's field binds. */
    private static final String RECORD_FIELD = "meta.record_field";

    /** The constructor an open record applies. */
    private static final String RECORD = KernelNames.of("record");

    /** The one slot of an open record a parameter may stand in as a whole token: a field's type. */
    private static final String FIELD_TYPE = RECORD_FIELD + "." + WireForm.TYPE;

    /** The kernel's type a type slot holds: a name or a type written in place, {@code { of: … }}. */
    private static final String TYPE_EXPR = KernelNames.of("type_expr");

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
     * Checks every local open record's parameters at its declaration, and returns the entries refused: that each
     * written bound names a type, and that each parameter passed to a bounded parameter is written with a bound that
     * IS-A it.
     *
     * <p>{@code entries} is the <b>whole namespace</b>, imports included, because a local template may pass a
     * parameter to an imported one, whose recorded bounds are what its author wrote. {@code local} is the subset this
     * schema produced: those are checked, and the only names a failure is reported against, since an imported entry
     * was checked in its own schema. {@code meta} is where a bound's name is read when the schema does not hold it.
     */
    static Set<String> check(Map<String, Top> entries, Set<String> local, Function<String, Top> meta,
                             FailureReporter reporter) {
        Set<String> refused = new LinkedHashSet<>();
        Map<String, List<TypeRef>> written = new LinkedHashMap<>();
        entries.forEach((name, definition) -> {
            if (!OpenEntry.isOpen(definition) || !local.contains(name)) {
                return;
            }
            try {
                written.put(name, OpenEntry.applications(definition));
                for (Param parameter : OpenEntry.parameters(definition)) {
                    parameter.bound().ifPresent(bound -> requireType(bound, entries::get));
                }
            } catch (SchemaValidationException e) {
                refused.add(name);
                reporter.report(name, e);
            }
        });
        Callees callees = new Callees(entries, local, Set.copyOf(refused), meta);
        written.forEach((name, applications) -> {
            Map<String, Param> mine = new LinkedHashMap<>();
            OpenEntry.parameters(entries.get(name)).forEach(p -> mine.put(p.name(), p));
            try {
                for (TypeRef application : applications) {
                    callees.passedOn(application, mine::get);
                }
            } catch (SchemaValidationException e) {
                refused.add(name);
                reporter.report(name, e);
            }
        });
        return refused;
    }

    /**
     * The walk's own refusals, thrown, over an open record's explicit payload not yet bound, {@code <T> !meta.record {
     * … }}: its parameters are confined to its field types before {@code record}'s reader is asked to bind them, so a
     * parameter at a value or a scalar slot is refused for where it stands ([TSON-SCHEMA] §5.10) rather than for the
     * token it is. The payload is walked as the {@code record} construction the author wrote, so each slot is named as
     * {@code record} and {@code record_field} name it.
     */
    static void checkSlots(List<String> parameters, DataValue payload, Function<String, Top> meta) {
        Walk walk = new Walk(parameters, meta, false);
        if (walk.resolve(RECORD) instanceof RecordBody vocabulary) {
            walk.payload(payload.coreValue(), RECORD, vocabulary);
        }
    }

    /** Where a declaration whose parameters are refused is reported, entry by entry. */
    @FunctionalInterface
    interface FailureReporter {
        void report(String entryName, SchemaValidationException error);
    }

    // ── A parameter passed on ────────────────────────────────────────────────────────────────────

    /**
     * The applications a declaration writes, judged against the bounds their heads wrote. A head already refused is
     * not judged against: its verdict is in.
     */
    private record Callees(Map<String, Top> entries, Set<String> local, Set<String> refused,
                           Function<String, Top> meta) {

        /**
         * Each argument of {@code application} that is one of the declaration's parameters ({@code parameterOf}
         * maps a name to it, or to {@code null}), against the head's parameter at that position; a nested
         * application is judged in turn. A head that is no open record -- a constructor's construction sugar --
         * has no parameters to bound.
         */
        void passedOn(TypeRef application, Function<String, Param> parameterOf) {
            Top head = entries.get(application.name());
            List<Param> heads = OpenEntry.isOpen(head) && !refused.contains(application.name())
                    ? OpenEntry.parameters(head) : List.of();
            requireNames(application);
            for (int i = 0; i < application.arguments().size(); i++) {
                TypeRef argument = application.arguments().get(i);
                Param mine = argument.arguments().isEmpty() ? parameterOf.apply(argument.name()) : null;
                if (mine == null) {
                    passedOn(argument, parameterOf);
                } else if (i < heads.size()) {
                    Param theirs = heads.get(i);
                    theirs.bound().ifPresent(bound -> requireBound(mine, application.name(), theirs.name(), bound));
                }
            }
        }

        /**
         * Refuses an argument of {@code application} that must be a name and is an application ([TSON-SCHEMA] §4.2,
         * §5.3): any argument of an open record's application, and one of a constructor's that lands at a slot
         * holding only a name ({@code meta.ref<box<T>>}). An argument at a constructor's type slot is held and closes
         * there when the record is applied ({@code meta.array<box<T>>}).
         */
        private void requireNames(TypeRef application) {
            Function<String, Top> lookup = name -> lookup(name, true, entries::get, meta);
            String terminal = AliasChain.terminal(application.name(), lookup);
            Top head = lookup.apply(terminal);
            boolean constructs = !OpenEntry.isOpen(head) && ConstructionSugar.constructs(terminal, head);
            if (constructs) {
                ConstructionSugar.refuseNamespaceInPlace(application, terminal, head, lookup);
            }
            List<Boolean> inPlace = OpenEntry.isOpen(head) ? null
                    : constructs ? ConstructionSugar.slotsInPlace((RecordBody) head, lookup) : List.of();
            for (int i = 0; i < application.arguments().size(); i++) {
                TypeRef argument = application.arguments().get(i);
                if (argument.arguments().isEmpty()) {
                    continue;
                }
                if (inPlace == null) {
                    throw TemplateMaterialiser.argumentNotAName(argument, application);
                }
                if (i < inPlace.size() && !inPlace.get(i)) {
                    throw TemplateMaterialiser.nameRequired(argument);
                }
            }
        }

        /** {@code mine}, passed to {@code callee}'s {@code theirs}, bounded by {@code bound}: written IS-A it. */
        private void requireBound(Param mine, String callee, String theirs, TypeRef bound) {
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
     * (§4.1), so a bound naming one would admit no argument. A bound is a name, {@code meta.param.bound} being
     * {@code meta.type_ref}, so an application there is refused (§4.2).
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
        if (!bound.arguments().isEmpty()) {
            throw TemplateMaterialiser.nameRequired(bound);
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
     * An open record's explicit payload walked against {@code record}'s own vocabulary, whose one whole-token parameter
     * slot is a field's type; every walk below carries the slot it is in, as {@code constructor.field}, so a refusal
     * can name it.
     *
     * <p><b>A type written in place is walked into</b> ({@code inPlace}), its {@code { of: !C { … } }} body against
     * {@code C}'s vocabulary. A parameter at a type slot there -- {@code type: { of: !meta.array { element_type: T }
     * }} -- is no author's error, the slot holding a type, but is not yet supported: an open record's parameters are
     * scoped and substituted at its field types and the applications there ({@link RecordBody}, {@code Substitution}),
     * and neither reaches inside a type written in place, so the parameter would stand unbound. The sugar an author
     * writes never puts one there, {@code [T]} over a parameter being the application {@code meta.array<T>} ({@code
     * SchemaDesugarer}). A parameter at a slot that holds no type is refused for where it stands, inside the wrapper as
     * outside it.
     */
    private record Walk(List<String> parameters, Function<String, Top> meta, boolean inPlace) {

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
                unmarked.getFirst().getValue().ownType().ifPresent(type -> value(payload, type,
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
                Optional.ofNullable(vocabulary.fields().get(written.name())).flatMap(RecordField::ownType)
                        .ifPresent(type -> value(value, type, constructor + "." + written.name()));
            }
        }

        /**
         * One written value at {@code slot}, against the type the slot declares: a name's entry, or a type the
         * vocabulary writes in place ({@code variants: [meta.type_expr]}), faced as its body.
         */
        private void value(CoreValue written, TypeExpr declared, String slot) {
            String declaredName = declared instanceof TypeRef reference ? reference.name() : null;
            Top type = switch (declared) {
                case TypeRef reference -> resolve(reference.name());
                case TypeInline inline -> inline.of();
            };
            switch (written) {
                case TokenValue token when declares(token.text()) && inPlace
                        && (type instanceof ReferenceBody || TYPE_EXPR.equals(declaredName)) ->
                        throw inPlace(token.text(), slot);
                // A field written positionally, `item => T`, is its record_field's one unmarked slot, its type.
                case TokenValue token when declares(token.text()) -> token(token.text(),
                        RECORD_FIELD.equals(declaredName) ? FIELD_TYPE : slot);
                case RecordValue wrapper when TYPE_EXPR.equals(declaredName) -> wrapped(wrapper);
                case ArrayValue array -> elements(array, type, slot);
                case MapValue map when type instanceof MapBody entries -> entries(map, entries, slot);
                case RecordValue record when type instanceof RecordBody nested && declaredName != null ->
                        record(record, declaredName, nested);
                default -> {
                }
            }
        }

        /**
         * A type written in place, {@code { of: !C { … } }}, walked as the construction of {@code C} it holds. Where
         * {@code C} is not yet a record this namespace can show -- a constructor of this schema still resolving -- no
         * slot can be told from another, and a parameter anywhere inside is the gap.
         */
        private void wrapped(RecordValue wrapper) {
            for (RecordValue.Field field : wrapper.fields()) {
                if (!WireForm.OF.equals(field.name())) {
                    continue; // a field the wrapper does not declare, which its own reader reports
                }
                DataValue construction = field.value();
                String constructor = construction.typeRef().orElse(null);
                Walk inside = new Walk(parameters, meta, true);
                if (constructor != null && inside.resolve(constructor) instanceof RecordBody vocabulary) {
                    inside.payload(construction.coreValue(), constructor, vocabulary);
                } else {
                    inside.anyParameter(construction.coreValue());
                }
            }
        }

        /** Refuses, as the gap it is, a parameter anywhere in {@code written}. */
        private void anyParameter(CoreValue written) {
            switch (written) {
                case TokenValue token when declares(token.text()) -> throw inPlace(token.text(), "a type written in place");
                case ArrayValue array -> array.elements().forEach(element -> anyParameter(element.coreValue()));
                case MapValue map -> map.entries().forEach(entry -> {
                    anyParameter(entry.key().coreValue());
                    anyParameter(entry.value().coreValue());
                });
                case RecordValue record -> record.fields().forEach(field -> anyParameter(field.value().coreValue()));
                default -> {
                }
            }
        }

        /** A parameter at {@code slot} inside a type written in place, which phase 1 cannot bind (see this walk). */
        private static UnsupportedOperationException inPlace(String parameter, String slot) {
            return new UnsupportedOperationException("parameter '" + parameter + "' stands at " + slot + ", inside "
                    + "a type written in place: an open record's parameters are not yet substituted there -- write "
                    + "the application the sugar spells, 'meta.array<" + parameter + ">' for '[" + parameter + "]'");
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

        /** A parameter standing at a slot: admitted only at a field's type, and refused naming the slot anywhere else. */
        private void token(String parameter, String slot) {
            if (!FIELD_TYPE.equals(slot)) {
                throw at(parameter, slot);
            }
        }

        private void elements(ArrayValue array, Top type, String slot) {
            TypeExpr element = switch (type) {
                case ArrayBody arrayBody -> arrayBody.elementType();
                default -> null;
            };
            if (element == null && !(type instanceof TupleBody)) {
                return; // not a collection slot -- a shape error the constructor's own reader reports
            }
            for (int i = 0; i < array.elements().size(); i++) {
                TypeExpr declared = element != null ? element
                        : ((TupleBody) type).elements().get(Math.min(i, ((TupleBody) type).elements().size() - 1))
                                .elementType();
                value(array.elements().get(i).coreValue(), declared, slot);
            }
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
