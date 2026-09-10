package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;

import java.util.Optional;

/**
 * What a JSON read reports through, and where it is -- the peer of {@code tson-compiler}'s
 * {@code TsonReadContext}, and the reason a JSON read can collect every problem in one pass rather than
 * ending at the first.
 *
 * <p><b>It holds no error policy.</b> {@link #report} builds a {@link Diagnostic} from the path and
 * position it is already tracking and hands it to the read's {@link DiagnosticsReceiver}; that receiver
 * decides its fate and nothing else does. {@code throwing()} raises at the first problem, {@code
 * collecting()} accumulates and lets the read continue -- which is the whole point, and the thing a
 * reader that threw could not offer.
 *
 * <p><b>The path is built by stepping, not by concatenating.</b> {@link #field}/{@link #index} return a
 * context one step deeper, each holding a link to its parent; the RFC 6901 pointer is rendered only when
 * a diagnostic is actually built. Concatenating per step is quadratic in depth and thrown away by every
 * read that reports nothing, which is nearly all of them.
 *
 * <p><b>The schema end is present only when a schema is.</b> A schemaless read -- a tree, or a class's own
 * {@code tson-bind} descriptor playing the schema's part ([TSON-JSON] §4.1) -- has no document to point
 * into, so {@link #schemaLocation()} is empty and the three schema components of every {@link Diagnostic}
 * it builds stay empty with it. A schema-directed read ([TSON-JSON] §5-§8) offers each declaration as it
 * descends ({@link #underDeclaration}), and those components are filled from that.
 *
 * <p><b>Simpler than its TSON peer in two ways, each because this read has less to say.</b> There is no
 * lookahead or rewind, because the JSON engine pulls a value's opening event and passes it down rather than
 * peeking and putting back. And no name policy runs here yet; when it does it will run where the TSON side
 * runs it, on the event as it is pulled.
 *
 * <p>Not thread-safe, and single-use over one read: every derived context shares one cursor, so the
 * position and the reported count are the read's rather than any one step's.
 */
public final class JsonReadContext {

    /**
     * What every context derived from one read shares: the source being pulled, the receiver problems go
     * to, the position of the last event consumed, and how many problems have been reported.
     */
    private static final class Cursor {
        final JsonEventSource events;
        final DiagnosticsReceiver receiver;
        SourcePosition position;
        int reported;

        Cursor(JsonEventSource events, DiagnosticsReceiver receiver) {
            this.events = events;
            this.receiver = receiver;
        }
    }

    /** One step of the RFC 6901 pointer. A member name, or an array index where {@code name} is null. */
    private record PathStep(PathStep parent, String name, int index) {
    }

    private final Cursor cursor;

    /** The step this context sits at; {@code null} at the root, whose pointer is {@code ""}. */
    private final PathStep tail;

    /**
     * Where in the schema this step is; {@code null} for a read with no schema behind it. Per-context rather
     * than on the {@link Cursor}, because unlike the position it is not one live value the whole read shares:
     * a sibling that has finished reading must not leave its own declaration behind for the next one.
     */
    private final JsonSchemaLocation schemaLocation;

    private JsonReadContext(Cursor cursor, PathStep tail, JsonSchemaLocation schemaLocation) {
        this.cursor = cursor;
        this.tail = tail;
        this.schemaLocation = schemaLocation;
    }

    public static JsonReadContext of(JsonEventSource events, DiagnosticsReceiver receiver) {
        return new JsonReadContext(new Cursor(events, receiver), null, null);
    }

    // ── The cursor ───────────────────────────────────────────────────────

    public JsonEvent peek() {
        return cursor.events.peek();
    }

    public JsonEvent next() {
        JsonEvent e = cursor.events.next();
        cursor.position = e.position();
        return e;
    }

    /** Where the last event consumed began -- what a diagnostic points at. */
    public Optional<SourcePosition> position() {
        return Optional.ofNullable(cursor.position);
    }

    // ── Stepping ─────────────────────────────────────────────────────────

    /** This context at member {@code name} -- a new context, sharing this read's cursor. */
    public JsonReadContext field(String name) {
        return new JsonReadContext(cursor, new PathStep(tail, name, -1), schemaLocation);
    }

    /** This context at element {@code i}. */
    public JsonReadContext index(int i) {
        return new JsonReadContext(cursor, new PathStep(tail, null, i), schemaLocation);
    }

    /** The RFC 6901 pointer to where this context sits; {@code ""} at the root, which is a location and not an absence. */
    public String path() {
        StringBuilder out = new StringBuilder();
        append(out, tail);
        return out.toString();
    }

    private static void append(StringBuilder out, PathStep step) {
        if (step == null) {
            return;
        }
        append(out, step.parent());   // parent first: the chain links backwards, the pointer reads forwards
        out.append('/');
        if (step.name() != null) {
            out.append(escape(step.name()));
        } else {
            out.append(step.index());
        }
    }

    /** RFC 6901 §3: {@code ~} is {@code ~0} and {@code /} is {@code ~1}, in that order or the escape eats itself. */
    private static String escape(String name) {
        return name.indexOf('~') < 0 && name.indexOf('/') < 0
                ? name
                : name.replace("~", "~0").replace("/", "~1");
    }

    // ── The schema end ───────────────────────────────────────────────────

    /** Where in the schema this read currently is -- absent for a read with no schema behind it at all. */
    public Optional<JsonSchemaLocation> schemaLocation() {
        return Optional.ofNullable(schemaLocation);
    }

    /**
     * A copy of this context carrying {@code declaration} <em>only</em> if no schema location has been
     * established yet -- what every reader offers as it begins, so its own declaration locates a value read
     * at the root of a document without displacing the enclosing declaration's when there is one.
     *
     * <p>That is [TSON-JSON] §4.1's rule seen from the diagnostic's side: the position decides how a value
     * is read, so the position is what a report about it should name. An atom's own declaration is a seed;
     * the container that reached it owns the pointer.
     */
    public JsonReadContext underDeclaration(JsonSchemaLocation declaration) {
        return schemaLocation != null ? this : new JsonReadContext(cursor, tail, declaration);
    }

    // ── Reporting ────────────────────────────────────────────────────────

    /**
     * Hands one problem to this read's receiver, located at this context's pointer and the cursor's position,
     * and at its schema location where the read has one.
     *
     * <p>{@code Diagnostic} spells a missing schema identity {@code ""} and a missing pointer as an absence,
     * since for a pointer {@code ""} is the root and a real location -- so the two are read back through
     * {@code schemaIdIfKnown()} and the {@code Optional} rather than by remembering which convention each
     * component uses.
     */
    public void report(Diagnostic.Code code, String message, String expected, String actual) {
        cursor.reported++;
        cursor.receiver.report(new Diagnostic(Optional.of(path()),
                Optional.ofNullable(schemaLocation).map(JsonSchemaLocation::pointer),
                schemaLocation == null ? "" : schemaLocation.schemaId(), code, message, expected, actual,
                position(), Optional.ofNullable(schemaLocation).flatMap(JsonSchemaLocation::position)));
    }

    /**
     * How many problems this read has reported so far, counting every derived context since they share one
     * cursor.
     *
     * <p>Monotonic and independent of what the receiver does, so a reader can checkpoint around a child
     * read ({@code int before = ctx.reported(); ...; if (ctx.reported() > before)}) whether the receiver
     * collects, streams or throws. Two readers here need that: a container asking whether anything under it
     * failed before it builds itself, and a record asking whether the name it just pulled was refused.
     */
    public int reported() {
        return cursor.reported;
    }
}
