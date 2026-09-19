package io.ltr8.tson.json;

import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.CountingReceiver;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.LimitExceededException;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.reader.DataClassObjectReader;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;
import io.ltr8.tson.json.stream.JsonStream;
import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;


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
 * calling it done. The engine under it is the JSON peer of {@code tson-compiler}'s reader of the same
 * name, which does the same job against the TSON event stream, and it sits beside {@link Json} for the
 * same reason {@code TsonObjectReader} sits beside {@code Tson}: a reader is a front door, not a
 * layer of one.
 *
 * <p><b>Two engines under one door.</b> {@link #read} binds through {@link DataClassObjectReader}, taking the
 * class as the schema; {@link #readAs}, on a reader from {@code Json.withSchemas(loader).objectReader()} named a
 * schema with {@link #withSchema}, reads against the TSON schema in bind mode -- [TSON-JSON] §5-§8's
 * schema-directed decode, validating in full and building the classes the bind context resolves. The split is
 * the one {@code TsonObjectReader} makes, and for the same reason: a front door owns the document -- entry
 * points, framing, and the configuration a read is judged under -- where an engine owns one value and stops.
 *
 * <p><b>Streams the events, never a tree.</b> Memory held is proportional to nesting depth rather than
 * to document size, and {@link io.ltr8.tson.json.stream.JsonStream} 's §10.1 bound refuses a document before this descends
 * into
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
 * the two paths agree. {@link #ignoringUnknownFields} is the opt-out -- <b>the same name the TSON reader
 * uses</b>, because the rule is about a <em>record's fields</em> and a record is the same thing in both
 * encodings. RFC 8259 calls an object's entries members, but what is being closed here is a bound class's
 * field set, not a JSON syntactic category, and one rule with two names is a rule two readers can drift on.
 *
 * <p>The cost is real and is worth stating: a converted JSON Schema whose {@code additionalProperties}
 * defaults to true describes documents this reader refuses. That is §6.2's rest field's job to fix, and
 * it fixes it properly by <em>keeping</em> the extra members rather than by ignoring them — which is the
 * difference between a document read wholly and one read partly, and why the schema-directed decode is
 * where the accommodation belongs rather than here.
 *
 * <p><b>What {@link #read} is not.</b> It is not validation against a TSON schema, and it is not §1.3
 * principle 1's schema-directed decode: nothing there consults facets, field states, defaults, fixed values,
 * groups or the discrimination predicate, because a Java class declares none of them. It is the ergonomic read
 * for a caller who has a class and a document, and the honest name for what it checks is "does this document
 * fit this class" -- {@link #readAs} is the read that asks whether it conforms.
 *
 * <h2>The rules that are §7-shaped, and why they are</h2>
 *
 * JSON null is bound the way §7 binds it, as far as a class can express §7: a component the class marks
 * required refuses it, one that does not takes {@code null}. That is the same treatment
 * {@code tson-compiler}'s reader of that name gives the absent sentinel {@code _} — §7 makes the
 * two spellings one concept, and a bound object has no third state to tell "omitted" from "present and
 * null" (the asymmetry {@code TsonAbsent} exists for on the tree side). An omitted member and a null
 * member are therefore indistinguishable in the result, which §6.1.2 says outright.
 *
 * <p>Annotations do not exist on the JSON wire (§4.3), so a class declaring an {@code Annotations}
 * carrier gets an empty one. Nothing is dropped: there was nothing to drop.
 */
public final class JsonObjectReader {

    private final DataBindContext context;

    /**
     * Whether a member the target class does not declare is discarded rather than refused -- see
     * {@link #ignoringUnknownFields} .
     */
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

    /** Where a named schema is compiled from in bind mode, or null for a reader that can name none. */
    private final JsonCompiledSchemaRegistry schemas;

    /** The schema {@link #readAs} reads against, or null until {@link #withSchema} names one. */
    private final String schemaUri;

    private JsonObjectReader(DataBindContext context, boolean ignoreUnknownMembers, DiagnosticsReceiver receiver,
                             ProcessorPolicy policy, JsonCompiledSchemaRegistry schemas, String schemaUri) {
        this(context, ignoreUnknownMembers, receiver, policy, schemas, schemaUri,
                new DataClassObjectReader(context, ignoreUnknownMembers, policy.identifierPolicy()));
    }

    /** Sharing {@code engine}, for a copy that differs from its original only in its receiver. */
    private JsonObjectReader(DataBindContext context, boolean ignoreUnknownMembers, DiagnosticsReceiver receiver,
                             ProcessorPolicy policy, JsonCompiledSchemaRegistry schemas, String schemaUri,
                             DataClassObjectReader engine) {
        this.context = context;
        this.ignoreUnknownMembers = ignoreUnknownMembers;
        this.receiver = receiver;
        this.policy = policy;
        this.schemas = schemas;
        this.schemaUri = schemaUri;
        this.engine = engine;
    }



    /**
     * This reader under {@code policy} -- a new reader, leaving this one unchanged.
     *
     * <p><b>The only policy derivation here, deliberately.</b> {@code ProcessorPolicy} already carries
     * {@code withIdentifierPolicy}/{@code withTokenPolicy}/{@code withLimits}, so a caller changing one
     * component says {@code r.withProcessorPolicy(r.processorPolicy().withTokenPolicy(p))} -- one method on
     * the reader and the component derivations where the components live, rather than three more that only
     * forward. The TSON facades carry all four for history; a new surface need not.
     *
     * <p>It is also how a {@code Tson} front door hands this reader the same policy its TSON readers carry.
     */
    public JsonObjectReader withProcessorPolicy(ProcessorPolicy policy) {
        return new JsonObjectReader(context, ignoreUnknownMembers, receiver, policy, schemas, schemaUri);
    }

    /**
     * The bind context this reader binds through -- {@link AtomContext#defaultContext()} unless a caller
     * supplied one. {@code TsonObjectReader}'s counterpart on the other encoding.
     */
    public DataBindContext dataBindContext() {
        return context;
    }

    /** Everything this reader will admit and spend, for a caller stating it beside a read's diagnostics. */
    public ProcessorPolicy processorPolicy() {
        return policy;
    }


    /**
     * This reader routing its problems to {@code receiver} -- a new reader, leaving this one unchanged.
     *
     * <p>The peer of {@code TsonObjectReader.withDiagnostics}, and what makes a one-pass read possible:
     * {@code DiagnosticsReceiver.collecting()} gathers every problem and lets the read run to the end,
     * where the default {@code throwing()} raises {@code ReadException} at the first. A collecting read
     * that reported anything hands back {@code null} rather than a half-built object, as every read does.
     */
    public JsonObjectReader withDiagnostics(DiagnosticsReceiver receiver) {
        return new JsonObjectReader(context, ignoreUnknownMembers, receiver, policy, schemas, schemaUri);
    }

    /**
     * Over {@code schemas}, a bind-mode registry, and the context it binds through -- what {@code
     * Json.withSchemas(loader).objectReader()} hands back, and the only route to {@link #withSchema}.
     */
    static JsonObjectReader over(JsonCompiledSchemaRegistry schemas, DataBindContext context) {
        if (schemas.mode() != JsonCompiledSchemaRegistry.Mode.BIND) {
            throw new IllegalArgumentException("a JsonObjectReader needs a bind-mode registry "
                    + "(JsonCompiledSchemaRegistry.bind(loader, context)) -- a tree-mode one reads to JsonValues");
        }
        return new JsonObjectReader(context, false, DiagnosticsReceiver.throwing(), ProcessorPolicy.defaults(),
                schemas, null);
    }

    /**
     * This reader bound to the schema {@code uri} names, for {@link #readAs} -- a new reader, leaving this one
     * unchanged, sharing its compiled-schema registry. {@code TsonObjectReader.withSchema}'s peer.
     *
     * <p>[TSON-JSON] §3.4's <b>out-of-band route</b>: the application supplies the schema and the root type, and
     * the document is a bare value read at that type. The schema is not resolved here: {@link #readAs} reaches
     * for it, so a schema nothing would supply is a diagnostic on the read that needed it.
     */
    public JsonObjectReader withSchema(String uri) {
        if (schemas == null) {
            throw new IllegalStateException("this reader can name no schema -- one from Json.withSchemas(loader)"
                    + ".objectReader() can, and a class-directed read is read(...) rather than readAs(...)");
        }
        return new JsonObjectReader(context, ignoreUnknownMembers, receiver, policy, schemas,
                Objects.requireNonNull(uri, "uri"));
    }

    /** Over a caller's own bind context — one per binding profile, descriptors cached inside it. */
    public static JsonObjectReader using(DataBindContext context) {
        return new JsonObjectReader(context, false, DiagnosticsReceiver.throwing(), ProcessorPolicy.defaults(),
                null, null);
    }

    /**
     * Over {@link AtomContext#defaultContext()} -- records, arrays, maps, tuples, the primitive and
     * boxed atoms, and the host types the built-in atom families read to ({@code UUID}, the temporal,
     * network and identifier families).
     *
     * <p><b>The same vocabulary the TSON text reader starts from</b>, per [TSON-JSON] §5.1: a consumer must
     * not have to discover that one front door treats their {@code UUID} component as a scalar and the
     * other takes it apart.
     */
    public static JsonObjectReader standard() {
        return new JsonObjectReader(AtomContext.defaultContext(), false, DiagnosticsReceiver.throwing(),
                ProcessorPolicy.defaults(), null, null);
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
    public JsonObjectReader ignoringUnknownFields() {
        return new JsonObjectReader(context, true, receiver, policy, schemas, schemaUri);
    }

    // ── Reading ──────────────────────────────────────────────────────────

    public <T> T read(String source, Class<T> type) {
        // §3.1 makes the document UTF-8 and the lexer takes bytes; a string is re-encoded here, at the
        // front door, rather than by a convenience on every layer beneath.
        try (ByteSource bytes = ByteSource.of(source)) {
            return read(bytes, type);
        }
    }


    /**
     * Reads {@code source} -- the general entry, and what the {@code String} and {@code InputStream} forms
     * above adapt to. A source already in memory is read without a copy.
     */
    public <T> T read(ByteSource source, Class<T> type) {
        return counted(r -> r.readEvents(new JsonStream(source, policy, r.receiver), type));
    }

    /** Off UTF-8 bytes, where §3.1's rules bite. {@code source} is not closed here. */
    public <T> T read(InputStream source, Class<T> type) {
        try (ByteSource bytes = ByteSource.of(source)) {
            return counted(r -> r.readEvents(new JsonStream(bytes, policy, r.receiver), type));
        }
    }


    /**
     * Off an event source, which is the seam the two families above come through. A problem the source reports
     * to a receiver of its own is not this read's to count.
     */
    public <T> T read(JsonEventSource events, Class<T> type) {
        return counted(r -> r.readEvents(events, type));
    }

    private <T> T readEvents(JsonEventSource events, Class<T> type) {
        try {
            return readDocument(events, type);
        } catch (RuntimeException e) {
            return readFailure(e);
        }
    }

    /**
     * One whole-document read, run on a copy of this reader whose receiver counts: a document that reported
     * anything binds to nothing, whatever was assembled beneath ({@link CountingReceiver}), and the rule holds
     * over every route a problem takes, the root's framing and the stream's own refusals among them.
     */
    private <T> T counted(Function<JsonObjectReader, T> read) {
        CountingReceiver counting = new CountingReceiver(receiver);
        T value = read.apply(new JsonObjectReader(context, ignoreUnknownMembers, counting, policy, schemas,
                schemaUri, engine));
        return counting.reported() ? null : value;
    }

    /**
     * A document that will not parse, reported through this read's own receiver rather than thrown past it
     * -- {@code JsonTreeReader.readFailure}'s peer, and the reason a collecting read never throws for a bad
     * <i>document</i> while a fail-fast one still does.
     *
     * <p>Hands back {@code null}: bind mode is all-or-nothing, so a document whose syntax failed produces no
     * object at all rather than one built from the members that arrived before the failure.
     *
     * <p>{@link JsonDiagnostics#ofBaseSyntaxError} rethrows anything that is not a syntax failure, and a
     * limit refusal is classified apart ({@link Diagnostic#ofLimitExceeded}) because it says this processor
     * declined rather than that the document is malformed.
     */

    private <T> T readFailure(RuntimeException e) {
        receiver.report(e instanceof LimitExceededException limit
                ? Diagnostic.ofLimitExceeded(limit)
                : JsonDiagnostics.ofBaseSyntaxError(e));
        return null;
    }

    // ── Reading against a schema ─────────────────────────────────────────

    /** {@link #readAs(ByteSource, String, Class)} over a string. */
    public <T> T readAs(String source, String typeName, Class<T> type) {
        try (ByteSource bytes = ByteSource.of(source)) {
            return readAs(bytes, typeName, type);
        }
    }

    /** {@link #readAs(ByteSource, String, Class)} over a stream, which is not closed here. */
    public <T> T readAs(InputStream source, String typeName, Class<T> type) {
        try (ByteSource bytes = ByteSource.of(source)) {
            return readAs(bytes, typeName, type);
        }
    }

    /**
     * Binds {@code source} as {@code typeName}, declared by the schema {@link #withSchema} named, into {@code type}
     * -- [TSON-JSON] §3.4's out-of-band binding, and {@code TsonObjectReader.readAs}'s peer. Unlike {@link #read},
     * which takes the class as the schema, this validates the document in full against the TSON schema -- facets,
     * field states, defaults and fixed values, groups, the discrimination predicate -- and builds the class from
     * what it read: each record into the class the bind context resolves for its schema type, each field into
     * what its component declares.
     *
     * <p><b>All-or-nothing, at the document too.</b> A document that reported anything binds to {@code null}. A
     * fail-fast reader throws at the first problem; a collecting one hands every problem to its receiver and
     * returns {@code null}.
     *
     * <p><b>What stands in the way of a read is a diagnostic, and a verdict only where it is one.</b> A schema
     * nothing supplies is {@code SCHEMA_NOT_FOUND}, a type it does not declare {@code UNKNOWN_TYPE}, a schema
     * whose types the bound classes do not match {@code BIND_MISMATCH}, and a root type bound to a class {@code
     * type} cannot hold {@code TYPE_MISMATCH} -- that last checked before the value is read. A schema type with
     * no bound class at all reaches the read that needs it as {@code MissingBindingException}: the reading
     * application's own wiring, in every mode.
     */
    public <T> T readAs(ByteSource source, String typeName, Class<T> type) {
        return counted(r -> r.readRootAs(source, typeName, type));
    }

    private <T> T readRootAs(ByteSource source, String typeName, Class<T> type) {
        if (schemaUri == null) {
            throw new IllegalStateException("no schema named -- readAs reads against one, so name it with "
                    + "withSchema(uri); a class-directed read is read(...)");
        }
        Root root = root(typeName, type);
        if (root == null) {
            return null;   // refused before the document was touched, the reason reported
        }
        try {
            JsonEventSource events = new JsonStream(source, policy, receiver);
            JsonReadContext ctx = JsonReadContext.of(events, receiver, policy.identifierPolicy());
            // Rooted at the name the caller supplied, so a diagnostic names the declaration the author wrote rather
            // than whatever an alias resolves to -- JsonTreeReader.readAs does the same.
            ctx = root.declaration().map(ctx::underDeclaration).orElse(ctx);
            Object value = root.reader().read(ctx);
            if (value != null && !type.isInstance(value)) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH, "the schema's type `" + typeName + "` produced a "
                        + value.getClass().getName() + ", not the requested " + type.getName(), type.getName(),
                        value.getClass().getName());
                return null;
            }
            if (!(events.next() instanceof JsonEvent.EndOfDocument)) {
                throw new IllegalStateException("the stream produced events after the document's root value");
            }
            return type.cast(value);
        } catch (RuntimeException e) {
            return readFailure(e);
        }
    }

    /** The reader for the root, and the declaration a diagnostic about it is rooted at. */
    private record Root(JsonTypeReader<?> reader, Optional<JsonSchemaLocation> declaration) {
    }

    /**
     * The reader for {@code typeName} in this reader's schema, checked against {@code type} -- or null, the reason
     * reported, before any of the document is read.
     */
    private Root root(String typeName, Class<?> type) {
        Optional<JsonCompiledSchema> schema;
        try {
            schema = schemas.get(schemaUri);
        } catch (MissingBindingException e) {
            throw e;
        } catch (BindMismatchException e) {
            report(Diagnostic.Code.BIND_MISMATCH, e.getMessage(), "a schema whose types the bound classes match",
                    schemaUri);
            return null;
        }
        if (schema.isEmpty()) {
            report(Diagnostic.Code.SCHEMA_NOT_FOUND, "no schema was supplied for \"" + schemaUri + "\"",
                    "a schema this processor can obtain", schemaUri);
            return null;
        }
        Optional<JsonTypeReader<?>> reader = schema.get().find(typeName);
        if (reader.isEmpty()) {
            report(Diagnostic.Code.UNKNOWN_TYPE, schema.get().unknownTypeMessage(typeName),
                    schema.get().declaredTypeNames(), typeName);
            return null;
        }
        Class<?> bound = boundClass(typeName);
        if (bound != null && !type.isAssignableFrom(bound)) {
            report(Diagnostic.Code.TYPE_MISMATCH, "the schema's type `" + typeName + "` binds to " + bound.getName()
                    + ", which is not assignable to the requested " + type.getName(), type.getName(), bound.getName());
            return null;
        }
        return new Root(reader.get(), schema.get().rootDeclaration(typeName));
    }

    /**
     * The class {@code typeName} is bound to, or null where it is not bound by name -- a container, an atom, a
     * type the context cannot resolve. The before-read check then falls to the after-read one.
     */
    private Class<?> boundClass(String typeName) {
        try {
            return context.getDescriptor(typeName).typeClass();
        } catch (DataBindException | RuntimeException e) {
            return null;
        }
    }

    /** A problem stated about the read as a whole, before any of the document is read -- no pointer, no position. */
    private void report(Diagnostic.Code code, String message, String expected, String actual) {
        receiver.report(new Diagnostic(Optional.of(""), Optional.empty(), schemaUri, code, message, expected,
                actual, Optional.empty(), Optional.empty()));
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
