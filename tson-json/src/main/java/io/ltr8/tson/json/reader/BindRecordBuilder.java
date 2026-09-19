package io.ltr8.tson.json.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Unbound;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.FieldState;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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
 * <p><b>The class is checked against the schema when the reader is built</b> ({@link #FACTORY}'s
 * {@link BindMismatchException}), not on the first document that reaches the position -- a wiring mistake
 * between a schema and the caller's own classes is cheapest found before any document exists.
 */
final class BindRecordBuilder implements RecordBuilder {

    /**
     * Bind mode's concrete record reader over {@code binding}: the bound class resolved from the schema type's
     * names, each atom field's reader bound to what its component holds, and each schema-stated value made the
     * component's own class. {@link DispatchFactories} decides whether a record position gets this or a
     * dispatcher in front of it.
     */
    static ValueReaderFactory factory(DataBindContext binding) {
        return (name, definition, context) -> {
            RecordPlan plan = new RecordPlan(name, definition, context);
            DataClassRecord descriptor = boundRecord(name, context, binding);
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
                if (!(readers[i] instanceof AtomReader<?> atom)) {
                    continue;
                }
                if (!(component.dataClass() instanceof DataClassAtom bound)) {
                    mismatches.add("field '" + plan.fields[i].name() + "' is an atom, and component '"
                            + component.name() + "' binds " + component.type().getName() + " structurally");
                    continue;
                }
                Optional<AtomReader<?>> atTarget = atom.boundTo(bound.dataClass());
                if (atTarget.isEmpty()) {
                    mismatches.add("field '" + plan.fields[i].name() + "' cannot produce "
                            + bound.dataClass().getName() + ", which is what component '" + component.name()
                            + "' binds");
                    continue;
                }
                readers[i] = bound.bridge().isPresent()
                        ? new BridgedReader(atTarget.get(), bound.bridge().get(), bound.typeClass())
                        : atTarget.get();
                if (plan.stated[i] != null) {
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

    private BindRecordBuilder(DataClassRecord descriptor, int[] argument) {
        this.descriptor = descriptor;
        this.argument = argument;
        this.carrier = descriptor.annotationsCarrier().orElse(null);
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
            if (argument[i] >= 0 && slot != RecordReader.ABSENT && slot != RecordReader.NULL_KEPT) {
                arguments[argument[i]] = slot;
            }
        }
        try {
            return descriptor.constructor().invoke(arguments);
        } catch (Throwable e) {
            // The class's own rule refusing the values -- a compact constructor's check, most often -- which is
            // a fact about this document and not a fault in this library.
            ctx.report(Diagnostic.Code.TYPE_MISMATCH, "%s rejected the value read for it: %s"
                    .formatted(descriptor.typeClass().getSimpleName(), e), "a value "
                    + descriptor.typeClass().getSimpleName() + " accepts", String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Override
    public Object refused() {
        return null;
    }

    /**
     * The class bound to entry {@code name}, tried under the names an author wrote for it before the entry's own:
     * a binding map is written in the names in the schema, and an entry a template application materialised has a
     * minted name no author could have written ([TSON-SCHEMA] §8.2). No class at all is deferred rather than
     * fatal -- a schema declares types a given consumer never binds -- so it reaches the first read of this type.
     */
    private static DataClassRecord boundRecord(String name, ValueReaderContext context, DataBindContext binding) {
        List<String> candidates = new ArrayList<>(context.admitting(List.of(name)));
        candidates.remove(name);
        candidates.add(name);
        DataBindException first = null;
        for (String candidate : candidates) {
            try {
                DataClass bound = binding.getDescriptor(candidate);
                if (bound instanceof DataClassRecord record) {
                    return record;
                }
                throw new BindMismatchException("'" + name + "' is a record, and " + bound.typeClass().getName()
                        + ", which is bound to it, is not record-shaped");
            } catch (DataBindException e) {
                first = first == null ? e : first;
            }
        }
        throw new MissingBindingException("no bound Java class for '" + String.join("' or '", candidates)
                + "': nothing in this bind context resolves that schema type name"
                + (first == null ? "" : " -- " + first.getMessage()), first);
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
