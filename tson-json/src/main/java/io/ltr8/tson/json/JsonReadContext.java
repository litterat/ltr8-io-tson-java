package io.ltr8.tson.json;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.base.diagnostics.Refusal;
import io.ltr8.tson.base.policy.UnicodePolicy;
import io.ltr8.tson.json.stream.JsonEvent;
import io.ltr8.tson.json.stream.JsonEventSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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

        /** Events a lookahead consumed and put back, served before the source is pulled again. */
        final Deque<JsonEvent> rewound = new ArrayDeque<>();

        /** Where a lookahead in progress collects what it consumes, or null when none is running. */
        List<JsonEvent> recording;

        /** [TSON-DATA] §8.2's identifier policy, for the read positions that carry a name. */
        final UnicodePolicy identifierPolicy;

        Cursor(JsonEventSource events, DiagnosticsReceiver receiver, UnicodePolicy identifierPolicy) {
            this.events = events;
            this.receiver = receiver;
            this.identifierPolicy = identifierPolicy;
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

    /**
     * A position pinned by {@link #withPosition}, or null to follow the shared cursor. Per-context for the
     * same reason the schema location is: it belongs to one step's report, not to the read.
     */
    private final SourcePosition pinnedPosition;

    private JsonReadContext(Cursor cursor, PathStep tail, JsonSchemaLocation schemaLocation,
                            SourcePosition pinnedPosition) {
        this.cursor = cursor;
        this.tail = tail;
        this.schemaLocation = schemaLocation;
        this.pinnedPosition = pinnedPosition;
    }

    /**
     * A context over {@code events} that judges no name.
     *
     * <p><b>The default is "nothing" rather than [TSON-DATA] §8.2's recommended level, and that differs from
     * the TSON context deliberately.</b> There, every name the stream carries is a name, so a policy is always
     * applicable and defaulting to Highly Restrictive is right. Here it depends on the position: a JSON object
     * is one syntax for a record and a map both (§4.1), so only a reader holding the position knows whether a
     * member name is a field name or a key -- and a schemaless read holds no position at all, so it applies
     * nothing. {@link #of(JsonEventSource, DiagnosticsReceiver, UnicodePolicy)} is what a read that does know.
     */
    public static JsonReadContext of(JsonEventSource events, DiagnosticsReceiver receiver) {
        return of(events, receiver, UnicodePolicy.unrestricted());
    }

    /** As above, under {@code identifierPolicy} -- what a schema-directed read passes, knowing its positions. */
    public static JsonReadContext of(JsonEventSource events, DiagnosticsReceiver receiver,
                                     UnicodePolicy identifierPolicy) {
        return new JsonReadContext(new Cursor(events, receiver,
                Objects.requireNonNull(identifierPolicy, "identifierPolicy")), null, null, null);
    }

    /** The identifier policy this read judges names under -- [TSON-DATA] §8.2's, never a validity rule. */
    public UnicodePolicy identifierPolicy() {
        return cursor.identifierPolicy;
    }

    // ── The cursor ───────────────────────────────────────────────────────

    public JsonEvent peek() {
        return cursor.rewound.isEmpty() ? cursor.events.peek() : cursor.rewound.peekFirst();
    }

    public JsonEvent next() {
        JsonEvent e = cursor.rewound.isEmpty() ? cursor.events.next() : cursor.rewound.removeFirst();
        cursor.position = e.position();
        if (cursor.recording != null) {
            cursor.recording.add(e);
        }
        return e;
    }

    /**
     * Runs {@code lookahead} against this read's cursor and then rewinds every event it consumed, so
     * whatever reads next sees a stream nothing has touched.
     *
     * <p><b>Why one event of peek is not enough.</b> {@link #peek()} answers "what is here". Recognising an
     * annotation object ([TSON-JSON] §3.3) asks something else: whether this object carries any member of
     * the reserved set, and §6.1.6 gives member order no meaning -- so {@code $type} may sit anywhere in it
     * and the decision cannot be made from the opening brace. Reading the members to find out is not a
     * substitute, because the reader that ends up building the value must see them all.
     *
     * <p>Consumed events are replayed from a buffer rather than re-lexed, so a lookahead costs what it
     * looked past and never the document; a scan for reserved names skips values without materialising
     * them, so what it looks past is one object's member names. {@link #position()} is deliberately left
     * where the lookahead reached rather than restored: a caller looks ahead in order to say something
     * about what it found, and that is where the saying belongs.
     *
     * <p>A static method taking the context rather than an instance method, so nothing about it has to be
     * re-stated by a caller holding a derived copy: every copy shares one cursor, and this is the cursor's.
     */
    public static <T> T lookingAhead(JsonReadContext ctx, Function<JsonReadContext, T> lookahead) {
        Cursor cursor = ctx.cursor;
        List<JsonEvent> consumed = new ArrayList<>();
        List<JsonEvent> outer = cursor.recording;
        cursor.recording = consumed;
        try {
            return lookahead.apply(ctx);
        } finally {
            cursor.recording = outer;
            for (int i = consumed.size() - 1; i >= 0; i--) {
                cursor.rewound.addFirst(consumed.get(i));
            }
        }
    }

    /** Where the last event consumed began -- what a diagnostic points at. */
    public Optional<SourcePosition> position() {
        return Optional.ofNullable(pinnedPosition != null ? pinnedPosition : cursor.position);
    }

    // ── Stepping ─────────────────────────────────────────────────────────

    /** This context at member {@code name} -- a new context, sharing this read's cursor. */
    public JsonReadContext field(String name) {
        return new JsonReadContext(cursor, new PathStep(tail, name, -1), schemaLocation, pinnedPosition);
    }

    /** This context at element {@code i}. */
    public JsonReadContext index(int i) {
        return new JsonReadContext(cursor, new PathStep(tail, null, i), schemaLocation, pinnedPosition);
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
        return schemaLocation != null ? this : new JsonReadContext(cursor, tail, declaration, pinnedPosition);
    }

    /**
     * A copy of this context anchored on the record now being read: {@code declaration}'s identity and line
     * replace whatever was there, and its pointer is taken only if none has been established.
     *
     * <p>The asymmetry with {@link #underDeclaration} is the one that keeps a schema pointer useful. A record
     * <em>declares</em> the member the pointer ends with, so it owns the identity and the line; an atom or a
     * container reached from that member does not, and offering its own would name the file the type came
     * from -- core.tn -- rather than the line the author can edit.
     */
    public JsonReadContext inRecord(JsonSchemaLocation declaration) {
        return new JsonReadContext(cursor, tail,
                schemaLocation == null ? declaration : schemaLocation.anchoredOn(declaration), pinnedPosition);
    }

    /**
     * A copy of this context one <em>declared</em> member deeper, stepping the data path, the schema pointer
     * and the schema line together -- the one descent where the schema has a name of its own for where the
     * read went.
     *
     * <p>{@code fieldPosition} is a parameter rather than a second overload because every caller has the field
     * in hand: two ways to take this descent would mean the one that forgot the line was the shorter one.
     */
    public JsonReadContext schemaField(String name, Optional<SourcePosition> fieldPosition) {
        return new JsonReadContext(cursor, new PathStep(tail, name, -1),
                schemaLocation == null ? null : schemaLocation.field(name, fieldPosition), pinnedPosition);
    }

    /**
     * A copy of this context whose {@link #position()} is pinned rather than following the shared cursor.
     *
     * <p>A field the document never mentioned has no event of its own to be noticed at, so it can only be
     * noticed after the whole enclosing object has been consumed -- by which time the live cursor is sitting
     * on the closing brace or past it. Pinning the object's own opening position puts the report where the
     * reader would look for the missing member.
     */
    public JsonReadContext withPosition(SourcePosition pinned) {
        return new JsonReadContext(cursor, tail, schemaLocation, pinned);
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
    /**
     * Hands one of a family's rules to this read's receiver, located here.
     *
     * <p>The rule states four of a {@link Diagnostic}'s nine components and this context supplies the other
     * five ({@code base.diagnostics}), so the unpacking lives here rather than in each reader -- one place
     * adds a location, so one place takes a {@link Refusal} apart.
     */
    public void report(Refusal refusal) {
        report(refusal.code(), refusal.message(), refusal.expected(), refusal.actual());
    }

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
