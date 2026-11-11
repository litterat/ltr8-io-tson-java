package io.ltr8.tson.compiler.reader;

import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.FieldTypeExpr;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Ref;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TupleElement;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
 *       into a {@code long}. A target that binds structurally cannot hold an atom.</li>
 *   <li><b>An array, map or tuple</b> is read again for the target's own class, each element, key, value or
 *       position bound to the target's in turn -- so {@code List<Long>}, {@code long[]}, {@code List<int[]>}
 *       and {@code Map<String, SequencedMap<String, Long>>} each receive what they declare. A void element
 *       cannot reach a primitive array, an ordered map needs a map that keeps insertion order, and a tuple
 *       class needs the tuple's arity and somewhere for each voidable position's void value.</li>
 *   <li><b>A namespace type's map at a {@link Namespace} target</b> -- a value of a schema's {@code api =>
 *       !meta.namespace { ... }} -- is read for the target's {@code entries} and delivered as an instance of the constructor
 *       ({@link NamespaceInstanceReader}). A map that is no namespace has no constructor to name and cannot fill
 *       one; a {@code Map} target at a namespace type receives the map.</li>
 *   <li><b>A record</b> is bound by its schema type's own name, so its class is checked against the target and
 *       nothing is rebuilt. A record written in place has no name to bind by, and is read into the target's own
 *       record class ({@link RecordInPlaceBindReader}).</li>
 *   <li><b>A name at a reference- or kind-typed slot</b> is recorded with the slot's declared type for the linker
 * to judge, whatever the target holds. A {@code Reference} target reads as a {@code TypeRef} one does, a type reference
 * being a reference, and admits a sibling reference besides, which a {@code TypeRef} target refuses at its token, naming the
 * slot ([TSON-SCHEMA] §5.2's position rule). A {@code Ref} or {@code TypeRef} target at a reference-typed slot ({@code
 * meta.ref<T>}, [TSON-SCHEMA] §5.12) reads through a {@link ReferenceNameReader}: a {@code Ref} receives the value the
 * name reaches -- when the schema links, for a name a schema document writes, and at the read for one in a data
 * document -- and a {@code TypeRef} the name, carrying that value as its referent in a data document. A {@code
 * TypeRef} target at a kind-typed slot ({@code meta.record_field.type} is {@code meta.type_ref}, a meta's {@code
 * request: meta.ref<record>}) reads through a {@link TypeNameSlotReader}: a type's name, or in a kernel body an
 * application still to close, written unquoted. Any other target there is bound as at an atom and wrapped in a {@link
 * RecordedNameReader}, which records the name it read. The slot declares the spelling, so a {@code TypeRef} or
 * {@code Ref} target is a mismatch at a record-typed slot, which holds the record's value inline, and at any other
 * atom, where a token is a literal and never a reference.</li>
 * </ul>
 * A dependent field ({@link DependentFieldReader}) binds to an {@code Object} component, which receives what the
 * type its sibling names reads to, and to nothing narrower.
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

    /**
     * What every rebuild at one record field shares: the field's name, the schema's readers, the mismatches, and the
     * entry a name reaches past its alias hops ([TSON-SCHEMA] §8.3).
     */
    record Site(String field, TsonTypeReaderResolver resolver, AnnotationTypes annotationTypes,
                List<String> mismatches, UnaryOperator<String> terminal) {
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
        if (reader instanceof DependentFieldReader) {
            // A dependent field's value is of whichever type its sibling names in each instance, so the component
            // holds what that type's reader builds: an Object, the one class every such value is.
            if (payload.typeClass() != Object.class) {
                site.mismatches().add(what + " is a dependent field, whose type its sibling's value names per "
                        + "instance, and " + where + " holds a " + payload.typeClass().getSimpleName()
                        + " -- declare it Object, which receives what the named type reads to");
            }
            return reader;
        }
        TsonTypeReader<?> bound;
        // A component holding what a type slot holds -- a name, or a name or a type written in place -- reads a name
        // at a kind as a TypeRef component does; a type written in place reaches it through the choice that types
        // the slot (meta.type_expr), whose own reader this leaves as it is.
        boolean typeRef = payload.typeClass() == TypeRef.class || payload.typeClass() == Reference.class
                || payload.typeClass() == TypeExpr.class || payload.typeClass() == FieldTypeExpr.class;
        boolean byName = typeRef || payload.typeClass() == Ref.class;
        boolean slot = payload.typeClass() == TypeExpr.class || payload.typeClass() == FieldTypeExpr.class;
        if (slot && RecordAbstractReader.unwrapped(reader) instanceof NamedDispatchReader choice
                && choice.recovers(DiscriminationClass.STRING)) {
            // A type slot's choice (meta.type_expr): a name at it is read as a TypeRef component reads one, so the
            // annotations written on the name are kept; a type written in place is read by the choice's own variant,
            // under the constructor its body is tagged with, which no field of the wrapper holds.
            TsonTypeReader<?> named = site.resolver().resolve(choice.recoveredTo(DiscriminationClass.STRING));
            if (readsAKind(named)) {
                Map<DiscriminationClass, TsonTypeReader<?>> readers = new EnumMap<>(DiscriminationClass.class);
                readers.put(DiscriminationClass.STRING, new TypeNameSlotReader(slotAtom(named),
                        site.annotationTypes(), what, payload.typeClass() == FieldTypeExpr.class));
                if (choice.recovers(DiscriminationClass.BRACE)) {
                    readers.put(DiscriminationClass.BRACE, new TypeInlineReader(
                            site.resolver().resolve(choice.recoveredTo(DiscriminationClass.BRACE)), site.terminal()));
                }
                return AnnotationBoxing.wrap(choice.recoveringWith(readers), target, site.annotationTypes());
            }
        }
        if (byName && readsARef(reader)) {
            // The name reader produces the host value itself -- the Ref the linker will deliver into, or the
            // TypeRef -- so the atom's own bridge (Ref's name form) is not applied on top of it.
            AtomTypeReader<?> atom = slotAtom(reader);
            TsonTypeReader<?> named = new ReferenceNameReader(expectedAt(reader, what), payload.typeClass() == Ref.class,
                    atom.slotType(), atom.boundTo(payload.typeClass()).orElseThrow(() -> new IllegalStateException(
                            what + " reads a reference, whose atom binds no " + payload.typeClass().getSimpleName())));
            return AnnotationBoxing.wrap(named, target, site.annotationTypes());
        } else if (typeRef && readsAKind(reader)) {
            // The same for a type's name or an application: the reader produces the TypeRef itself, so the atom's
            // own bridge (the reference's text form) is not applied on top of it.
            boolean siblingHeld = payload.typeClass() == Reference.class || payload.typeClass() == FieldTypeExpr.class;
            return AnnotationBoxing.wrap(new TypeNameSlotReader(slotAtom(reader), site.annotationTypes(), what,
                    siblingHeld), target, site.annotationTypes());
        } else if (byName && (recordHeldByName(reader) || readsAnAtom(reader))) {
            site.mismatches().add(what + " is not a reference, and " + where + " holds a "
                    + payload.typeClass().getSimpleName() + " -- a value stands by name only at a position typed "
                    + "meta.ref<T> (and a type's name at one typed by a kind); a position typed by a record holds the "
                    + "record's value inline, so declare that record's class");
            bound = reader;
        } else {
            bound = shaped(reader, payload, what, where, site, top);
            if (readsAKind(reader) || readsARef(reader)) {
                // A name at a kind or reference is judged whatever the host holds: the host receives its value,
                // and the name is recorded as a TypeRef host's would be.
                AtomTypeReader<?> atom = slotAtom(reader);
                TsonTypeReader<?> refusing = readsARef(reader)
                        ? new ReferenceNameReader(expectedAt(reader, what), false, atom.slotType(), bound)
                        : new TypeNameSlotReader(atom, site.annotationTypes(), what, false);
                bound = new RecordedNameReader(bound, atom.slotType(), refusing);
            }
        }
        return AnnotationBoxing.wrap(ElementBridging.wrap(bound, payload), target, site.annotationTypes());
    }

    /**
     * The host type a natural reader guesses for what a slot holds: a name's bound class ({@code named}), and for a
     * type written in place, which has no bound class of its own, the shape its constructor reads to -- a {@code List}
     * of its element's host type for an array, a {@code Map} of its key's and value's for a map, and for a tuple, a
     * choice or a reference whatever {@code named} gives a name it has no binding for -- what the host binds there is
     * declared by the component holding it, which rebinds the reader ({@link #to}).
     */
    static java.lang.reflect.Type hostType(TypeExpr type, java.util.function.Function<String, Class<?>> named) {
        return switch (type) {
            case TypeRef reference -> named.apply(reference.name());
            case TypeInline inline -> switch (inline.of()) {
                case ArrayBody array -> new io.ltr8.bind.DataParameterizedType(List.class,
                        hostType(array.elementType(), named));
                case MapBody map -> new io.ltr8.bind.DataParameterizedType(
                        map.ordered() ? java.util.LinkedHashMap.class : java.util.Map.class,
                        named.apply(map.keyType().name()), hostType(map.valueType(), named));
                // Reached by an array or map holding a tuple, a choice, a reference or a record in place, read
                // naturally. Nothing binds a type in place by name, so asking for its form lands on `named`'s
                // answer for an unbound name; the component holding it declares the class it binds.
                default -> named.apply(inline.text());
            };
        };
    }

    /** Whether {@code reader} reads a reference ({@code meta.ref<T>}): a token there is a value's qualified name. */
    private static boolean readsARef(TsonTypeReader<?> reader) {
        return RecordAbstractReader.unwrapped(reader) instanceof AtomTypeReader<?> atom && atom.readsARef();
    }

    /**
     * Whether {@code reader} reads a record position a {@code TypeRef} or {@code Ref} target cannot hold: a record
     * whose bare token is not positional, or a record position this compile cannot see into (a dispatcher, an
     * unbuildable type).
     */
    private static boolean recordHeldByName(TsonTypeReader<?> reader) {
        return switch (RecordAbstractReader.unwrapped(reader)) {
            case RecordAbstractReader<?> record -> !record.bareTokenFillsPositionally();
            case RecordInPlaceBindReader ignored -> true;
            case ErrorReader ignored -> true;
            case RecordTagDispatchReader ignored -> true;
            case RecordMemberDispatchReader ignored -> true;
            case null, default -> false;
        };
    }

    /** The kind or reference atom {@code reader} reads, through any wrapper. */
    private static AtomTypeReader<?> slotAtom(TsonTypeReader<?> reader) {
        return (AtomTypeReader<?>) RecordAbstractReader.unwrapped(reader);
    }

    /** Whether {@code reader} reads a kind: a token there is a type's name. */
    private static boolean readsAKind(TsonTypeReader<?> reader) {
        return RecordAbstractReader.unwrapped(reader) instanceof AtomTypeReader<?> atom && atom.readsAKind();
    }

    /** Whether {@code reader} reads an atom: a token there is a literal, which no reference can hold. */
    private static boolean readsAnAtom(TsonTypeReader<?> reader) {
        return RecordAbstractReader.unwrapped(reader) instanceof AtomTypeReader<?>
                || RecordAbstractReader.unwrapped(reader) instanceof VoidReader;
    }

    /** What a position holding a reference by name expects, as its refusal says it: {@code a 'method' value}. */
    private static String expectedAt(TsonTypeReader<?> reader, String what) {
        return switch (RecordAbstractReader.unwrapped(reader)) {
            case RecordAbstractReader<?> record -> "a '" + record.displayName + "' value";
            case AtomTypeReader<?> atom when atom.readsARef() -> "a '" + atom.refOf() + "' value";
            case null, default -> "a value for " + what;
        };
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
            case RecordInPlaceBindReader inPlace -> recordInPlace(inPlace, target, what, where, site);
            default -> reader;
        };
    }

    /**
     * A position that reads the token itself is already specialised and stays as it is. Every other atom is bound to
     * the target's wire class -- never the declared one, since a bridged class is reached by whatever its bridge takes.
     */
    private static TsonTypeReader<?> atom(AtomTypeReader<?> atom, DataClass target, String what, String where,
                                          Site site) {
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
        TsonTypeReader<?> element = to(site.resolver().resolve(array.body.elementType()),
                collection.arrayDataClass(), what + "'s element", targetName + "'s element", site, false);
        return new ArrayBindReader(top ? site.field() : array.name, top ? site.field() : array.displayName,
                array.body, collection, element, array.schemaLocation, array.elementsAreNames);
    }

    private static TsonTypeReader<?> map(MapBindReader map, DataClass target, String what, String where, Site site,
                                         boolean top) {
        if (target.typeClass() == Namespace.class) {
            return namespace(map, target, what, where, site, top);
        }
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
        TsonTypeReader<?> value = to(site.resolver().resolve(map.body.valueType()), keyed.valueDataClass(),
                what + "'s value", targetName + "'s value", site, false);
        return new MapBindReader(top ? site.field() : map.name, top ? site.field() : map.displayName, map.body,
                keyed, key, value, map.schemaLocation, map.keysAreNames, map.namespace);
    }

    /**
     * A namespace type's value at a target holding a {@link Namespace}: the map read for the namespace's own
     * {@code entries}, as an instance of the constructor the position names. A map that is no namespace has no
     * constructor to name, so a {@code Namespace} target there is a mismatch.
     */
    private static TsonTypeReader<?> namespace(MapBindReader map, DataClass target, String what, String where,
                                               Site site, boolean top) {
        if (map.namespace == null) {
            site.mismatches().add(what + " is a map that is no namespace, and " + where + " binds "
                    + Namespace.class.getName() + ", a value of a namespace type -- declare a map");
            return map;
        }
        if (!(target instanceof DataClassRecord record) || !record.fieldIndex().containsKey("entries")) {
            throw new IllegalStateException(Namespace.class.getName() + " binds as " + target
                    + ", which has no 'entries' to read the map into");
        }
        DataClass entries = record.fields()[record.fieldIndex().get("entries")].dataClass();
        return new NamespaceInstanceReader(map.namespace, map(map, entries, what, where, site, top));
    }

    /**
     * A tuple written in place has no name to bind a class to, so its own reader is unbound
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
            positions.add(to(site.resolver().resolve(element.elementType()),
                    positional.elements()[i].dataClass(), what + "'s position " + i,
                    targetName + "'s element " + i, site, false));
        }
        return new TupleBindReader(top ? site.field() : tuple.name, top ? site.field() : tuple.displayName,
                tuple.body, positional, positions, tuple.schemaLocation);
    }

    /**
     * A record written in place is bound by the component that holds it: no binding map can name it ([TSON-SCHEMA]
     * §8.2), so the target's record class is the only one there is, and the record is read into it, each field bound
     * to the class's component as a declared record's is.
     */
    private static TsonTypeReader<?> recordInPlace(RecordInPlaceBindReader inPlace, DataClass target, String what,
                                                   String where, Site site) {
        if (!(target instanceof DataClassRecord record)) {
            site.mismatches().add(what + " is a record written in place, and " + where + " binds "
                    + target.typeClass().getName() + ", which is not a record class -- a record in place binds to "
                    + "the record class its component declares");
            return inPlace;
        }
        return inPlace.boundTo(record);
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
