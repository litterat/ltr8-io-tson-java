package io.ltr8.tson.json;

import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.reader.JsonReadContext;
import io.ltr8.tson.json.reader.SchemalessTreeReader;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonValue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

    private JsonTreeReader(ProcessorPolicy policy, DiagnosticsReceiver receiver) {
        this.policy = policy;
        this.receiver = receiver;
    }

    /** Under the processor's defaults, raising what it refuses. */
    public static JsonTreeReader standard() {
        return new JsonTreeReader(ProcessorPolicy.defaults(), DiagnosticsReceiver.throwing());
    }

    /**
     * This reader under {@code policy} -- a new reader, leaving this one unchanged.
     *
     * <p>The only policy derivation here, for {@link JsonObjectReader#withProcessorPolicy}'s reason:
     * {@code ProcessorPolicy} already carries the component derivations, so a caller changing one says
     * {@code r.withProcessorPolicy(r.processorPolicy().withTokenPolicy(p))}.
     */
    public JsonTreeReader withProcessorPolicy(ProcessorPolicy policy) {
        return new JsonTreeReader(policy, receiver);
    }

    /** This reader routing its problems to {@code receiver} -- a new reader, leaving this one unchanged. */
    public JsonTreeReader withDiagnostics(DiagnosticsReceiver receiver) {
        return new JsonTreeReader(policy, receiver);
    }

    /** Everything this reader will admit and spend, for a caller stating it beside a read's diagnostics. */
    public ProcessorPolicy processorPolicy() {
        return policy;
    }

    // ── Reading ──────────────────────────────────────────────────────────

    /** A string as the bytes this encoding reads -- §3.1 makes the document UTF-8 and the lexer takes bytes. */
    public JsonValue read(String source) {
        return read(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code source} is not closed here. */
    public JsonValue read(InputStream source) {
        return read(new JsonStream(source, policy, receiver));
    }

    /**
     * Off an event source, which is the seam the two above come through.
     *
     * <p>The source is drained through {@link JsonEvent.EndOfDocument} -- the pull past the root value is
     * what rejects trailing content, so a read that stopped at the root's last event would accept
     * {@code "[1] 2"}.
     */
    public JsonValue read(JsonEventSource events) {
        JsonReadContext ctx = JsonReadContext.of(events, receiver);
        JsonValue root = ENGINE.read(ctx);
        if (!(events.next() instanceof JsonEvent.EndOfDocument)) {
            throw new IllegalStateException("the stream produced events after the document's root value");
        }
        return root;
    }
}
