package io.ltr8.tson.json;

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
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;
import io.ltr8.tson.json.stream.JsonStream;

import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a JSON document straight into a Java object, driven by the target class's own
 * {@code tson-bind} descriptor.
 *
 * <p><b>The class is the schema.</b> [TSON-JSON] §4.1 reads every JSON value at a typed position and
 * never by inspecting the value twice; here the type comes from a {@link DataClass} rather than from a
 * resolved TSON schema, and every rule below follows from that one substitution. An object at a
 * {@link DataClassRecord} is a record and at a {@link DataClassMap} is a map — the brace question JSON
 * cannot answer syntactically, answered by the position, which is the reading §4.1 requires and the one
 * a shared event vocabulary could not have expressed.
 *
 * <p>Binding is JEP 540's other explicit non-goal, after streaming, and it is the one worth not
 * inheriting: a library whose point is validated typed data has no business handing back a tree and
 * calling it done. This is the JSON peer of {@code tson-compiler}'s {@code SchemalessObjectReader},
 * which does the same job against the TSON event stream, and it sits beside {@link Json} for the
 * same reason {@code TsonObjectReader} sits beside {@code Tson}: a reader is a front door, not a
 * layer of one.
 *
 * <p><b>Streams the events, never a tree.</b> Memory held is proportional to nesting depth rather than
 * to document size, and {@link JsonStream}'s §10.1 bound refuses a document before this descends into
 * it.
 *
 * <p><b>Fail-fast.</b> The first disagreement between document and class is a {@link
 * JsonBindException}; there is no collecting mode here. Collecting belongs with the schema-directed
 * decode of §5–§8, where §9.4's four categories exist to sort what is collected — a receiver over this
 * reader would be a list of one shape, and the wrong shape at that: a class that does not match is a
 * misconfiguration in the reading application, not a verdict on the document.
 *
 * <p><b>A member the class does not declare refuses the document.</b> This is the one place the reader is
 * stricter than a JSON consumer expects, and the reason is not tidiness: <b>a member added in a later
 * version can change what the members this class does read mean</b> — a {@code currency} beside an
 * {@code amount}, a {@code unit} beside a {@code quantity}, an {@code encoding} beside a
 * {@code payload}. A reader that drops it has not read a subset of the document; it has read a different
 * document and cannot tell. The class is the schema here, and a closed reading is what makes that claim
 * mean anything — it is also what [TSON-SCHEMA] §7.2 already says of a record under a real schema, so
 * the two paths agree. {@link #ignoringUnknownMembers} is the opt-out.
 *
 * <p>The cost is real and is worth stating: a converted JSON Schema whose {@code additionalProperties}
 * defaults to true describes documents this reader refuses. That is §6.2's rest field's job to fix, and
 * it fixes it properly by <em>keeping</em> the extra members rather than by ignoring them — which is the
 * difference between a document read wholly and one read partly, and why the schema-directed decode is
 * where the accommodation belongs rather than here.
 *
 * <p><b>What this reader is not.</b> It is not validation against a TSON schema, and it is not §1.3
 * principle 1's schema-directed decode: nothing here consults facets, field states, defaults, fixed
 * values, groups or the discrimination predicate, because a Java class declares none of them. It is the
 * ergonomic read for a caller who has a class and a document, and the honest name for what it checks is
 * "does this document fit this class".
 *
 * <h2>The rules that are §7-shaped, and why they are</h2>
 *
 * JSON null is bound the way §7 binds it, as far as a class can express §7: a component the class marks
 * required refuses it, one that does not takes {@code null}. That is the same treatment
 * {@code SchemalessObjectReader} gives the absent sentinel {@code _}, which is the point — §7 makes the
 * two spellings one concept, and a bound object has no third state to tell "omitted" from "present and
 * null" (the asymmetry {@code TsonAbsent} exists for on the tree side). An omitted member and a null
 * member are therefore indistinguishable in the result, which §6.1.2 says outright.
 *
 * <p>Annotations do not exist on the JSON wire (§4.3), so a class declaring an {@link Annotations}
 * carrier gets an empty one. Nothing is dropped: there was nothing to drop.
 */
public final class JsonObjectReader {

    private final DataBindContext context;

    /** Whether a member the target class does not declare is discarded rather than refused -- see {@link #ignoringUnknownMembers}. */
    private final boolean ignoreUnknownMembers;

    private JsonObjectReader(DataBindContext context, boolean ignoreUnknownMembers) {
        this.context = context;
        this.ignoreUnknownMembers = ignoreUnknownMembers;
    }

    /** Over a caller's own bind context — one per binding profile, descriptors cached inside it. */
    public static JsonObjectReader using(DataBindContext context) {
        return new JsonObjectReader(context, false);
    }

    /**
     * Over a default context, which binds records, arrays, maps, tuples and the primitive and boxed
     * atoms. A caller wanting TSON's atom vocabulary ({@code UUID}, the temporal families) builds a
     * context registering them and uses {@link #using}.
     */
    public static JsonObjectReader standard() {
        return new JsonObjectReader(DataBindContext.builder().build(), false);
    }

    /**
     * This reader, discarding a member the target class does not declare instead of refusing the document.
     *
     * <p>The opt-out from the default, which is to refuse — see the class Javadoc for why refusing is the
     * default. For a caller genuinely reading a document wider than the class they bind it to, and content
     * that what they cannot see does not change what they can. Deliberately the derived reader rather than
     * the default: the safe reading is the one nobody has to know to ask for.
     *
     * <p>It is also, today, the closest thing this module has to §6.2's rest field — with the difference
     * that a rest field <em>keeps</em> what it collects, where this drops it. A schema is what tells the
     * two apart, and the schema-directed decode is where the keeping form arrives.
     */
    public JsonObjectReader ignoringUnknownMembers() {
        return new JsonObjectReader(context, true);
    }

    // ── Reading ──────────────────────────────────────────────────────────

    public <T> T read(String source, Class<T> type) {
        return read(new JsonStream(source), type);
    }

    public <T> T read(String source, Class<T> type, int maxDepth) {
        return read(new JsonStream(source, maxDepth), type);
    }

    /** Off UTF-8 bytes, where §3.1's rules bite. {@code source} is not closed here. */
    public <T> T read(InputStream source, Class<T> type) {
        return read(new JsonStream(source), type);
    }

    /** {@link #read(InputStream, Class)} under a nesting bound other than {@link JsonStream#DEFAULT_MAX_DEPTH}. */
    public <T> T read(InputStream source, Class<T> type, int maxDepth) {
        return read(new JsonStream(source, maxDepth), type);
    }

    /**
     * Off an event source, which is the seam the two families above come through.
     *
     * <p>The source is drained through {@link JsonEvent.EndOfDocument} — the pull past the root value is
     * what rejects trailing content, so a read that stopped at the object's closing brace would accept
     * {@code "{} junk"}.
     */
    public <T> T read(JsonEventSource events, Class<T> type) {
        Object value = bind(events, events.next(), descriptorFor(type, null));
        events.next(); // EndOfDocument, or the stream refuses what follows the root value
        return type.cast(value);
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
