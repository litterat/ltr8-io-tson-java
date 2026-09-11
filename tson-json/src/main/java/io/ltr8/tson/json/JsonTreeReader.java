package io.ltr8.tson.json;

import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.LimitExceededException;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.reader.SchemalessTreeReader;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonValue;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads a JSON document into a {@link JsonValue} tree -- the peer of {@link JsonObjectReader}, and what
 * {@link Json#treeReader()} hands out.
 *
 * <p>A facade over {@link SchemalessTreeReader}, on the split {@code TsonTreeReader} makes over its own
 * engine: a front door owns the document -- entry points, framing, and the configuration a read is judged
 * under -- where the engine owns one value and stops.
 *
 * <p><b>Every problem goes through a {@link DiagnosticsReceiver}</b>, so a read's own receiver decides its
 * fate: {@link DiagnosticsReceiver#throwing()} -- the default -- raises {@code ReadException} at the first,
 * and {@link #withDiagnostics} with {@link DiagnosticsReceiver#collecting()} gathers every problem in one
 * pass. A collecting read <b>still hands back the tree</b>, where {@link JsonObjectReader} hands back
 * nothing: a {@code JsonObject} has somewhere to put a partial answer and a Java record does not.
 *
 * <p><b>What a tree read is not.</b> It is a JSON document, not a TSON value: no schema has been consulted,
 * {@code null} is a value, and no member name has been read as a field name. [TSON-JSON] §1.3's first
 * principle makes decoding schema-directed, so this is a JSON convenience the encoding does not itself
 * define -- useful, and never a validation.
 */
public final class JsonTreeReader {

    private static final SchemalessTreeReader ENGINE = new SchemalessTreeReader();

    private final ProcessorPolicy policy;
    private final DiagnosticsReceiver receiver;

    /** Where a named schema is compiled from, or null for a reader that can name none. */
    private final JsonCompiledSchemaRegistry schemas;

    /** The schema this reader is bound to, or null until {@link #withSchema} names one. */
    private final String schemaUri;

    private JsonTreeReader(ProcessorPolicy policy, DiagnosticsReceiver receiver,
                           JsonCompiledSchemaRegistry schemas, String schemaUri) {
        this.policy = policy;
        this.receiver = receiver;
        this.schemas = schemas;
        this.schemaUri = schemaUri;
    }

    /** Under the processor's defaults, raising what it refuses. Schemaless: {@link #readAs} needs a schema. */
    public static JsonTreeReader standard() {
        return new JsonTreeReader(ProcessorPolicy.defaults(), DiagnosticsReceiver.throwing(), null, null);
    }

    /** Over {@code schemas} -- what {@code Json.withSchemas(...).treeReader()} hands back. */
    static JsonTreeReader over(JsonCompiledSchemaRegistry schemas) {
        return new JsonTreeReader(ProcessorPolicy.defaults(), DiagnosticsReceiver.throwing(), schemas, null);
    }

    /**
     * This reader under {@code policy} -- a new reader, leaving this one unchanged.
     *
     * <p>The only policy derivation here, for {@link JsonObjectReader#withProcessorPolicy}'s reason:
     * {@code ProcessorPolicy} already carries the component derivations, so a caller changing one says
     * {@code r.withProcessorPolicy(r.processorPolicy().withTokenPolicy(p))}.
     */
    public JsonTreeReader withProcessorPolicy(ProcessorPolicy policy) {
        return new JsonTreeReader(policy, receiver, schemas, schemaUri);
    }

    /** This reader routing its problems to {@code receiver} -- a new reader, leaving this one unchanged. */
    public JsonTreeReader withDiagnostics(DiagnosticsReceiver receiver) {
        return new JsonTreeReader(policy, receiver, schemas, schemaUri);
    }

    /**
     * This reader bound to the schema {@code uri} names -- a new reader, leaving this one unchanged.
     *
     * <p>[TSON-JSON] §3.4's <b>out-of-band route</b>, which the spec calls "the expected production route":
     * the application supplies the schema and the root type, and the document is then a bare value read
     * directly at that type. A JSON document has no in-band channel for either -- {@code !!schema} is TSON
     * text syntax -- so this is where the binding a TSON document carries in its own header comes from.
     *
     * <p>The schema is not resolved here: {@link #readAs} reaches for it, so a schema nothing would supply
     * is a diagnostic on the read that needed it rather than an exception from the derivation that named it.
     */
    public JsonTreeReader withSchema(String uri) {
        if (schemas == null) {
            throw new IllegalStateException("this reader can name no schema -- one from Json.withSchemas(loader)"
                    + ".treeReader() can, and a schemaless read is read(...) rather than readAs(...)");
        }
        return new JsonTreeReader(policy, receiver, schemas, Objects.requireNonNull(uri, "uri"));
    }

    /** Everything this reader will admit and spend, for a caller stating it beside a read's diagnostics. */
    public ProcessorPolicy processorPolicy() {
        return policy;
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /** A string as the bytes this encoding reads -- §3.1 makes the document UTF-8 and the lexer takes bytes. */
    public JsonValue read(String source) {
        try (ByteSource bytes = ByteSource.of(source)) {
            return read(bytes);
        }
    }

    /**
     * Reads {@code source} -- the general entry, and what the {@code String} and {@code InputStream} forms
     * above adapt to. {@code ByteSource.of} covers a {@code byte[]}, a {@code ByteBuffer}, a {@code Path}
     * and a mapped file besides, and a source already in memory is read without a copy.
     */
    public JsonValue read(ByteSource source) {
        return read(new JsonStream(source, policy, receiver));
    }

    /** {@code source} is not closed here. */
    public JsonValue read(InputStream source) {
        try (ByteSource bytes = ByteSource.of(source)) {
            return read(new JsonStream(bytes, policy, receiver));
        }
    }

    /**
     * Off an event source, which is the seam the two above come through.
     *
     * <p>The source is drained through {@link JsonEvent.EndOfDocument} -- the pull past the root value is
     * what rejects trailing content, so a read that stopped at the root's last event would accept
     * {@code "[1] 2"}.
     */
    public JsonValue read(JsonEventSource events) {
        try {
            JsonReadContext ctx = JsonReadContext.of(events, receiver);
            JsonValue root = ENGINE.read(ctx);
            if (!(events.next() instanceof JsonEvent.EndOfDocument)) {
                throw new IllegalStateException("the stream produced events after the document's root value");
            }
            return root;
        } catch (RuntimeException e) {
            return readFailure(e);
        }
    }

    // ── Reading against a schema ─────────────────────────────────────────

    /** {@link #readAs(ByteSource, String)} over a string. */
    public JsonValue readAs(String source, String rootType) {
        try (ByteSource bytes = ByteSource.of(source)) {
            return readAs(bytes, rootType);
        }
    }

    /** {@link #readAs(ByteSource, String)} over a stream, which is not closed here. */
    public JsonValue readAs(InputStream source, String rootType) {
        try (ByteSource bytes = ByteSource.of(source)) {
            return readAs(bytes, rootType);
        }
    }

    /**
     * Reads {@code source} against this reader's schema, at {@code rootType} -- [TSON-JSON] §3.4's
     * out-of-band binding, and the whole of what a schema-directed read needs beyond the document.
     *
     * <p><b>Validation is the point and the tree is the by-product.</b> Each family's parser runs, which is
     * what §5.1 makes the validation, and the document comes back as the JSON it was. A caller wanting a
     * typed value is asking a different question and reads in bind mode.
     *
     * <p><b>Failing to reach the schema is a diagnostic, never an exception</b>, and never a verdict on the
     * document: {@code SCHEMA_NOT_FOUND} for an identity the loader has none for, {@code UNKNOWN_TYPE} for a
     * root type the schema does not declare. {@code Code.verdict()} is false for both, so a consumer routing
     * on the code cannot mistake either for the document being wrong.
     */
    public JsonValue readAs(ByteSource source, String rootType) {
        if (schemaUri == null) {
            throw new IllegalStateException("no schema named -- readAs reads against one, so name it with "
                    + "withSchema(uri); a schemaless read is read(...)");
        }
        Optional<JsonCompiledSchema> schema = schemas.get(schemaUri);
        if (schema.isEmpty()) {
            receiver.report(new Diagnostic(Optional.of(""), Optional.empty(), schemaUri,
                    Diagnostic.Code.SCHEMA_NOT_FOUND, "no schema was supplied for \"" + schemaUri + "\"",
                    "a schema this processor can obtain", schemaUri, Optional.empty(), Optional.empty()));
            return null;
        }
        Optional<JsonTypeReader<?>> reader = schema.get().find(rootType);
        if (reader.isEmpty()) {
            receiver.report(new Diagnostic(Optional.of(""), Optional.empty(), schemaUri,
                    Diagnostic.Code.UNKNOWN_TYPE, schema.get().unknownTypeMessage(rootType),
                    schema.get().declaredTypeNames(), rootType, Optional.empty(), Optional.empty()));
            return null;
        }
        return readAs(new JsonStream(source, policy, receiver), schema.get(), rootType, reader.get());
    }

    /**
     * The framing, shared by every {@code readAs} above: the root value at its compiled reader, then the
     * pull past it that rejects trailing content.
     *
     * <p>The root declaration is <b>seeded from the name the read entered through</b> rather than left to
     * the reader to claim. The two differ whenever that name aliases something else, and a diagnostic naming
     * the aliased type points the author at a file they did not write.
     */
    private JsonValue readAs(JsonEventSource events, JsonCompiledSchema schema, String rootType,
                             JsonTypeReader<?> reader) {
        try {
            // The identifier policy reaches this read and not the schemaless one: a schema-directed reader
            // holds the position, so it knows which member names are field names ([TSON-JSON] §9.4).
            JsonReadContext ctx = JsonReadContext.of(events, receiver, policy.identifierPolicy());
            ctx = schema.rootDeclaration(rootType).map(ctx::underDeclaration).orElse(ctx);
            JsonValue root = (JsonValue) reader.read(ctx);
            if (!(events.next() instanceof JsonEvent.EndOfDocument)) {
                throw new IllegalStateException("the stream produced events after the document's root value");
            }
            return root;
        } catch (RuntimeException e) {
            return readFailure(e);
        }
    }

    /**
     * A document that will not parse, reported through this read's own receiver rather than thrown past it
     * -- so a collecting read never throws for a bad <i>document</i>, and a fail-fast one still throws,
     * because its receiver does when handed this.
     *
     * <p><b>Why the receiver rather than the caller.</b> The lexer is fail-fast and the stream is lazy, so a
     * syntax failure surfaces mid-read, after any earlier value-level problem has already been reported --
     * which leaves a collecting caller holding a populated collector <em>and</em> an exception, with no way
     * to tell that the two belong to one document. Reporting it is also the only way the problem reaches a
     * caller who asked for problems.
     *
     * <p>Nothing continues past this, and nothing pretends to: the read is over and hands back no tree.
     *
     * <p>{@link JsonDiagnostics#ofBaseSyntaxError} rethrows anything that is not a syntax failure, so a
     * fault in this library still reaches the caller as itself: a bug is not a verdict on the document.
     *
     * <p><b>A limit refusal comes through here too, and is classified apart</b> ([TSON-JSON] §10.1 gives
     * this encoding [TSON-DATA] §9.1's bounds): the read ends the same way -- report once, hand back nothing
     * -- but what is reported says this processor declined rather than that the document is malformed.
     */
    private JsonValue readFailure(RuntimeException e) {
        receiver.report(e instanceof LimitExceededException limit
                ? Diagnostic.ofLimitExceeded(limit)
                : JsonDiagnostics.ofBaseSyntaxError(e));
        return null;
    }
}
