package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.tson.json.JsonTypeReader;

import java.util.List;
import java.util.Optional;

/**
 * A position's reader bound to what a Java target holds -- a record component, or a collection's element --
 * decided once, when the reader is built. Bind mode compiles each schema type to its natural reading (an atom to
 * its family's host value, an array to a {@code List}); a target that declares something more specific is met
 * here, and a target the position cannot fill is a mismatch named before any document exists.
 *
 * <ul>
 *   <li><b>An atom</b> is bound to the target's wire class ({@link AtomReader#boundTo}) -- an {@code int32} into a
 *       {@code long} -- and through the target's bridge where it has one. A target that binds structurally
 *       cannot hold an atom.</li>
 *   <li><b>An array or set</b> is read again for the target's own collection or array class, its element bound to
 *       the target's element in turn -- so {@code List<Long>}, {@code long[]} and {@code Set<UUID>} each receive
 *       what they declare.</li>
 *   <li><b>A map</b> is read again for the target's own map class, its keys and values bound to the class's.</li>
 *   <li><b>A tuple</b> is read again for the target's own tuple class, each position bound to its element.</li>
 *   <li><b>A record</b> is bound by its schema type's own name, so its class is checked against the target and
 *       nothing is rebuilt.</li>
 * </ul>
 * Anything else -- a dispatcher, a choice, a gap -- is read as it is.
 */
final class BindTargets {

    private BindTargets() {
    }

    /**
     * {@code reader} bound to {@code target}, recording a mismatch where it cannot be.
     *
     * @param what  the position, as a message names it -- {@code field 'age'}
     * @param where the target, as a message names it -- {@code component 'age'}
     */
    static JsonTypeReader<?> to(JsonTypeReader<?> reader, DataClass target, String what, String where,
                                List<String> mismatches) {
        if (reader instanceof AtomReader<?> atom) {
            if (!(target instanceof DataClassAtom bound)) {
                mismatches.add(what + " is an atom, and " + where + " binds " + target.typeClass().getName()
                        + " structurally");
                return reader;
            }
            Optional<AtomReader<?>> atTarget = atom.boundTo(bound.dataClass());
            if (atTarget.isEmpty()) {
                mismatches.add(what + " cannot produce " + bound.dataClass().getName() + ", which is what " + where
                        + " binds");
                return reader;
            }
            return bound.bridge().isPresent()
                    ? new BridgedReader(atTarget.get(), bound.bridge().get(), bound.typeClass())
                    : atTarget.get();
        }
        if (reader instanceof ArrayReader array) {
            if (!(target instanceof DataClassArray collection)) {
                mismatches.add(what + " is an array, and " + where + " binds " + target.typeClass().getName());
                return reader;
            }
            return BindArrayBuilder.forTarget(array, collection, what, mismatches);
        }
        if (reader instanceof MapObjectReader || reader instanceof MapPairsReader) {
            if (!(target instanceof DataClassMap map)) {
                mismatches.add(what + " is a map, and " + where + " binds " + target.typeClass().getName());
                return reader;
            }
            return BindMapBuilder.forTarget(reader, map, what, mismatches);
        }
        if (reader instanceof TupleReader tuple) {
            if (!(target instanceof DataClassTuple positional)) {
                mismatches.add(what + " is a tuple, and " + where + " binds " + target.typeClass().getName()
                        + ", which is not a tuple class (@Tuple)");
                return reader;
            }
            return BindTupleBuilder.forTarget(tuple, positional, what, mismatches);
        }
        if (reader instanceof RecordReader record && record.builder() instanceof BindRecordBuilder bound
                && !target.typeClass().isAssignableFrom(bound.typeClass())) {
            mismatches.add(what + " binds " + bound.typeClass().getName() + ", which " + where + " ("
                    + target.typeClass().getName() + ") cannot hold");
        }
        return reader;
    }
}
