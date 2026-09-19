package io.ltr8.tson.json.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Unbound;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bind mode's record: the bound class, constructed from the filled slots -- or nothing, where anything under the
 * record was reported.
 *
 * <p><b>Bind mode is all-or-nothing.</b> A tree keeps what it built, a record missing a field being a coherent
 * value to inspect beside the diagnostics. A bound object is the opposite -- real, typed application data whose
 * whole promise is that the document was good -- and a constructor handed nulls for components that never
 * arrived would either throw on the caller's stack or produce an object nobody wrote. So a record whose read
 * reported anything binds to {@code null}, the same rule {@code tson-compiler}'s bind mode keeps.
 *
 * <p><b>The class is checked against the schema when the reader is built</b> ({@link #factory}'s
 * {@link BindMismatchException}, each field bound to its component through {@link BindTargets}), not on the first
 * document that reaches the position -- a wiring mistake between a schema and the caller's own classes is cheapest
 * found before any document exists.
 */
final class BindRecordBuilder implements RecordBuilder {

    /**
     * Bind mode's concrete record reader over {@code binding}: the bound class resolved from the schema type's
     * names, each atom field's reader bound to what its component holds, and each schema-stated value made the
     * component's own class. A record with no subtypes bound to a union is a labelled choice
     * ({@link BindGroupUnionBuilder}). {@link DispatchFactories} decides whether a record position gets this or a
     * dispatcher in front of it.
     */
    static ValueReaderFactory factory(DataBindContext binding) {
        return (name, definition, context) -> {
            DataClass boundClass = boundClass(name, context, binding);
            if (boundClass instanceof DataClassUnion union) {
                if (!definition.subtypes().isEmpty()) {
                    return ownDataOfUnion(name, definition, context, binding, union);
                }
                return BindGroupUnionBuilder.reader(name, definition, context, binding, union);
            }
            if (!(boundClass instanceof DataClassRecord descriptor)) {
                throw new BindMismatchException("'" + name + "' is a record, and " + boundClass.typeClass().getName()
                        + ", which is bound to it, is not record-shaped");
            }
            RecordPlan plan = new RecordPlan(name, definition, context);
            int count = plan.names.length;
            JsonTypeReader<?>[] readers = plan.schemaReaders.clone();
            Object[] injected = new Object[count];
            int[] argument = new int[count];
            boolean[] filled = new boolean[descriptor.fields().length];
            List<String> mismatches = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Integer at = descriptor.fieldIndex().get(plan.fields[i].name());
                if (at == null) {
                    argument[i] = -1;
                    // A FIXED field is exempt: the schema settles its value, so a component would hold a constant
                    // the schema already knows. Every other field needs one -- an OPTIONAL one included, being
                    // exactly the field that works in development and fails the first time a caller sends it.
                    if (!isFixed(plan.states[i])) {
                        mismatches.add("no component for field '" + plan.fields[i].name() + "'");
                    }
                    continue;
                }
                DataClassField component = descriptor.fields()[at];
                argument[i] = component.index();
                filled[at] = true;
                readers[i] = BindTargets.to(readers[i], component.dataClass(), "field '" + plan.fields[i].name()
                        + "'", "component '" + component.name() + "'", mismatches);
                if (plan.stated[i] != null && component.dataClass() instanceof DataClassAtom bound) {
                    injected[i] = statedValue(plan.stated[i], bound, plan.fields[i].name(), mismatches);
                }
            }
            DataClassField carrier = descriptor.annotationsCarrier().orElse(null);
            Set<String> unbound = unboundComponents(descriptor.typeClass());
            for (int i = 0; i < descriptor.fields().length; i++) {
                DataClassField component = descriptor.fields()[i];
                if (!filled[i] && component != carrier && !unbound.contains(component.name())) {
                    mismatches.add("component '" + component.name() + "' is filled by no field, so it reaches the "
                            + "constructor as null on every document -- annotate it @Unbound if the class means to "
                            + "own it");
                }
            }
            if (!mismatches.isEmpty()) {
                throw new BindMismatchException("'" + plan.displayName + "' and " + descriptor.typeClass().getName()
                        + " do not agree: " + String.join("; ", mismatches));
            }
            return new RecordReader(plan, readers, injected, new BindRecordBuilder(descriptor, argument));
        };
    }

    private final DataClassRecord descriptor;

    /** Each field slot's constructor argument, or -1 for a FIXED field the class has no component for. */
    private final int[] argument;

    /** The component §4.3 leaves empty -- JSON carries no annotations -- or null where the class has none. */
    private final DataClassField carrier;

    /** What makes the bound class of the constructed data form, or null where the class is its own. */
    private final DataClassBridge bridge;

    private BindRecordBuilder(DataClassRecord descriptor, int[] argument) {
        this.descriptor = descriptor;
        this.argument = argument;
        this.carrier = descriptor.annotationsCarrier().orElse(null);
        this.bridge = descriptor.bridge().orElse(null);
    }

    /** The class this builds, for a caller checking a component against it. */
    Class<?> typeClass() {
        return descriptor.typeClass();
    }

    @Override
    public Object build(JsonReadContext ctx, Object[] slots, boolean clean) {
        if (!clean) {
            return null;
        }
        Object[] arguments = new Object[descriptor.fields().length];
        if (carrier != null) {
            arguments[carrier.index()] = Annotations.empty();
        }
        for (int i = 0; i < slots.length; i++) {
            Object slot = slots[i];
            if (argument[i] >= 0 && slot != Slots.ABSENT && slot != Slots.NULL_KEPT) {
                arguments[argument[i]] = slot;
            }
        }
        try {
            // A bridged class (`ToData`, `@Transparent` over a record) constructs its data form, which the bridge
            // makes the class of: what this builds is always the bound class itself.
            Object built = descriptor.constructor().invoke(arguments);
            return bridge == null ? built : bridge.toObject().invoke(built);
        } catch (Throwable e) {
            rejected(ctx, descriptor.typeClass(), e);
            return null;
        }
    }

    /**
     * A bound class's own rule refusing the values read for it -- a compact constructor's check, most often, or a
     * bridge's -- which is a fact about this document and not a fault in this library.
     */
    static void rejected(JsonReadContext ctx, Class<?> type, Throwable e) {
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "%s rejected the value read for it: %s"
                .formatted(type.getSimpleName(), e), "a value " + type.getSimpleName() + " accepts",
                String.valueOf(e.getMessage()));
    }

    @Override
    public Object refused() {
        return null;
    }

    /**
     * An OPEN record with subtypes bound to a union -- a sealed interface, most often, its implementations the
     * subtypes' classes. The record itself has no class to build, so a value placed at it rather than at a
     * subtype has nothing to bind to; every subtype's class is checked, here, to be a member of the union, so a
     * tag the dispatcher follows always lands on a class the position can hold. A subtype with no bound class is
     * left to the read that reaches it, which defers it on the usual terms.
     */
    private static JsonTypeReader<?> ownDataOfUnion(String name, TypeDefinition definition,
                                                    ValueReaderContext context, DataBindContext binding,
                                                    DataClassUnion union) {
        List<String> outside = new ArrayList<>();
        for (String subtype : definition.subtypes()) {
            DataClass member = boundClassOrNull(subtype, context, binding);
            if (member != null && !union.isMemberType(member.typeClass())) {
                outside.add("subtype '" + subtype + "' binds " + member.typeClass().getName()
                        + ", which is not a member");
            }
        }
        String displayName = EntryDisplayName.of(name, definition, context.schema().entries());
        if (!outside.isEmpty()) {
            throw new BindMismatchException("'" + displayName + "' and " + union.typeClass().getName()
                    + " do not agree: " + String.join("; ", outside));
        }
        return new NoDataOfItsOwn(displayName, union.typeClass(), String.join(" | ", definition.subtypes()),
                context.locationOf(name, definition));
    }

    /**
     * Where an untagged value, or one tagged with the record's own name, reaches a record bound to a union: it is
     * refused, since what the position builds is one of the union's members and a value of the record itself is
     * none of them -- the same verdict {@code tson-compiler}'s bind mode gives. A tag naming a subtype never
     * reaches here; the dispatcher in front of it sends that to the subtype's class.
     */
    private static final class NoDataOfItsOwn implements JsonTypeReader<Object> {

        private final String name;
        private final Class<?> union;
        private final String subtypes;
        private final JsonSchemaLocation schemaLocation;

        NoDataOfItsOwn(String name, Class<?> union, String subtypes, JsonSchemaLocation schemaLocation) {
            this.name = name;
            this.union = union;
            this.subtypes = subtypes;
            this.schemaLocation = schemaLocation;
        }

        @Override
        public Object read(JsonReadContext ctx) {
            ctx = ctx.inRecord(schemaLocation);
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "'%s' binds to %s, which has no data of its own -- a value "
                    .formatted(name, union.getSimpleName()) + "here carries a '$type' naming one of its subtypes ("
                    + subtypes + ")", "a '$type' naming one of (" + subtypes + ")", "no subtype named");
            EventSkip.nextValue(ctx);
            return null;
        }
    }

    /**
     * The class bound to entry {@code name}, tried under the names an author wrote for it before the entry's own:
     * a binding map is written in the names in the schema, and an entry a template application materialised has a
     * minted name no author could have written ([TSON-SCHEMA] §8.2). No class at all is deferred rather than
     * fatal -- a schema declares types a given consumer never binds -- so it reaches the first read of this type.
     */
    private static DataClass boundClass(String name, ValueReaderContext context, DataBindContext binding) {
        DataBindException first = null;
        List<String> candidates = bindingNames(name, context);
        for (String candidate : candidates) {
            try {
                return binding.getDescriptor(candidate);
            } catch (DataBindException e) {
                first = first == null ? e : first;
            }
        }
        throw new MissingBindingException("no bound Java class for '" + String.join("' or '", candidates)
                + "': nothing in this bind context resolves that schema type name"
                + (first == null ? "" : " -- " + first.getMessage()), first);
    }

    /** {@link #boundClass}, or null where nothing is bound -- for a question a missing binding does not answer. */
    private static DataClass boundClassOrNull(String name, ValueReaderContext context, DataBindContext binding) {
        for (String candidate : bindingNames(name, context)) {
            try {
                return binding.getDescriptor(candidate);
            } catch (DataBindException ignored) {
                // not bound under this name; the next is tried
            }
        }
        return null;
    }

    /** The names a bind lookup for entry {@code name} tries, in order: the aliases an author wrote, then its own. */
    private static List<String> bindingNames(String name, ValueReaderContext context) {
        List<String> candidates = new ArrayList<>(context.admitting(List.of(name)));
        candidates.remove(name);
        candidates.add(name);
        return candidates;
    }

    /** A default or a pin in the component's own class, recorded as a mismatch where the family cannot say it so. */
    private static Object statedValue(FieldValue value, DataClassAtom bound, String field, List<String> mismatches) {
        try {
            Object wire = value.parser().boundTo(bound.dataClass()).orElseThrow().read(value.text());
            return bound.bridge().isPresent() ? bound.bridge().get().toObject().invoke(wire) : wire;
        } catch (Throwable e) {
            mismatches.add("field '" + field + "' states '" + value.text() + "', which "
                    + bound.typeClass().getName() + " cannot hold: " + e.getMessage());
            return null;
        }
    }

    /** The components a class marked as its own ({@code @Unbound}), on the component or its accessor. */
    private static Set<String> unboundComponents(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Set<String> unbound = new LinkedHashSet<>();
        if (components != null) {
            for (RecordComponent component : components) {
                if (component.isAnnotationPresent(Unbound.class)
                        || component.getAccessor().isAnnotationPresent(Unbound.class)) {
                    unbound.add(component.getName());
                }
            }
        }
        return unbound;
    }

    private static boolean isFixed(FieldState state) {
        return state == FieldState.REQUIRED_FIXED || state == FieldState.OPTIONAL_FIXED;
    }
}
