package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.UnicodePolicy;
import io.ltr8.tson.base.LimitsPolicy;
import io.ltr8.tson.base.ProcessorPolicy;
import io.ltr8.tson.json.reader.DataClassObjectReader;
import io.ltr8.tson.json.stream.JsonEventSource;
import io.ltr8.tson.json.stream.JsonStream;
import java.io.InputStream;


/**
 * Reads a JSON document straight into a Java object, driven by the target class's own
 * {@code tson-bind} descriptor.
 *
 * <p><b>The class is the schema.</b> [TSON-JSON] §4.1 reads every JSON value at a typed position and
 * never by inspecting the value twice; here the type comes from a {@code DataClass} rather than from a
 * resolved TSON schema, and every rule below follows from that one substitution. An object at a
 * {@code DataClassRecord} is a record and at a {@code DataClassMap} is a map — the brace question JSON
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
 * <p><b>A facade over {@link DataClassObjectReader}</b>, which is what actually binds a value. The split is
 * the one {@code TsonObjectReader} makes over {@code SchemalessObjectReader}, and for the same reason: a
 * front door owns the document -- entry points, framing, and the configuration a read is judged under --
 * where the engine owns one value at one descriptor and stops. It is also where the schema-directed decode
 * of §5-§8 will arrive: a second engine under this same door rather than a second door.
 *
 * <p><b>Streams the events, never a tree.</b> Memory held is proportional to nesting depth rather than
 * to document size, and {@link io.ltr8.tson.json.stream.JsonStream}'s §10.1 bound refuses a document before this descends into
 * it.
 *
 * <p><b>Every problem goes through a {@link DiagnosticsReceiver}</b>, so this read's own receiver decides
 * its fate exactly as it does for the TSON readers: {@link DiagnosticsReceiver#throwing()} -- the default --
 * raises {@code ReadException} at the first, and {@link #withDiagnostics} with
 * {@link DiagnosticsReceiver#collecting()} gathers every problem in one pass and still returns. One pass
 * finding everything is the point: a reader that threw could only ever report the first disagreement, and a
 * sender fixing a document one round trip per mistake is the failure mode diagnostics exist to avoid.
 *
 * <p>Each problem carries a {@link io.ltr8.tson.base.Diagnostic.Code} from the same closed vocabulary the
 * TSON readers use ([TSON-JSON] §9.4 adds no category of its own), an RFC 6901 pointer to where in the
 * document it is, and the position. Two of those codes are deliberately not verdicts on the document:
 * {@code BIND_MISMATCH} for a class this context cannot analyse or cannot receive a JSON object's keys
 * into, which is a misconfiguration in the reading application rather than anything about the document.
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
 * <p>Annotations do not exist on the JSON wire (§4.3), so a class declaring an {@code Annotations}
 * carrier gets an empty one. Nothing is dropped: there was nothing to drop.
 */
public final class JsonObjectReader {

    private final DataBindContext context;

    /** Whether a member the target class does not declare is discarded rather than refused -- see {@link #ignoringUnknownMembers}. */
    private final boolean ignoreUnknownMembers;

    /** What actually binds a value. Rebuilt per derived reader, since a derivation is a change to how it binds. */
    private final DataClassObjectReader engine;

    /** What decides a problem's fate. Fail-fast until a caller names otherwise. */
    private final DiagnosticsReceiver receiver;

    /**
     * Everything this reader will admit and spend -- §8.2's token surface and §10.1's bounds. One value
     * because a deployment states one, and the same value {@code TsonObjectReader} carries.
     */
    private final ProcessorPolicy policy;

    private JsonObjectReader(DataBindContext context, boolean ignoreUnknownMembers,
                             DiagnosticsReceiver receiver, ProcessorPolicy policy) {
        this.context = context;
        this.ignoreUnknownMembers = ignoreUnknownMembers;
        this.receiver = receiver;
        this.policy = policy;
        this.engine = new DataClassObjectReader(context, ignoreUnknownMembers);
    }

    /**
     * This reader applying {@code policy} to every token a document carries -- its strings, its numbers and
     * its member names ([TSON-DATA] §8.2's "Values", reached into this encoding by [TSON-JSON] §9.4).
     *
     * <p>Defaults to {@code unrestricted()}: a value is data and may legitimately be anything, so §8.2
     * scans none of it until a deployment says otherwise -- and §8.2 requires that saying so be code rather
     * than ambient, which is what this method is. The peer of {@code TsonObjectReader.withTokenPolicy}, and
     * the policy rides the <em>stream</em> this reader builds, since that is what produces each token
     * exactly once.
     */
    public JsonObjectReader withTokenPolicy(UnicodePolicy tokenPolicy) {
        return new JsonObjectReader(context, ignoreUnknownMembers, receiver, policy.withTokenPolicy(tokenPolicy));
    }

    /**
     * This reader under {@code limits} -- a new reader, leaving this one unchanged.
     *
     * <p>[TSON-JSON] §10.1 makes JSON's bounds [TSON-DATA] §9.1's policy "in JSON clothing, and the same
     * policy applies with the same defaults", and §9.1 requires the bounds be configurable in code rather
     * than from the ambient environment. The peer of {@code TsonObjectReader.withLimits}, and what replaced
     * a per-call {@code maxDepth}: a bound is the reader's, not one read's.
     */
    public JsonObjectReader withLimits(LimitsPolicy limits) {
        return new JsonObjectReader(context, ignoreUnknownMembers, receiver, policy.withLimits(limits));
    }

    /**
     * This reader under {@code policy} whole -- what a caller holding a processor's own policy says in one
     * call, rather than the derivations that each change one component of it. The peer of
     * {@code TsonObjectReader.withProcessorPolicy}, and how a {@code Tson} front door will hand this reader
     * the same policy its TSON readers carry.
     */
    public JsonObjectReader withProcessorPolicy(ProcessorPolicy policy) {
        return new JsonObjectReader(context, ignoreUnknownMembers, receiver, policy);
    }

    /** Everything this reader will admit and spend, for a caller stating it beside a read's diagnostics. */
    public ProcessorPolicy processorPolicy() {
        return policy;
    }

    /** {@link #processorPolicy()}'s {@code limits} component, in one call. */
    public LimitsPolicy limitsPolicy() {
        return policy.limits();
    }

    /**
     * This reader routing its problems to {@code receiver} -- a new reader, leaving this one unchanged.
     *
     * <p>The peer of {@code TsonObjectReader.withDiagnostics}, and what makes a one-pass read possible:
     * {@code DiagnosticsReceiver.collecting()} gathers every problem and lets the read run to the end,
     * where the default {@code throwing()} raises {@code ReadException} at the first. A collecting read
     * hands back {@code null} rather than a half-built object -- see {@code DataClassObjectReader} on why
     * bind mode is all-or-nothing where a tree keeps what it built.
     */
    public JsonObjectReader withDiagnostics(DiagnosticsReceiver receiver) {
        return new JsonObjectReader(context, ignoreUnknownMembers, receiver, policy);
    }

    /** Over a caller's own bind context — one per binding profile, descriptors cached inside it. */
    public static JsonObjectReader using(DataBindContext context) {
        return new JsonObjectReader(context, false, DiagnosticsReceiver.throwing(), ProcessorPolicy.defaults());
    }

    /**
     * Over a default context, which binds records, arrays, maps, tuples and the primitive and boxed
     * atoms. A caller wanting TSON's atom vocabulary ({@code UUID}, the temporal families) builds a
     * context registering them and uses {@link #using}.
     */
    public static JsonObjectReader standard() {
        return new JsonObjectReader(DataBindContext.builder().build(), false, DiagnosticsReceiver.throwing(),
                ProcessorPolicy.defaults());
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
        return new JsonObjectReader(context, true, receiver, policy);
    }

    // ── Reading ──────────────────────────────────────────────────────────

    public <T> T read(String source, Class<T> type) {
        return read(new JsonStream(source, policy, receiver), type);
    }


    /** Off UTF-8 bytes, where §3.1's rules bite. {@code source} is not closed here. */
    public <T> T read(InputStream source, Class<T> type) {
        return read(new JsonStream(source, policy, receiver), type);
    }


    /** Off an event source, which is the seam the two families above come through. */
    public <T> T read(JsonEventSource events, Class<T> type) {
        return readDocument(events, type);
    }

    // ── Framing ──────────────────────────────────────────────────────────

    /**
     * Binds the document's root value, then drains the source.
     *
     * <p>The pull past the root value is what rejects trailing content, so a read that stopped at the
     * object's closing brace would accept {@code "{} 2"}. Framing is the facade's because the document is:
     * {@link DataClassObjectReader} binds a value and stops, which is what lets it compose.
     */
    private <T> T readDocument(JsonEventSource events, Class<T> type) {
        T value = engine.read(events, type, receiver);
        events.next();   // EndOfDocument, or the stream refuses what follows the root value
        return value;
    }
}
