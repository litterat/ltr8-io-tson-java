package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.TypeSlots;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Construction sugar: {@code C<a₁ … aₙ>} over a constructor {@code C} is the construction
 * {@code !C { f₁: a₁ … fₙ: aₙ }}, where {@code f₁ … fₙ} are {@code C}'s <b>argument slots</b> -- its required fields
 * that hold a type, typed {@code meta.type_expr} or by a reference naming types, and not pinned by its chain, in
 * declaration order. At a type slot the construction is written in place ({@code TypeInline}). It is a row of
 * [TSON-SCHEMA] §5.3's sugar table beside {@code [T]} and {@code {K => V}}, the same construction spelled by the
 * grammar, so {@code meta.array<text>}, {@code [text]} and {@code !meta.array { element_type: text }} build one
 * value, and nothing is written on a constructor to permit it.
 *
 * <p><b>A constructor</b> here is a closed record IS-A {@code meta.type}: one whose instances are types. A head
 * that composes {@code meta.top} alone builds values, and an open entry substitutes rather than constructs, so
 * neither is one.
 *
 * <p><b>A reference names types</b> where its {@code of}, past its alias hops, is {@code meta.top} or a constructor
 * ([TSON-SCHEMA] §5.2, {@link KindMembers#namesTypes}), read through refinements and aliases: {@code
 * meta.type_ref}, {@code meta.identifier_ref}, a {@code meta.ref<C>} over a constructor. A required field typed by a
 * reference naming values ({@code meta.ref<method>}) is no slot, so every argument is a type's name. A field is
 * required where it is neither optional nor given a value by its chain -- a pin or a default supplies one, so the
 * construction needs none.
 *
 * <p><b>The sugar decides only where each argument lands.</b> Every other verdict is the construction's, the same
 * one {@code !C { … }} written out gets: an argument the slot's {@code of} refuses ({@code meta.namespace<int32
 * text>}) is refused at link as {@code key_type: int32} is, and a required field no slot fills ({@code
 * meta.enum_type<meta.identifier>} leaves {@code members}) is refused by the constructor's reader as an incomplete
 * construction. Only the argument count is refused here; an argument is a reference by the grammar ([TSON-SCHEMA]
 * §5.10), a literal token being refused where it is written.
 */
public final class ConstructionSugar {

    private static final String TYPE = KernelNames.of("type");

    private static final String NAMESPACE = KernelNames.of("namespace");

    /** The kernel's {@code meta.type_expr}: what a type slot holds, a type's name or a type written in place. */
    private static final String TYPE_EXPR = KernelNames.of("type_expr");

    private ConstructionSugar() {
    }

    /**
     * Whether {@code definition}, the entry at the end of {@code name}'s alias chain, is a constructor construction
     * sugar applies: a closed record IS-A {@code meta.type}. An open record is applied by substitution, never
     * constructed, whatever its chain reaches (§5.10).
     */
    public static boolean constructs(String name, Top definition) {
        return definition instanceof RecordBody record && !record.isOpen()
                && (TYPE.equals(name) || TypeChain.supertypesOf(record).contains(TYPE));
    }

    /**
     * Refuses {@code application}, written at a type slot, where its constructor {@code name} builds a namespace type:
     * the kernel's {@code namespace} or a constructor refining it ([TSON-SCHEMA] §4.2, §5.2). A namespace value carries
     * its type's name as its tag, and a type written in place has none, so a namespace type is declared and named.
     *
     * @param types where the constructor's slots are looked up, for the declaration the message suggests
     * @throws SchemaValidationException where it builds one
     */
    static void refuseNamespaceInPlace(ApplyBody application, String name, Top definition,
                                       Function<String, Top> types) {
        if (!NAMESPACE.equals(name) && !TypeChain.supertypesOf(definition).contains(NAMESPACE)) {
            return;
        }
        List<String> slots = slots((RecordBody) definition, types);
        List<String> written = new ArrayList<>();
        for (int i = 0; i < slots.size() && i < application.arguments().size(); i++) {
            written.add(slots.get(i) + ": " + application.arguments().get(i).text());
        }
        throw new SchemaValidationException("'" + application.text() + "' constructs a namespace type where it is "
                + "used, and a namespace value carries its type's name as its tag, which a type written in place does "
                + "not have (§4.2, §5.2): declare it ('my_" + application.head().segments().getLast() + " => !"
                + application.head().name() + " { " + String.join("  ", written) + " }') and write that name");
    }

    /**
     * {@code constructor}'s argument slots, in declaration order: its required fields typed by a reference naming
     * types and not pinned by its chain.
     *
     * @param types where a field's type, and a reference's {@code of}, are looked up
     */
    public static List<String> slots(RecordBody constructor, Function<String, Top> types) {
        List<String> slots = new ArrayList<>();
        for (Map.Entry<String, RecordField> field : constructor.fields().entrySet()) {
            RecordField declared = field.getValue();
            if (!declared.optional() && declared.role() == FieldRole.FREE && declared.type() instanceof TypeRef type
                    && holdsAType(type.name(), types)) {
                slots.add(field.getKey());
            }
        }
        return slots;
    }

    /**
     * For each of {@code constructor}'s argument slots ({@link #slots}), in order, whether it holds a type written in
     * place: typed {@code meta.type_expr}, where an application closes in place, and not by a reference naming types,
     * which holds only a name ([TSON-SCHEMA] §4.2).
     */
    static List<Boolean> slotsInPlace(RecordBody constructor, Function<String, Top> types) {
        return slots(constructor, types).stream().map(slot -> TYPE_EXPR.equals(AliasChain.terminal(
                ((TypeRef) constructor.fields().get(slot).type()).name(), types))).toList();
    }

    /**
     * Whether a field typed {@code name} holds a type: typed {@code meta.type_expr}, a type's name or a type written in
     * place, or by a reference naming types ([TSON-SCHEMA] §5.3).
     */
    private static boolean holdsAType(String name, Function<String, Top> types) {
        return TYPE_EXPR.equals(AliasChain.terminal(name, types)) || namesTypes(name, types);
    }

    /**
     * Refuses {@code written<…>} with {@code supplied} arguments over a constructor whose argument slots are
     * {@code slots}, unless there is one argument for each.
     *
     * @throws SchemaValidationException where the counts differ
     */
    public static void requireArity(String written, List<String> slots, int supplied) {
        if (slots.size() == supplied) {
            return;
        }
        throw new SchemaValidationException(slots.isEmpty()
                ? "'" + written + "' is a constructor with no required field typed by a reference naming types, so "
                        + "'" + written + "<...>' gives arguments it has no field for (§5.3); construct it in braces, "
                        + "'!" + written + " { ... }', as a declaration"
                : "'" + written + "' is a constructor whose required fields typed by a reference naming types are "
                        + slots + ", so '" + written + "<...>' takes " + slots.size() + " argument"
                        + (slots.size() == 1 ? "" : "s") + ", one for each in that order, but " + supplied + " "
                        + (supplied == 1 ? "was" : "were") + " applied (§5.3)");
    }

    /** Whether the type {@code name} is a reference naming types, past its alias hops. */
    private static boolean namesTypes(String name, Function<String, Top> types) {
        Top type = types.apply(AliasChain.terminal(name, types));
        return type instanceof ReferenceBody reference && KindMembers.namesTypes(reference.of().name(), types);
    }

    /**
     * The construction {@code written<arguments>} denotes, as the binding record {@code !C { … }} would write: each
     * argument, a name, at its slot.
     *
     * @param written the head as the author wrote it, for a refusal
     * @param slots the constructor's argument slots ({@link #slots})
     * @throws SchemaValidationException where the count differs from the slots'
     */
    static RecordValue payload(String written, List<String> slots, List<TypeRef> arguments) {
        requireArity(written, slots, arguments.size());
        List<RecordValue.Field> fields = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            fields.add(new RecordValue.Field(slots.get(i), WireForm.plain(WireForm.refValue(arguments.get(i)))));
        }
        return new RecordValue(fields);
    }

    /**
     * The arguments of a constructor's application as its binding record writes them, and how the construction read
     * from it gets back those written in place. A reference reads a name and nothing else, so an argument written in
     * place -- an application ({@code meta.array<box<T>>}) or a construction a nested one closed to -- stands in the
     * binding record as a name of its own, which {@link #placed} replaces at the slot it landed at. One landing at a
     * slot that holds only a name is refused there, as an application written at that slot is ([TSON-SCHEMA] §4.2).
     */
    record StandIns(List<TypeRef> written, Map<String, TypeInline> inPlace, Map<String, TypeExpr> asWritten) {

        /** {@code arguments}, each a name or written in place, as the binding record writes them. */
        static StandIns of(List<TypeExpr> arguments) {
            return of(arguments, arguments);
        }

        /** The same, a refusal naming each argument as {@code asWritten} spells it, before it closed. */
        static StandIns of(List<TypeExpr> arguments, List<TypeExpr> asWritten) {
            Map<String, TypeInline> inPlace = new LinkedHashMap<>();
            Map<String, TypeExpr> shown = new LinkedHashMap<>();
            List<TypeRef> written = new ArrayList<>();
            for (int i = 0; i < arguments.size(); i++) {
                switch (arguments.get(i)) {
                    case TypeRef name -> written.add(name);
                    case TypeInline inline -> {
                        String standIn = "in_place_" + UUID.randomUUID().toString().replace("-", "");
                        inPlace.put(standIn, inline);
                        shown.put(standIn, i < asWritten.size() ? asWritten.get(i) : inline);
                        written.add(TypeRef.of(standIn));
                    }
                }
            }
            return new StandIns(written, inPlace, shown);
        }

        /** Stand-ins already made, {@code inPlace} by name, each shown as it is. */
        static StandIns of(Map<String, TypeInline> inPlace) {
            return new StandIns(List.of(), inPlace, Map.copyOf(inPlace));
        }

        /** {@code body} with each stand-in replaced by the argument it stands for. */
        Top placed(Top body) {
            if (inPlace.isEmpty()) {
                return body;
            }
            return TypeSlots.mapBody(body, new TypeSlots.Rewrite() {
                @Override
                public TypeRef atName(TypeRef reference) {
                    if (inPlace.containsKey(reference.name())) {
                        throw TemplateMaterialiser.nameRequired(asWritten.get(reference.name()));
                    }
                    return reference;
                }

                @Override
                public TypeExpr atSlot(TypeRef reference) {
                    TypeInline argument = inPlace.get(reference.name());
                    return argument != null ? argument : reference;
                }
            });
        }
    }
}
