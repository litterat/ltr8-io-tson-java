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
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.policy.UnicodePolicy;
import io.ltr8.tson.base.unicode.IdentifierProfile;
import io.ltr8.tson.json.JsonPosition;
import io.ltr8.tson.json.atom.JsonAtoms;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>{@code tson-compiler}'s peer carries the same name, because it is the same engine against the other
 * encoding's events. Neither is "schemaless": the class <em>is</em> the schema here, as that peer's own
 * Javadoc says of its own target ("in effect the schema the data must satisfy"). The word stays accurate
 * of a <em>tree</em> reader, which really is driven by nothing, and both encodings keep it there.
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

    /**
     * Whether a member the target class does not declare is discarded rather than refused -- see
     * {@code JsonObjectReader.ignoringUnknownFields} .
     */
    private final boolean ignoreUnknownMembers;

    /**
     * [TSON-DATA] §8.2's <b>identifier</b> policy, which this engine can apply and the tree engine cannot.
     *
     * <p>§4.1 makes a {@code {...}} a record or a map by the <em>position</em>, and JSON spells the two the
     * same way -- so nothing that reads a member name can say whether it read a name or a key. Here the
     * target class answers it, standing in for the schema exactly as it does for every other decision this
     * engine makes: a {@link DataClassRecord} position means the member names are field names, and §2.5
     * makes a field name an identifier at every layer. A {@link DataClassMap} position means they are keys,
     * which are data, and data is the token policy's business rather than this one's.
     *
     * <p>That is why this lives on the engine rather than on {@code JsonReadContext}: the context is shared
     * with the tree engine, which has no position to consult and so would carry a policy it must ignore.
     */
    private final UnicodePolicy identifierPolicy;

    public DataClassObjectReader(DataBindContext context, boolean ignoreUnknownMembers,
                                 UnicodePolicy identifierPolicy) {
        this.context = context;
        this.ignoreUnknownMembers = ignoreUnknownMembers;
        this.identifierPolicy = identifierPolicy;
    }

    /**
     * Binds one value at {@code events}' current position into {@code targetClass}, consuming exactly that
     * value's events and no more.
     *
     * <p>The general form, for a caller managing their own source. The facade's whole-document entry points
     * are this plus framing.
     */
    public <T> T read(JsonEventSource events, Class<T> targetClass, DiagnosticsReceiver receiver) {
        JsonReadContext ctx = JsonReadContext.of(events, receiver);
        DataClass target = descriptorFor(ctx, targetClass);
        if (target == null) {
            skipValue(ctx, ctx.next());
            return null;
        }
        return targetClass.cast(bind(ctx, ctx.next(), target));
    }

    // ── The dispatch ─────────────────────────────────────────────────────

    /** One value at one descriptor, {@code first} being its opening event, already pulled. */
    private Object bind(JsonReadContext ctx, JsonEvent first, DataClass target) {
        if (first instanceof JsonEvent.NullValue && !(target instanceof DataClassAtom)) {
            // A container or record component written null: absence, which the caller's own state rule
            // has already admitted -- a required one never reaches here (see bindMember/element).
            return null;
        }
        return switch (target) {
            case DataClassAtom atom -> JsonAtoms.bind(ctx, first, atom);
            case DataClassRecord record -> bindRecord(ctx, first, record);
            case DataClassArray array -> bindArray(ctx, first, array);
            case DataClassMap map -> bindMap(ctx, first, map);
            case DataClassTuple tuple -> bindTuple(ctx, first, tuple);
            case DataClassAnnotated annotated -> bindAnnotated(ctx, first, annotated);
            case DataClassUnion union -> {
                reportUnion(ctx, union);
                skipValue(ctx, first);
                yield null;
            }
            default -> {
                ctx.report(Diagnostic.Code.NOT_IMPLEMENTED,
                        "no rule for reading " + target.getClass().getSimpleName(),
                        "a shape this reader binds", target.getClass().getSimpleName());
                skipValue(ctx, first);
                yield null;
            }
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
    private static void reportUnion(JsonReadContext ctx, DataClassUnion union) {
        ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, ("%s is a union, and a JSON document carries no selector for one "
                + "without a schema -- [TSON-JSON] §8.2 admits a tag-free choice only where a declared "
                + "discriminator or a derived disjointness fact picks the variant, and neither is "
                + "something a Java class states. Read the members' own types, or use the "
                + "schema-directed decode when it lands.").formatted(union.typeClass().getSimpleName()),
                "a selector for the variant", "a union with none");
    }

    /**
     * {@code type}'s descriptor, or a failure naming the class and the reason under it.
     *
     * <p>The cause is carried and unwrapped into the message: {@code DataBindException}'s own text for an
     * unresolvable class is "Failed to resolve", which names neither the class nor what stopped it, and a
     * caller reading that alone has no next step.
     */
    private DataClass descriptorFor(JsonReadContext ctx, Class<?> type) {
        try {
            return context.getDescriptor(type);
        } catch (DataBindException e) {
            // BIND_MISMATCH, not a verdict: a class this context cannot analyse is a misconfiguration in
            // the reading application, and nothing about the document is being asserted by it.
            // `Code.verdict()` is false for it, which is what a caller routing on the answer needs.
            Throwable reason = e.getCause() != null ? e.getCause() : e;
            ctx.report(Diagnostic.Code.BIND_MISMATCH, "%s cannot be bound: %s".formatted(type.getName(), reason),
                    type.getName(), "(unbindable)");
            return null;
        }
    }

    // ── Records ──────────────────────────────────────────────────────────

    private Object bindRecord(JsonReadContext ctx, JsonEvent first, DataClassRecord target) {
        if (!(first instanceof JsonEvent.ObjectStart)) {
            wrongShape(ctx, first, "an object", target.typeClass());
            skipValue(ctx, first);
            return null;
        }
        // Taken past the framing, matching RecordAbstractReader: what this record built is abandoned if
        // anything under it failed, where the enclosing read's own problems are not this record's business.
        int mark = ctx.reported();
        DataClassField[] fields = target.fields();
        DataClassField carrier = target.annotationsCarrier().orElse(null);

        Map<String, Integer> indexByName = target.fieldIndex();

        Object[] construct = new Object[fields.length];
        boolean[] seen = new boolean[fields.length];
        if (carrier != null) {
            // §4.3: the JSON encoding carries no annotations, so there is nothing to capture and nothing
            // dropped. The component is filled and marked seen so the required-field pass skips it.
            construct[carrier.index()] = Annotations.empty();
            seen[indexOf(fields, carrier)] = true;
        }

        // §3.1's duplicate rule is about the *document*, so it holds for a member the class does not
        // declare as much as for one it does -- but only the latter needs remembering: `seen` already
        // records every declared member that has arrived. So the set below exists for the undeclared ones
        // alone and is built only if one turns up, which for a document matching its class is never.
        Set<String> statedUnknown = null;

        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectEnd) {
                break;
            }
            JsonEvent.MemberName member = (JsonEvent.MemberName) event;
            checkNameHygiene(ctx, member.name());
            Integer index = indexByName.get(member.name());
            if (index == null) {
                if (statedUnknown == null) {
                    statedUnknown = new HashSet<>();
                }
                if (!statedUnknown.add(member.name())) {
                    reportRepeat(ctx, member.name());
                }
                if (!ignoreUnknownMembers) {
                    ctx.field(member.name()).report(Diagnostic.Code.UNRECOGNIZED_FIELD,
                            "%s declares no member '%s'; it declares (%s)"
                                    .formatted(target.typeClass().getSimpleName(), member.name(),
                                            declaredNames(fields, carrier)),
                            declaredNames(fields, carrier), member.name());
                }
                skipValue(ctx, ctx.next());
                continue;
            }
            if (seen[index]) {
                reportRepeat(ctx, member.name());
            }
            DataClassField field = fields[index];
            construct[field.index()] = bindMember(ctx, field);
            seen[index] = true;   // last occurrence wins, reached by overwrite
        }

        for (int i = 0; i < fields.length; i++) {
            if (!seen[i] && fields[i].isRequired()) {
                ctx.field(fields[i].name()).report(Diagnostic.Code.FIELD_REQUIRED,
                        "%s requires a member '%s', and this object has none"
                                .formatted(target.typeClass().getSimpleName(), fields[i].name()),
                        "a value for '" + fields[i].name() + "'", "(absent)");
            }
        }
        if (ctx.reported() > mark) {
            // Bind mode is all-or-nothing: a record whose members failed is not constructed, because a
            // constructor handed nulls for components that never arrived would either throw out of the
            // collecting read or hand back an object nobody wrote. Tree mode keeps what it built; this
            // cannot, having nowhere to put a hole. The same asymmetry ConstructionGuard draws on the TSON
            // side, and for the same reason.
            return null;
        }
        return construct(ctx, target.constructor(), construct, target.typeClass());
    }

    /** §3.1's repeat, reported wherever the member was noticed to be one. */
    private static void reportRepeat(JsonReadContext ctx, String name) {
        ctx.field(name).report(Diagnostic.Code.DUPLICATE_FIELD,
                "'%s' is already a member of this object, and a member name appears once".formatted(name),
                "each member stated once", "'" + name + "' stated again");
    }

    /**
     * One member's value.
     *
     * <p>Where §7 lands: a member written {@code null} at a required component is refused, exactly as
     * {@code _} is at a REQUIRED field in text ([TSON-SCHEMA] §7.6), and at any other component it is the
     * absence — which a bound object spells {@code null}, having no third state.
     */
    private Object bindMember(JsonReadContext ctx, DataClassField field) {
        JsonReadContext at = ctx.field(field.name());
        JsonEvent value = at.next();
        if (value instanceof JsonEvent.NullValue && field.isRequired()) {
            at.report(Diagnostic.Code.FIELD_REQUIRED,
                    "member '%s' is required and is written null, which is the absent value"
                            .formatted(field.name()),
                    "a value for '" + field.name() + "'", "null");
            return null;
        }
        return bind(at, value, field.dataClass());
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

    /**
     * [TSON-DATA] §8.2's <b>restricted-character and restricted-script</b> rules over one record's member
     * names -- the two per-name rules, applied where the position says the names are names.
     *
     * <p><b>Both defaults are on</b>, which §8.2 requires: the identifier profile MUST apply and a name's
     * scripts SHOULD be judged at Highly Restrictive over the whole name. They are one report shape because
     * they are one outcome -- the document is refused, and which table said so is the code's business.
     *
     * <p><b>A refusal is not an invalidity</b> (§8.2, and §9.4 carries the categories into this encoding
     * unchanged): the document is not invalid, it is declined by this processor under a policy reading data
     * the UCD does not freeze, and it MUST NOT be reported in any of §8.1's four categories. That is what
     * the code carries -- {@code RESTRICTED_CHARACTER} and {@code RESTRICTED_SCRIPT}, one per rule, because
     * the two want different fixes. It is still a {@link Diagnostic.Code#verdict()}: the processor looked
     * and declined, and the sender holds the fix, which is the question a consumer routes on.
     *
     * <p><b>The third rule has nowhere here to run.</b> Names that read alike is a property of a
     * <em>set</em>, and {@code tson-compiler} asks it of a record's field names in its schemaless
     * <em>tree</em> reader, where the document's own field set is all there is. Under a class -- as here --
     * the admissible names are declared, so a member that reads alike to a declared one is simply
     * undeclared and already reports {@code UNRECOGNIZED_FIELD}. The TSON bind path draws the line in the
     * same place, and drawing it elsewhere would make this encoding stricter than that one for a rule §8.2
     * states once.
     *
     * <p>Applied to <b>every</b> member name a record position carries, declared or not: §8.2's scope is
     * the names the document wrote, and a name refused for its characters is refused whether or not the
     * class was going to keep the value under it.
     */
    private void checkNameHygiene(JsonReadContext ctx, String name) {
        // Tested rather than `ifPresent`-ed, and measurably so: both rules are allocation-free when a name
        // passes, which is every name of an ordinary document, but a capturing lambda is not -- it captures
        // `ctx` and `name` and so allocates per member name whether or not the Optional holds anything.
        // Two of those per name cost ~140 bytes per bound record in JsonAllocationHarnessTest.

        // The restricted-character rule is gated on the level, per §8.2: Unrestricted "drops the profile
        // too", taking that rule with it. Script mixing gates itself inside violation().
        if (identifierPolicy.appliesIdentifierProfile()) {
            Optional<String> restricted = IdentifierProfile.hygiene(name);
            if (restricted.isPresent()) {
                refuse(ctx, name, restricted.get(), Diagnostic.Code.RESTRICTED_CHARACTER);
            }
        }
        Optional<String> script = identifierPolicy.violation(name);
        if (script.isPresent()) {
            refuse(ctx, name, script.get(), Diagnostic.Code.RESTRICTED_SCRIPT);
        }
    }

    /**
     * One shape for both name-hygiene rules, and the same one {@code DefaultTsonReadContext} uses: refused
     * under this read's <em>name</em> policy, never invalid. The rule is the code -- a character outside the
     * identifier profile and a script the policy does not admit want different fixes.
     */
    private static void refuse(JsonReadContext ctx, String name, String violation, Diagnostic.Code code) {
        ctx.report(code, "the name " + violation, "a name this processor will accept", "'" + name + "'");
    }

    private Object bindArray(JsonReadContext ctx, JsonEvent first, DataClassArray target) {
        if (!(first instanceof JsonEvent.ArrayStart)) {
            wrongShape(ctx, first, "an array", target.typeClass());
            skipValue(ctx, first);
            return null;
        }
        int mark = ctx.reported();
        // Buffered because the array's own constructor takes a length, which a JSON array does not state
        // until it closes -- the same shape DataClassObjectReader's array path takes, and the reason a
        // read's memory is proportional to the widest array as well as to the deepest nesting.
        List<Object> buffered = new ArrayList<>();
        int slot = 0;
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ArrayEnd) {
                break;
            }
            buffered.add(bind(ctx.index(slot), event, target.arrayDataClass()));
            slot++;
        }
        if (ctx.reported() > mark) {
            // Before allocating, not just before returning: a failed element is buffered as null, and
            // storing one into a primitive-component array throws out of `put`'s own MethodHandle.
            return null;
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
            return failed(ctx, target.typeClass(), e);
        }
    }

    /** §6.4: a JSON array of exactly the tuple's length, short or long being an error either way. */
    private Object bindTuple(JsonReadContext ctx, JsonEvent first, DataClassTuple target) {
        if (!(first instanceof JsonEvent.ArrayStart)) {
            wrongShape(ctx, first, "an array", target.typeClass());
            skipValue(ctx, first);
            return null;
        }
        int mark = ctx.reported();
        DataClassElement[] slots = target.elements();
        Object[] construct = new Object[slots.length];
        int index = 0;
        boolean reportedExtra = false;
        while (true) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ArrayEnd) {
                break;
            }
            if (index >= slots.length) {
                if (!reportedExtra) {
                    ctx.report(Diagnostic.Code.WRONG_ARITY, "%s takes %d element%s and this array has more"
                            .formatted(target.typeClass().getSimpleName(), slots.length,
                                    slots.length == 1 ? "" : "s"),
                            slots.length + " elements", "more than " + slots.length);
                    reportedExtra = true;
                }
                skipValue(ctx, event);
                index++;
                continue;
            }
            construct[index] = bind(ctx.index(index), event, slots[index].dataClass());
            index++;
        }
        if (index < slots.length) {
            ctx.report(Diagnostic.Code.WRONG_ARITY, "%s takes %d element%s and this array has %d"
                    .formatted(target.typeClass().getSimpleName(), slots.length,
                            slots.length == 1 ? "" : "s", index),
                    slots.length + " elements", String.valueOf(index));
        }
        if (ctx.reported() > mark) {
            return null;
        }
        return construct(ctx, target.constructor(), construct, target.typeClass());
    }

    /**
     * §6.5's object form: each member name is a key token, its content read by the key type's own
     * contract rather than taken as text.
     *
     * <p>The pairs form is not read here. §6.5 selects it by the key type, and a key type this reader can
     * reach is one a JSON member name can spell — so the form a compound key would need never arises from
     * a class whose keys this reader can bind at all, and inventing it would be a rule with no caller.
     */
    private Object bindMap(JsonReadContext ctx, JsonEvent first, DataClassMap target) {
        if (!(first instanceof JsonEvent.ObjectStart)) {
            wrongShape(ctx, first, "an object", target.typeClass());
            skipValue(ctx, first);
            return null;
        }
        if (!(target.keyDataClass() instanceof DataClassAtom key)) {
            // BIND_MISMATCH, not a verdict: the document is fine and the class cannot receive it.
            ctx.report(Diagnostic.Code.BIND_MISMATCH,
                    ("a JSON object's member names are the map's keys, so %s's key type must be one a name "
                            + "can spell -- §6.5's pairs form, which carries a compound key, is not read here")
                            .formatted(target.typeClass().getSimpleName()),
                    "a key type a member name can spell", target.keyDataClass().typeClass().getSimpleName());
            skipValue(ctx, first);
            return null;
        }
        // No name hygiene here, deliberately: §4.1 makes this position a map, so its member names are
        // *keys*, and a key is data. §8.2's identifier policy governs names; what governs data is the token
        // policy, which JsonStream has already applied to every token including these. It is also what
        // keeps a JSON-Schema conversion from meeting a name rule on `additionalProperties`.
        int mark = ctx.reported();
        Set<String> stated = new HashSet<>();
        try {
            Object map = target.constructor().invoke(0);
            while (true) {
                JsonEvent event = ctx.next();
                if (event instanceof JsonEvent.ObjectEnd) {
                    return ctx.reported() > mark ? null : map;
                }
                JsonEvent.MemberName member = (JsonEvent.MemberName) event;
                JsonReadContext at = ctx.field(member.name());
                if (!stated.add(member.name())) {
                    at.report(Diagnostic.Code.DUPLICATE_MAP_KEY,
                            "'%s' is already a key of this map, and a key appears once".formatted(member.name()),
                            "each key stated once", "'" + member.name() + "' stated again");
                }
                Object boundKey = JsonAtoms.bind(at,
                        new JsonEvent.StringValue(member.name(), member.position()), key);
                Object value = bind(at, at.next(), target.valueDataClass());
                if (boundKey != null) {
                    target.put().invoke(map, boundKey, value);
                }
            }
        } catch (Throwable e) {
            return failed(ctx, target.typeClass(), e);
        }
    }

    // ── Annotations carrier ──────────────────────────────────────────────

    /** §4.3: no annotations reach the JSON wire, so the wrapper's own value binds and the carrier is empty. */
    private Object bindAnnotated(JsonReadContext ctx, JsonEvent first, DataClassAnnotated target) {
        Object value = bind(ctx, first, target.valueClass());
        try {
            return target.constructor().invoke(value, Annotations.empty());
        } catch (Throwable e) {
            return failed(ctx, target.typeClass(), e);
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
    private static void skipValue(JsonReadContext ctx, JsonEvent first) {
        int depth = first instanceof JsonEvent.ObjectStart || first instanceof JsonEvent.ArrayStart ? 1 : 0;
        while (depth > 0) {
            JsonEvent event = ctx.next();
            if (event instanceof JsonEvent.ObjectStart || event instanceof JsonEvent.ArrayStart) {
                depth++;
            } else if (event instanceof JsonEvent.ObjectEnd || event instanceof JsonEvent.ArrayEnd) {
                depth--;
            }
        }
    }

    private Object construct(JsonReadContext ctx, MethodHandle constructor, Object[] arguments, Class<?> type) {
        try {
            return constructor.invoke(arguments);
        } catch (Throwable e) {
            return failed(ctx, type, e);
        }
    }

    private static void wrongShape(JsonReadContext ctx, JsonEvent found, String expected, Class<?> type) {
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "%s takes %s, and this is %s"
                .formatted(type.getSimpleName(), expected, JsonAtoms.describe(found)),
                expected, JsonAtoms.describe(found));
    }

    /**
     * A constructor or collection call that threw — the class's own rule refusing the value, most often,
     * which is a fact about this document and not a fault in this library.
     */
    private static Object failed(JsonReadContext ctx, Class<?> type, Throwable cause) {
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "%s rejected the value read for it: %s".formatted(type.getSimpleName(), cause),
                "a value " + type.getSimpleName() + " accepts", String.valueOf(cause.getMessage()));
        return null;
    }
}
