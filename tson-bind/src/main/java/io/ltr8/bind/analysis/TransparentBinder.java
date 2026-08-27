package io.ltr8.bind.analysis;

import io.ltr8.annotation.Transparent;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataClassRecord;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.RecordComponent;

/**
 * Resolves a {@link Transparent} class to the descriptor of the one component it wraps, with a {@link
 * DataClassBridge} that unwraps and rewraps it.
 *
 * <p><b>No descriptor kind of its own.</b> Transparency is a statement about representation -- "my wire form
 * is my component's" -- and that is what a bridge already says. So the result is an ordinary
 * {@link DataClassRecord} or {@link DataClassAtom} whose shape is the <em>component's</em> and whose
 * {@code typeClass} is the wrapper, exactly the arrangement {@code DefaultRecordBinder}'s {@code ToData} path
 * produces. Everything that already unwraps a bridge -- the schemaless reader, the object writer -- handles a
 * transparent class with no case of its own, and nothing is added to the set of {@code DataClass} kinds.
 *
 * <p><b>What it refuses, and why each is a refusal rather than a silent fallback.</b> A class carrying the
 * marker has said what it wants; failing to deliver it quietly would leave the wrapper in the output, which
 * is the whole thing the marker exists to prevent.
 * <ul>
 * <li><b>Not a record, or not exactly one component</b> -- there is no single value to be transparent to.</li>
 * <li><b>A component whose descriptor is neither a record nor an atom</b> -- only those two carry a bridge
 *     ({@code DataClassArray}/{@code Map}/{@code Tuple}/{@code Union} have no bridge-taking constructor), so
 *     the bridge would be dropped and the wrapper would silently reappear.</li>
 * <li><b>A component that is itself bridged</b> (an {@code enum}, or a type with a registered bridge) -- two
 *     bridges over one position would have to compose into one, and a single {@link DataClassBridge} cannot
 *     hold both conversions.</li>
 * </ul>
 *
 * <p><b>A wrapper on a cycle in the type graph cannot work</b>, and does not need its own check: the
 * component's shape has to be copied, so its descriptor must be finished, and {@code
 * DataBindContext.getDescriptor} already refuses a re-entrant resolution with a message that says so. A
 * deferred component source is no use here -- there would be nothing to copy at the moment the copy is made.
 */
final class TransparentBinder {

    /** The descriptor for {@code targetClass}: its component's shape, wearing its own class and a bridge. */
    DataClass resolve(DataBindContext context, Class<?> targetClass) throws DataBindException {
        RecordComponent component = onlyComponent(targetClass);
        DataClassBridge bridge = bridge(targetClass, component);
        DataClass wrapped = context.getDescriptor(component.getType(), component.getGenericType());
        if (wrapped.bridge().isPresent()) {
            throw new DataBindException(String.format(
                    "@Transparent class %s wraps %s, which is itself bridged: one position cannot carry two "
                            + "bridges, so the wrapper has no representation to be transparent to",
                    targetClass.getName(), component.getType().getName()));
        }
        return switch (wrapped) {
            case DataClassRecord record -> new DataClassRecord(targetClass, bridge, record.isMutable(),
                    record.creator(), record.constructor(), record.fields(),
                    record.annotationsCarrier().orElse(null));
            case DataClassAtom ignored -> new DataClassAtom(targetClass, bridge);
            default -> throw new DataBindException(String.format(
                    "@Transparent class %s wraps %s, which resolves to %s: only a record or an atom carries a "
                            + "bridge, so this wrapper cannot be made transparent",
                    targetClass.getName(), component.getType().getName(),
                    wrapped.getClass().getSimpleName()));
        };
    }

    /** The one component the wire form is, or a refusal naming what the class has instead. */
    private static RecordComponent onlyComponent(Class<?> targetClass) throws DataBindException {
        RecordComponent[] components = targetClass.getRecordComponents();
        if (components == null) {
            throw new DataBindException(String.format(
                    "@Transparent is only meaningful on a record: %s is not one, so there is no single "
                            + "component to be transparent to", targetClass.getName()));
        }
        if (components.length != 1) {
            throw new DataBindException(String.format(
                    "@Transparent record %s has %d components, and a transparent wrapper writes exactly one: "
                            + "the others would have nowhere to go", targetClass.getName(), components.length));
        }
        return components[0];
    }

    /**
     * {@code accessor} out, canonical constructor back -- the two halves of the wrapper, taken as handles so
     * that nothing downstream names the wrapper class.
     */
    private static DataClassBridge bridge(Class<?> targetClass, RecordComponent component)
            throws DataBindException {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            MethodHandle toData = lookup.unreflect(component.getAccessor());
            MethodHandle toObject = lookup.unreflectConstructor(
                    targetClass.getConstructor(component.getType()));
            return new DataClassBridge(component.getType(), toData, toObject);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new DataBindException(String.format(
                    "@Transparent record %s needs a public canonical constructor taking its one component (%s)",
                    targetClass.getName(), component.getType().getName()), e);
        }
    }
}
