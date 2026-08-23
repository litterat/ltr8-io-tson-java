package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The one backing implementation of {@link TsonReadContext}. It holds no policy of its own -- {@link #report}
 * enriches a problem with the path and positions tracked here and hands it to the read's {@link
 * TsonDiagnosticsReceiver}, which decides what happens next. Package-private: a caller only ever reaches this
 * through {@link TsonReadContext}'s own static factories, never by name -- see that interface's own Javadoc for
 * why it's an interface at all.
 */
final class DefaultTsonReadContext implements TsonReadContext {

    /**
     * Every scoped copy of a single read (see {@link #field}/{@link #index}/{@link
     * #schemaField}/{@link #withPosition}) shares one of these -- the real event source, its
     * own live cursor position (mutated by {@link #peek}/{@link #next} regardless of which copy made
     * the call, since there is only ever one real cursor per read), the receiver every copy reports
     * through, and the running count of what they have reported.
     */
    private static final class Cursor {
        final TsonEventSource events;
        final TsonDiagnosticsReceiver receiver;
        Optional<SourcePosition> position = Optional.empty();
        int reported;

        /**
         * Events consumed by a {@link #lookingAhead} pass and rewound, replayed ahead of {@code events}
         * until they run out. Empty for the whole of an ordinary read -- the one thing that fills it is a
         * caller that had to look further than {@link #peek()} reaches to know what to do next.
         */
        final Deque<TsonEvent> rewound = new ArrayDeque<>();

        /** Where {@link #next()} records what it consumes while a lookahead is running, else {@code null}. */
        List<TsonEvent> recording;

        Cursor(TsonEventSource events, TsonDiagnosticsReceiver receiver) {
            this.events = events;
            this.receiver = receiver;
        }
    }

    private final Cursor cursor;
    private final String path;
    private final Optional<SchemaLocation> schemaLocation;
    private final Optional<SourcePosition> positionOverride;

    private DefaultTsonReadContext(Cursor cursor, String path, Optional<SchemaLocation> schemaLocation,
                                    Optional<SourcePosition> positionOverride) {
        this.cursor = cursor;
        this.path = path;
        this.schemaLocation = schemaLocation;
        this.positionOverride = positionOverride;
    }

    static TsonReadContext of(TsonEventSource events, TsonDiagnosticsReceiver receiver) {
        return new DefaultTsonReadContext(new Cursor(events, receiver), "", Optional.empty(), Optional.empty());
    }

    @Override
    public TsonEvent peek() {
        TsonEvent e = cursor.rewound.isEmpty() ? cursor.events.peek() : cursor.rewound.peekFirst();
        cursor.position = Optional.of(e.position());
        return e;
    }

    @Override
    public TsonEvent next() {
        TsonEvent e = cursor.rewound.isEmpty() ? cursor.events.next() : cursor.rewound.removeFirst();
        cursor.position = Optional.of(e.position());
        if (cursor.recording != null) {
            cursor.recording.add(e);
        }
        return e;
    }

    /**
     * Runs {@code lookahead} against this read's cursor and then rewinds every event it consumed, so the
     * readers that follow see a stream nothing has touched.
     *
     * <p><b>Why this exists at all</b>, when {@link #peek()} is the primitive every reader uses: one event
     * of lookahead answers "what is here", and a caller occasionally has to answer "what is here <em>after
     * the part that can repeat</em>". A document's root is the case -- {@code data-value = *annotation
     * [type-ref] core-value}, so the type-ref that selects the root reader can sit behind any number of
     * annotations, and a single peek finds the first annotation and concludes there is no type-ref. Reading
     * the annotations to get past them is not an option on its own: they belong to the value, and a reader
     * that never sees them drops them from what it builds.
     *
     * <p><b>Rewinding, not re-parsing.</b> The consumed events are replayed from a buffer, so the source is
     * pulled exactly once and a lookahead over an annotation holds only that annotation's own events --
     * proportional to what was looked past, never to the document. {@link #position()} is deliberately left
     * where the lookahead put it rather than restored: a caller looks ahead in order to say something about
     * what it found, and what it found is where that belongs.
     *
     * <p>Takes a {@link TsonReadContext} rather than being an instance method because every caller holds the
     * interface; this class is its one implementation (see the class note), so the cast cannot fail for a
     * context this library made.
     */
    static <T> T lookingAhead(TsonReadContext ctx, Function<TsonReadContext, T> lookahead) {
        Cursor cursor = ((DefaultTsonReadContext) ctx).cursor;
        List<TsonEvent> consumed = new ArrayList<>();
        List<TsonEvent> outer = cursor.recording;
        cursor.recording = consumed;
        try {
            return lookahead.apply(ctx);
        } finally {
            cursor.recording = outer;
            // Front of the queue, in the order they were read: these events precede whatever is still
            // unread, and a nested lookahead's rewind must land ahead of the enclosing one's.
            // The rewound queue is the single record of what has been read and put back: an enclosing
            // lookahead does not also take these, or it would push back a second copy of events this one
            // has already returned. It records them again itself if it reads through them.
            for (int i = consumed.size() - 1; i >= 0; i--) {
                cursor.rewound.addFirst(consumed.get(i));
            }
        }
    }

    @Override
    public Optional<SourcePosition> position() {
        return positionOverride.isPresent() ? positionOverride : cursor.position;
    }

    @Override
    public Optional<SchemaLocation> schemaLocation() {
        return schemaLocation;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public TsonReadContext field(String name) {
        return new DefaultTsonReadContext(cursor, path + "/" + escape(name), schemaLocation, positionOverride);
    }

    @Override
    public TsonReadContext index(int i) {
        return new DefaultTsonReadContext(cursor, path + "/" + i, schemaLocation, positionOverride);
    }

    @Override
    public TsonReadContext schemaField(String name) {
        return new DefaultTsonReadContext(cursor, path + "/" + escape(name),
                schemaLocation.map(location -> location.field(name)), positionOverride);
    }

    @Override
    public TsonReadContext inRecord(SchemaLocation declaration) {
        // The pointer survives, the anchor does not: this record declares the field the pointer now ends
        // with, so it is what a reader following the pointer needs opened. Only an outermost record, with no
        // pointer to extend, contributes its own name as the path's root.
        return new DefaultTsonReadContext(cursor, path,
                Optional.of(schemaLocation.map(location -> location.anchoredOn(declaration)).orElse(declaration)),
                positionOverride);
    }

    @Override
    public TsonReadContext underDeclaration(SchemaLocation declaration) {
        return schemaLocation.isPresent()
                ? this
                : new DefaultTsonReadContext(cursor, path, Optional.of(declaration), positionOverride);
    }

    @Override
    public TsonReadContext withPosition(Optional<SourcePosition> position) {
        return new DefaultTsonReadContext(cursor, path, schemaLocation, position);
    }

    @Override
    public void report(Diagnostic.Code code, String message, String expected, String actual) {
        // All three schema-end components come from the one SchemaLocation the descent accumulated, so they
        // cannot disagree about which document to open. A read with no schema behind it carries none of them:
        // Diagnostic spells a missing identity "" and a missing pointer as an absence, since for a pointer
        // "" is the root.
        Diagnostic diagnostic = new Diagnostic(Optional.of(path), schemaLocation.map(SchemaLocation::pointer),
                schemaLocation.map(SchemaLocation::schemaId).orElse(""), code, message, expected, actual,
                position(), schemaLocation.flatMap(SchemaLocation::position));
        cursor.reported++;
        cursor.receiver.report(diagnostic);
    }

    @Override
    public int reported() {
        return cursor.reported;
    }

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
