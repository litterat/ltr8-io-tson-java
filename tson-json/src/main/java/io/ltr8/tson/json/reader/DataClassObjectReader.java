package io.ltr8.tson.json.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.bind.DataClassElement;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassTuple;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.json.JsonBindException;
import io.ltr8.tson.json.JsonPosition;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Binds one JSON value at one {@link DataClass}, driven by the target class's own {@code tson-bind}
 * descriptor -- the engine {@code JsonObjectReader} is a facade over.
 *
 * <p><b>Named for what drives it and what it produces</b>, which is the pair every reader in this family
 * is named for: a {@code DataClass} descriptor drives it, and an object comes out. The eventual tree
 * reader is a {@code JsonTreeReader} over a tree engine, and the schema-directed decode of §5-§8 is a
 * third engine under this same facade rather than a second front door -- so the two axes have to stay in
 * the name or the family stops scaling.
 *
 * <p>{@code tson-compiler}'s peer is {@code SchemalessObjectReader}, and this deliberately does not copy
 * that name: the class <em>is</em> the schema here, as that class's own Javadoc says of its own target
 * ("in effect the schema the data must satisfy"), so "schemaless" describes the one thing this reader is
 * not short of. It is accurate of a <em>tree</em> reader, which really is driven by nothing.
 *
 * <p><b>Frame-free.</b> Whole-document framing -- draining the source through
 * {@link JsonEvent.EndOfDocument}, which is what rejects trailing content -- belongs to whoever owns the
 * document, which is the facade. This binds a value and stops, so it composes: a caller with a source
 * positioned mid-document gets one value out of it.
 *
 * <p>Unexported, on the same terms as {@code tson-compiler}'s {@code reader} package: a consumer names the
 * facade, never the engine under it.
 */
public final class DataClassObjectReader {

    private final DataBindContext context;

    /** Whether a member the target class does not declare is discarded rather than refused -- see {@code JsonObjectReader.ignoringUnknownMembers}. */
    private final boolean ignoreUnknownMembers;

    public DataClassObjectReader(DataBindContext context, boolean ignoreUnknownMembers) {
        this.context = context;
        this.ignoreUnknownMembers = ignoreUnknownMembers;
    }

    /**
     * Binds one value at {@code events}' current position into {@code targetClass}, consuming exactly that
     * value's events and no more.
     *
     * <p>The general form, for a caller managing their own source. The facade's whole-document entry points
     * are this plus framing.
     */
    public <T> T read(JsonEventSource events, Class<T> targetClass) {
        return targetClass.cast(bind(events, events.next(), descriptorFor(targetClass, null)));
    }

    // ── The dispatch ─────────────────────────────────────────────────────

    /** One value at one descriptor, {@code first} being its opening event, already pulled. */
    private Object bind(JsonEventSource events, JsonEvent first, DataClass target) {
        if (first instanceof JsonEvent.NullValue && !(target instanceof DataClassAtom)) {
            // A container or record component written null: absence, which the caller's own state rule
            // has already admitted -- a required one never reaches here (see bindMember/element).
            return null;
        }
        return switch (target) {
            case DataClassAtom atom -> JsonAtoms.bind(first, atom, first.position());
            case DataClassRecord record -> bindRecord(events, first, record);
            case DataClassArray array -> bindArray(events, first, array);
            case DataClassMap map -> bindMap(events, first, map);
            case DataClassTuple tuple -> bindTuple(events, first, tuple);
            case DataClassAnnotated annotated -> bindAnnotated(events, first, annotated);
            case DataClassUnion union -> throw unions(union, first.position());
            default -> throw new JsonBindException(
                    "no rule for reading " + target.getClass().getSimpleName(), first.position());
        };
    }

    /**
     * §8's dispatch has no schemaless form, and refusing is the honest answer rather than inventing one.
     *
     * <p>§8.2 lets a choice go untagged by exactly two routes and forbids extending them: a declared
     * discriminator, or a disjoint choice whose variants are class-stable. Both are facts a <em>schema</em>
     * derives, and a Java union declares neither — so there is nothing here to dispatch on, and trying
     * each member in turn is what §8.2 rules out in as many words. The TSON side reaches this case
     * through {@code !variant}, which JSON has no counterpart for outside §3.3's annotation object, and
     * that object is meaningful only at a typed position §8 recognises.
     */
    private static JsonBindException unions(DataClassUnion union, JsonPosition at) {
        return new JsonBindException(("%s is a union, and a JSON document carries no selector for one "
                + "without a schema -- [TSON-JSON] §8.2 admits a tag-free choice only where a declared "
                + "discriminator or a derived disjointness fact picks the variant, and neither is "
                + "something a Java class states. Read the members' own types, or use the "
                + "schema-directed decode when it lands.").formatted(union.typeClass().getSimpleName()), at);
    }

    /**
     * {@code type}'s descriptor, or a failure naming the class and the reason under it.
     *
     * <p>The cause is carried and unwrapped into the message: {@code DataBindException}'s own text for an
     * unresolvable class is "Failed to resolve", which names neither the class nor what stopped it, and a
     * caller reading that alone has no next step.
     */
    private DataClass descriptorFor(Class<?> type, JsonPosition at) {
        try {
            return context.getDescriptor(type);
        } catch (DataBindException e) {
            Throwable reason = e.getCause() != null ? e.getCause() : e;
            JsonBindException failure = new JsonBindException(
                    "%s cannot be bound: %s".formatted(type.getName(), reason), at);
            failure.initCause(e);
            throw failure;
        }
    }

    // ── Records ──────────────────────────────────────────────────────────

    private Object bindRecord(JsonEventSource events, JsonEvent first, DataClassRecord target) {
        if (!(first instanceof JsonEvent.ObjectStart)) {
            throw wrongShape(first, "an object", target.typeClass());
        }
        DataClassField[] fields = target.fields();
        DataClassField carrier = target.annotationsCarrier().orElse(null);

        Map<String, Integer> indexByName = new HashMap<>(fields.length);
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != carrier) {
                indexByName.put(fields[i].name(), i);
            }
        }

        Object[] construct = new Object[fields.length];
        boolean[] seen = new boolean[fields.length];
        if (carrier != null) {
            // §4.3: the JSON encoding carries no annotations, so there is nothing to capture and nothing
            // dropped. The component is filled and marked seen so the required-field pass skips it.
            construct[carrier.index()] = Annotations.empty();
            seen[indexOf(fields, carrier)] = true;
        }

        // §3.1's duplicate rule, tracked by written name rather than by target slot: a name the class does
        // not declare is still stated twice, and this is a read at a *record* position, where §3.1 makes
        // the repeat a resolver error rather than something a key type relates.
        Set<String> stated = new HashSet<>();

        while (true) {
            JsonEvent event = events.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                break;
            }
            JsonEvent.MemberName member = (JsonEvent.MemberName) event;
            if (!stated.add(member.name())) {
                throw new JsonBindException("'%s' is already a member of this object, and a member name "
                        .formatted(member.name()) + "appears once", member.position());
            }
            Integer index = indexByName.get(member.name());
            if (index == null) {
                if (!ignoreUnknownMembers) {
                    throw new JsonBindException("%s declares no member '%s'; it declares (%s)"
                            .formatted(target.typeClass().getSimpleName(), member.name(),
                                    declaredNames(fields, carrier)), member.position());
                }
                skipValue(events, events.next());
                continue;
            }
            DataClassField field = fields[index];
            construct[field.index()] = bindMember(events, field, member);
            seen[index] = true;
        }

        for (int i = 0; i < fields.length; i++) {
            if (!seen[i] && fields[i].isRequired()) {
                throw new JsonBindException("%s requires a member '%s', and this object has none"
                        .formatted(target.typeClass().getSimpleName(), fields[i].name()), first.position());
            }
        }
        return construct(target.constructor(), construct, target.typeClass(), first.position());
    }

    /**
     * One member's value.
     *
     * <p>Where §7 lands: a member written {@code null} at a required component is refused, exactly as
     * {@code _} is at a REQUIRED field in text ([TSON-SCHEMA] §7.6), and at any other component it is the
     * absence — which a bound object spells {@code null}, having no third state.
     */
    private Object bindMember(JsonEventSource events, DataClassField field, JsonEvent.MemberName member) {
        JsonEvent value = events.next();
        if (value instanceof JsonEvent.NullValue && field.isRequired()) {
            throw new JsonBindException("member '%s' is required and is written null, which is the absent "
                    .formatted(field.name()) + "value", value.position());
        }
        return bind(events, value, field.dataClass());
    }

    /** The names this class declares, for a message that has to say what was expected. */
    private static String declaredNames(DataClassField[] fields, DataClassField carrier) {
        StringBuilder names = new StringBuilder();
        for (DataClassField field : fields) {
            if (field == carrier) {
                continue;
            }
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(field.name());
        }
        return names.toString();
    }

    private static int indexOf(DataClassField[] fields, DataClassField wanted) {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == wanted) {
                return i;
            }
        }
        throw new IllegalStateException("the annotations carrier is not one of the record's own fields");
    }

    // ── Arrays, tuples, maps ─────────────────────────────────────────────

    private Object bindArray(JsonEventSource events, JsonEvent first, DataClassArray target) {
        if (!(first instanceof JsonEvent.ArrayStart)) {
            throw wrongShape(first, "an array", target.typeClass());
        }
        // Buffered because the array's own constructor takes a length, which a JSON array does not state
        // until it closes -- the same shape SchemalessObjectReader's array path takes, and the reason a
        // read's memory is proportional to the widest array as well as to the deepest nesting.
        List<Object> buffered = new ArrayList<>();
        while (true) {
            JsonEvent event = events.next();
            if (event instanceof JsonEvent.ArrayEnd) {
                break;
            }
            buffered.add(bind(events, event, target.arrayDataClass()));
        }
        try {
            // constructor(size) -> iterator(array) -> put(array, iterator, element), which is what a
            // collection-backed descriptor needs: an index would only serve a real Java array.
            Object array = target.constructor().invoke(buffered.size());
            Object iterator = target.iterator().invoke(array);
            for (Object element : buffered) {
                target.put().invoke(array, iterator, element);
            }
            return array;
        } catch (Throwable e) {
            throw failed(target.typeClass(), e, first.position());
        }
    }

    /** §6.4: a JSON array of exactly the tuple's length, short or long being an error either way. */
    private Object bindTuple(JsonEventSource events, JsonEvent first, DataClassTuple target) {
        if (!(first instanceof JsonEvent.ArrayStart)) {
            throw wrongShape(first, "an array", target.typeClass());
        }
        DataClassElement[] slots = target.elements();
        Object[] construct = new Object[slots.length];
        int index = 0;
        while (true) {
            JsonEvent event = events.next();
            if (event instanceof JsonEvent.ArrayEnd) {
                break;
            }
            if (index >= slots.length) {
                throw new JsonBindException("%s takes %d element%s and this array has more"
                        .formatted(target.typeClass().getSimpleName(), slots.length, slots.length == 1 ? "" : "s"),
                        event.position());
            }
            construct[index] = bind(events, event, slots[index].dataClass());
            index++;
        }
        if (index < slots.length) {
            throw new JsonBindException("%s takes %d element%s and this array has %d"
                    .formatted(target.typeClass().getSimpleName(), slots.length, slots.length == 1 ? "" : "s", index),
                    first.position());
        }
        return construct(target.constructor(), construct, target.typeClass(), first.position());
    }

    /**
     * §6.5's object form: each member name is a key token, its content read by the key type's own
     * contract rather than taken as text.
     *
     * <p>The pairs form is not read here. §6.5 selects it by the key type, and a key type this reader can
     * reach is one a JSON member name can spell — so the form a compound key would need never arises from
     * a class whose keys this reader can bind at all, and inventing it would be a rule with no caller.
     */
    private Object bindMap(JsonEventSource events, JsonEvent first, DataClassMap target) {
        if (!(first instanceof JsonEvent.ObjectStart)) {
            throw wrongShape(first, "an object", target.typeClass());
        }
        if (!(target.keyDataClass() instanceof DataClassAtom key)) {
            throw new JsonBindException(("a JSON object's member names are the map's keys, so %s's key type "
                    + "must be one a name can spell -- §6.5's pairs form, which carries a compound key, is "
                    + "not read here").formatted(target.typeClass().getSimpleName()), first.position());
        }
        Set<String> stated = new HashSet<>();
        try {
            Object map = target.constructor().invoke(0);
            while (true) {
                JsonEvent event = events.next();
                if (event instanceof JsonEvent.ObjectEnd) {
                    return map;
                }
                JsonEvent.MemberName member = (JsonEvent.MemberName) event;
                if (!stated.add(member.name())) {
                    throw new JsonBindException("'%s' is already a key of this map, and a key appears once"
                            .formatted(member.name()), member.position());
                }
                Object boundKey = JsonAtoms.bind(
                        new JsonEvent.StringValue(member.name(), member.position()), key, member.position());
                target.put().invoke(map, boundKey, bind(events, events.next(), target.valueDataClass()));
            }
        } catch (JsonBindException e) {
            throw e;
        } catch (Throwable e) {
            throw failed(target.typeClass(), e, first.position());
        }
    }

    // ── Annotations carrier ──────────────────────────────────────────────

    /** §4.3: no annotations reach the JSON wire, so the wrapper's own value binds and the carrier is empty. */
    private Object bindAnnotated(JsonEventSource events, JsonEvent first, DataClassAnnotated target) {
        Object value = bind(events, first, target.valueClass());
        try {
            return target.constructor().invoke(value, Annotations.empty());
        } catch (Throwable e) {
            throw failed(target.typeClass(), e, first.position());
        }
    }

    // ── Skipping, construction, errors ───────────────────────────────────

    /**
     * Discards one value, {@code first} already pulled — a member the target class does not declare.
     *
     * <p>Iterative over its own depth rather than recursive: this walks values nothing keeps, so it is
     * the one place a document's nesting would cost stack for no result. §10.1's bound has already
     * refused anything deeper than the reader reads, but a skip that recursed would be spending the
     * stack twice for a value being thrown away.
     */
    private static void skipValue(JsonEventSource events, JsonEvent first) {
        int depth = first instanceof JsonEvent.ObjectStart || first instanceof JsonEvent.ArrayStart ? 1 : 0;
        while (depth > 0) {
            JsonEvent event = events.next();
            if (event instanceof JsonEvent.ObjectStart || event instanceof JsonEvent.ArrayStart) {
                depth++;
            } else if (event instanceof JsonEvent.ObjectEnd || event instanceof JsonEvent.ArrayEnd) {
                depth--;
            }
        }
    }

    private Object construct(MethodHandle constructor, Object[] arguments, Class<?> type, JsonPosition at) {
        try {
            return constructor.invoke(arguments);
        } catch (Throwable e) {
            throw failed(type, e, at);
        }
    }

    private static JsonBindException wrongShape(JsonEvent found, String expected, Class<?> type) {
        return new JsonBindException("%s takes %s, and this is %s"
                .formatted(type.getSimpleName(), expected, JsonAtoms.describe(found)), found.position());
    }

    /**
     * A constructor or collection call that threw — the class's own rule refusing the value, most often,
     * which is a fact about this document and not a fault in this library.
     */
    private static JsonBindException failed(Class<?> type, Throwable cause, JsonPosition at) {
        JsonBindException failure = new JsonBindException(
                "%s rejected the value read for it: %s".formatted(type.getSimpleName(), cause), at);
        failure.initCause(cause);
        return failure;
    }
}
