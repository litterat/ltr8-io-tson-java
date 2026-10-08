package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.TupleElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A position's reader bound to what a Java target declares -- a record component, or, at any depth below one, a
 * collection's element, a map's key or value, a tuple's position -- decided once, when the reader is built.
 * Bind mode compiles each schema type to its natural reading (an atom to its family's host value, an array to a
 * {@code List} of whatever its element type binds); a target that declares something more specific is met here,
 * and one the position cannot fill is a mismatch named before any document exists. {@code tson-json}'s
 * {@code BindTargets} is the same rule over that encoding's readers.
 *
 * <ul>
 *   <li><b>An atom</b> is bound to the target's wire class ({@link AtomTypeReader#boundTo}) -- an {@code int32}
 *       into a {@code long} -- and the uninterpreted {@code value} atom is read as that class
 *       ({@link ValueParser#at}). A target that binds structurally cannot hold an atom.</li>
 *   <li><b>An array, map or tuple</b> is read again for the target's own class, each element, key, value or
 *       position bound to the target's in turn -- so {@code List<Long>}, {@code long[]}, {@code List<int[]>}
 *       and {@code Map<String, SequencedMap<String, Long>>} each receive what they declare. A void element
 *       cannot reach a primitive array, an ordered map needs a map that keeps insertion order, and a tuple
 *       class needs the tuple's arity and somewhere for each voidable position's void value.</li>
 *   <li><b>A record</b> is bound by its schema type's own name, so its class is checked against the target and
 *       nothing is rebuilt.</li>
 * </ul>
 * The target's bridge, then its {@code Annotated} box, wrap whatever comes out. Anything else -- a dispatcher, a
 * choice, a gap -- is read as it is.
 *
 * <p><b>The element is bound from the schema's own reader, never from the natural one.</b> A container's
 * natural reader has already wrapped its element for the class the factory guessed from the schema's type name
 * ({@code String} where nothing is bound), so rebinding starts again from {@link TsonTypeReaderResolver#resolve}.
 * That guess is also why the natural path does not come through here: binding an element to it would refuse a
 * schema for a class nobody declared.
 */
final class BindTargets {

    private BindTargets() {
    }

    /** What every rebuild at one record field shares: the field's name, the schema's readers, the mismatches. */
    record Site(String field, TsonTypeReaderResolver resolver, AnnotationTypes annotationTypes,
                List<String> mismatches) {
    }

    /**
     * {@code reader} bound to {@code target}, recording in {@code site} where it cannot be.
     *
     * @param what  the position, as a message names it -- {@code field 'age'}
     * @param where the target, as a message names it -- {@code component 'age'}
     * @param top   whether this is the field's own position, whose rebuilt reader takes the field's name
     */
    static TsonTypeReader<?> to(TsonTypeReader<?> reader, DataClass target, String what, String where, Site site,
                                boolean top) {
        DataClass payload = target instanceof DataClassAnnotated boxed ? boxed.valueClass() : target;
        TsonTypeReader<?> bound = shaped(reader, payload, what, where, site, top);
        return AnnotationBoxing.wrap(ElementBridging.wrap(bound, payload), target, site.annotationTypes());
    }

    private static TsonTypeReader<?> shaped(TsonTypeReader<?> reader, DataClass target, String what, String where,
                                            Site site, boolean top) {
        // A §7.2 subsumption guard wraps the reader it checks: look through it and put it back around the result.
        if (reader instanceof VariantSchemaReader guard) {
            TsonTypeReader<?> inner = shaped(guard.wrapped(), target, what, where, site, top);
            return inner == guard.wrapped() ? reader : guard.rewrap(inner);
        }
        return switch (reader) {
            case AtomTypeReader<?> atom -> atom(atom, target, what, where, site);
            case ArrayBindReader array -> array(array, target, what, where, site, top);
            case MapBindReader map -> map(map, target, what, where, site, top);
            case TupleBindReader tuple -> tuple(tuple, target, what, where, site, top);
            case RecordBindReader record -> record(record, target, what, where, site);
            default -> reader;
        };
    }

    /**
     * The uninterpreted {@code value} atom is read as the class the target holds, under the field's name, since
     * the entry's own names the escape hatch rather than anything the author wrote. A position that reads the
     * token itself is already specialised and stays as it is. Every other atom is bound to the target's wire
     * class -- never the declared one, since a bridged class is reached by whatever its bridge takes.
     */
    private static TsonTypeReader<?> atom(AtomTypeReader<?> atom, DataClass target, String what, String where,
                                          Site site) {
        if (atom.readsUninterpretedValue()) {
            return atom.overAtom(site.field(), ValueParser.at(target.dataClass()));
        }
        if (atom.readsTheTokenItself()) {
            return atom;
        }
        if (!(target instanceof DataClassAtom bound)) {
            site.mismatches().add(what + " is an atom, and " + where + " binds " + target.typeClass().getName()
                    + " structurally -- register it as an atom (DataBindContext.Builder.registerAtom, or "
                    + "@Transparent on a single-component record) so that the value it produces has a way to reach "
                    + "it");
            return atom;
        }
        Optional<TsonTypeReader<?>> atWire = atom.boundTo(bound.dataClass());
        if (atWire.isEmpty()) {
            site.mismatches().add(what + " cannot produce " + bound.dataClass().getName() + ", which is what "
                    + where + " binds");
            return atom;
        }
        return atWire.get();
    }

    private static TsonTypeReader<?> array(ArrayBindReader array, DataClass target, String what, String where,
                                           Site site, boolean top) {
        if (!(target instanceof DataClassArray collection)) {
            site.mismatches().add(what + " is an array, and " + where + " binds " + target.typeClass().getName());
            return array;
        }
        if (array.body.voidable() && collection.typeClass().isArray()
                && collection.arrayDataClass().typeClass().isPrimitive()) {
            site.mismatches().add(what + " admits void elements, and " + where + " binds "
                    + collection.typeClass().getSimpleName() + ", which has no null to hold one");
        }
        String targetName = collection.typeClass().getSimpleName();
        TsonTypeReader<?> element = to(site.resolver().resolve(array.body.elementType().name()),
                collection.arrayDataClass(), what + "'s element", targetName + "'s element", site, false);
        return new ArrayBindReader(top ? site.field() : array.name, top ? site.field() : array.displayName,
                array.body, collection, element, array.schemaLocation, array.elementsAreNames);
    }

    private static TsonTypeReader<?> map(MapBindReader map, DataClass target, String what, String where, Site site,
                                         boolean top) {
        if (!(target instanceof DataClassMap keyed)) {
            site.mismatches().add(what + " is a map, and " + where + " binds " + target.typeClass().getName());
            return map;
        }
        if (map.body.ordered() && !keyed.ordered()) {
            site.mismatches().add(what + " is an ordered map, and " + where + " binds "
                    + keyed.typeClass().getName() + ", which does not keep insertion order -- declare a SequencedMap");
        }
        String targetName = keyed.typeClass().getSimpleName();
        TsonTypeReader<?> key = to(site.resolver().resolve(map.body.keyType().name()), keyed.keyDataClass(),
                what + "'s key", targetName + "'s key", site, false);
        TsonTypeReader<?> value = to(site.resolver().resolve(map.body.valueType().name()), keyed.valueDataClass(),
                what + "'s value", targetName + "'s value", site, false);
        return new MapBindReader(top ? site.field() : map.name, top ? site.field() : map.displayName, map.body,
                keyed, key, value, map.schemaLocation, map.keysAreNames);
    }

    /**
     * A tuple written inline has a minted entry no author binds, so its own reader is unbound
     * ({@link TupleBindReader#unbound}) and the target's {@code @Tuple} class is the only one there is.
     */
    private static TsonTypeReader<?> tuple(TupleBindReader tuple, DataClass target, String what, String where,
                                           Site site, boolean top) {
        if (!(target instanceof DataClassTuple positional)) {
            site.mismatches().add(what + " is a tuple, and " + where + " binds " + target.typeClass().getName()
                    + ", which is not a tuple class (@Tuple)");
            return tuple;
        }
        List<String> disagreements = TupleBindReader.disagreements(tuple.body, positional);
        for (String disagreement : disagreements) {
            site.mismatches().add(what + " is a tuple, and " + where + " binds " + positional.typeClass().getName()
                    + ": " + disagreement);
        }
        if (positional.elements().length != tuple.body.elements().size()) {
            return tuple;
        }
        String targetName = positional.typeClass().getSimpleName();
        List<TsonTypeReader<?>> positions = new ArrayList<>(tuple.body.elements().size());
        for (int i = 0; i < tuple.body.elements().size(); i++) {
            TupleElement element = tuple.body.elements().get(i);
            positions.add(to(site.resolver().resolve(element.elementType().name()),
                    positional.elements()[i].dataClass(), what + "'s position " + i,
                    targetName + "'s element " + i, site, false));
        }
        return new TupleBindReader(top ? site.field() : tuple.name, top ? site.field() : tuple.displayName,
                tuple.body, positional, positions, tuple.schemaLocation);
    }

    /** A record is bound by its own schema type's name, so the class it builds has to be one the target holds. */
    private static TsonTypeReader<?> record(RecordBindReader record, DataClass target, String what, String where,
                                            Site site) {
        Class<?> built = record.boundClass();
        boolean held = target.typeClass().isAssignableFrom(built)
                || target.bridge().isPresent() && target.dataClass().isAssignableFrom(built);
        if (!held) {
            site.mismatches().add(what + " binds " + built.getName() + ", which " + where + " ("
                    + target.typeClass().getName() + ") cannot hold");
        }
        return record;
    }
}
