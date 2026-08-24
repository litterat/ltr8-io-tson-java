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

    /**
     * One step of the descent, linked to the step before it -- {@code /lines}, {@code /2}, {@code /sku} as
     * three nodes rather than three progressively longer strings.
     *
     * <p><b>Both pointers a diagnostic carries are built from this, and only when one is built.</b> A read
     * that reports nothing -- which is every read of a valid document, the overwhelming majority -- pays a
     * node per step and no string at all, where concatenating eagerly cost a {@code StringBuilder}, a
     * {@code String} and its array at every field of every value, then threw them away. The structure is
     * what matters beyond this implementation: any port descends the same way and would inherit the same
     * waste from the same shape.
     *
     * <p>{@code schemaToo} is what keeps the two pointers apart. Every schema step is a data step, but not
     * the reverse: an array index moves through the document without moving through the schema, whose
     * element type is declared once for the whole array. So one chain carries both, and the schema pointer
     * renders the marked subset.
     *
     * @param name  the field name, or {@code null} for an array index
     * @param index the array index, meaningful only when {@code name} is {@code null}
     */
    private record PathStep(PathStep parent, String name, int index, boolean schemaToo) {
    }

    private final Cursor cursor;

    /** The step this context sits at; {@code null} at the root, whose path is {@code ""}. */
    private final PathStep tail;

    /**
     * The schema pointer's own root -- the declaration the descent entered through, e.g. {@code "/person"}
     * -- or {@code null} where no schema is in scope. Held apart from {@link #tail} because a re-anchoring
     * record replaces the identity and the line while the pointer keeps growing ({@link SchemaLocation}).
     */
    private final String schemaRoot;
    private final String schemaId;
    private final Optional<SourcePosition> schemaPosition;

    private final Optional<SourcePosition> positionOverride;

    private DefaultTsonReadContext(Cursor cursor, PathStep tail, String schemaRoot, String schemaId,
                                    Optional<SourcePosition> schemaPosition,
                                    Optional<SourcePosition> positionOverride) {
        this.cursor = cursor;
        this.tail = tail;
        this.schemaRoot = schemaRoot;
        this.schemaId = schemaId;
        this.schemaPosition = schemaPosition;
        this.positionOverride = positionOverride;
    }

    static TsonReadContext of(TsonEventSource events, TsonDiagnosticsReceiver receiver) {
        return new DefaultTsonReadContext(new Cursor(events, receiver), null, null, null,
                Optional.empty(), Optional.empty());
    }

    private DefaultTsonReadContext stepping(PathStep step) {
        return new DefaultTsonReadContext(cursor, step, schemaRoot, schemaId, schemaPosition, positionOverride);
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
     * {@link TsonReadContext#lookingAhead}'s implementation, which carries the contract and the reasoning.
     * The cast cannot fail for a context this library made: this class is the interface's one implementation
     * (see the class note), and every context reaches a reader through {@link TsonReadContext#of}.
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

    /** Built on demand, from {@link #schemaRoot} plus the marked steps -- see {@link PathStep}. */
    @Override
    public Optional<SchemaLocation> schemaLocation() {
        if (schemaRoot == null) {
            return Optional.empty();
        }
        return Optional.of(new SchemaLocation(schemaId, render(true), schemaPosition));
    }

    /** Built on demand, from every step -- see {@link PathStep}. */
    @Override
    public String path() {
        return render(false);
    }

    /**
     * The RFC 6901 pointer {@link #tail}'s chain spells: every step for the data path, the {@code schemaToo}
     * ones for the schema pointer. Rendered nowhere else, and reached only by {@link #report} and a caller
     * that asks.
     */
    private String render(boolean schemaOnly) {
        StringBuilder out = new StringBuilder();
        if (schemaOnly) {
            out.append(schemaRoot);
        }
        append(out, tail, schemaOnly);
        return out.toString();
    }

    private static void append(StringBuilder out, PathStep step, boolean schemaOnly) {
        if (step == null) {
            return;
        }
        append(out, step.parent(), schemaOnly);   // parent first: the chain links backwards, the pointer reads forwards
        if (schemaOnly && !step.schemaToo()) {
            return;
        }
        out.append('/');
        if (step.name() != null) {
            out.append(escape(step.name()));
        } else {
            out.append(step.index());
        }
    }

    @Override
    public TsonReadContext field(String name) {
        return stepping(new PathStep(tail, name, -1, false));
    }

    @Override
    public TsonReadContext index(int i) {
        return stepping(new PathStep(tail, null, i, false));
    }

    @Override
    public TsonReadContext schemaField(String name) {
        return stepping(new PathStep(tail, name, -1, schemaRoot != null));
    }

    @Override
    public TsonReadContext inRecord(SchemaLocation declaration) {
        // The pointer survives, the anchor does not: this record declares the field the pointer now ends
        // with, so it is what a reader following the pointer needs opened. Only an outermost record, with no
        // pointer to extend, contributes its own name as the path's root.
        if (schemaRoot == null) {
            return anchoredOn(declaration.pointer(), declaration.schemaId(), declaration.position());
        }
        return anchoredOn(schemaRoot, declaration.schemaId(),
                declaration.position().isPresent() ? declaration.position() : schemaPosition);
    }

    @Override
    public TsonReadContext underDeclaration(SchemaLocation declaration) {
        return schemaRoot != null
                ? this
                : anchoredOn(declaration.pointer(), declaration.schemaId(), declaration.position());
    }

    private TsonReadContext anchoredOn(String root, String id, Optional<SourcePosition> position) {
        return new DefaultTsonReadContext(cursor, tail, root, id, position, positionOverride);
    }

    @Override
    public TsonReadContext withPosition(Optional<SourcePosition> position) {
        return new DefaultTsonReadContext(cursor, tail, schemaRoot, schemaId, schemaPosition, position);
    }

    @Override
    public void report(Diagnostic.Code code, String message, String expected, String actual) {
        // All three schema-end components come from the one SchemaLocation the descent accumulated, so they
        // cannot disagree about which document to open. A read with no schema behind it carries none of them:
        // Diagnostic spells a missing identity "" and a missing pointer as an absence, since for a pointer
        // "" is the root.
        Diagnostic diagnostic = new Diagnostic(Optional.of(render(false)),
                schemaRoot == null ? Optional.empty() : Optional.of(render(true)),
                schemaRoot == null ? "" : schemaId, code, message, expected, actual,
                position(), schemaRoot == null ? Optional.empty() : schemaPosition);
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
