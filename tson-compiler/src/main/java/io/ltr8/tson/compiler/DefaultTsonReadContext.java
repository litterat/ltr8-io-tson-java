package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

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
     * #withSchemaPosition}/{@link #withPosition}) shares one of these -- the real event source, its
     * own live cursor position (mutated by {@link #peek}/{@link #next} regardless of which copy made
     * the call, since there is only ever one real cursor per read), the receiver every copy reports
     * through, and the running count of what they have reported.
     */
    private static final class Cursor {
        final TsonEventSource events;
        final TsonDiagnosticsReceiver receiver;
        Optional<SourcePosition> position = Optional.empty();
        int reported;

        Cursor(TsonEventSource events, TsonDiagnosticsReceiver receiver) {
            this.events = events;
            this.receiver = receiver;
        }
    }

    private final Cursor cursor;
    private final String path;
    private final Optional<SourcePosition> schemaPosition;
    private final Optional<SourcePosition> positionOverride;

    private DefaultTsonReadContext(Cursor cursor, String path, Optional<SourcePosition> schemaPosition,
                                    Optional<SourcePosition> positionOverride) {
        this.cursor = cursor;
        this.path = path;
        this.schemaPosition = schemaPosition;
        this.positionOverride = positionOverride;
    }

    static TsonReadContext of(TsonEventSource events, TsonDiagnosticsReceiver receiver) {
        return new DefaultTsonReadContext(new Cursor(events, receiver), "", Optional.empty(), Optional.empty());
    }

    @Override
    public TsonEvent peek() {
        TsonEvent e = cursor.events.peek();
        cursor.position = Optional.of(e.position());
        return e;
    }

    @Override
    public TsonEvent next() {
        TsonEvent e = cursor.events.next();
        cursor.position = Optional.of(e.position());
        return e;
    }

    @Override
    public Optional<SourcePosition> position() {
        return positionOverride.isPresent() ? positionOverride : cursor.position;
    }

    @Override
    public Optional<SourcePosition> schemaPosition() {
        return schemaPosition;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public TsonReadContext field(String name) {
        return new DefaultTsonReadContext(cursor, path + "/" + escape(name), schemaPosition, positionOverride);
    }

    @Override
    public TsonReadContext index(int i) {
        return new DefaultTsonReadContext(cursor, path + "/" + i, schemaPosition, positionOverride);
    }

    @Override
    public TsonReadContext withSchemaPosition(Optional<SourcePosition> schemaPosition) {
        return new DefaultTsonReadContext(cursor, path, schemaPosition, positionOverride);
    }

    @Override
    public TsonReadContext withPosition(Optional<SourcePosition> position) {
        return new DefaultTsonReadContext(cursor, path, schemaPosition, position);
    }

    @Override
    public void report(Diagnostic.Code code, String message, String expected, String actual) {
        // The schema end carries a position but no pointer or identity: a reader knows the declaration
        // position it stamped, not which entry of which schema it came from. Populating those means
        // threading the compiled schema's identity down the reader stack -- see BACKLOG.md.
        Diagnostic diagnostic = new Diagnostic(Optional.of(path), Optional.empty(), "", code, message,
                expected, actual, position(), schemaPosition);
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
