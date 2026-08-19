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
     * #withSchemaLocation}/{@link #withPosition}) shares one of these -- the real event source, its
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
    public TsonReadContext withSchemaLocation(SchemaLocation schemaLocation) {
        return new DefaultTsonReadContext(cursor, path, Optional.of(schemaLocation), positionOverride);
    }

    @Override
    public TsonReadContext withPosition(Optional<SourcePosition> position) {
        return new DefaultTsonReadContext(cursor, path, schemaLocation, position);
    }

    @Override
    public void report(Diagnostic.Code code, String message, String expected, String actual) {
        // The schema end carries a pointer and a position but no identity: a reader knows which entry it was
        // built for, not which schema that entry came from -- TsonSchemaLinker.mergeImports copies an imported
        // TypeDefinition into the importing schema's entries and keeps no origin, so by compile time there is
        // nothing correct to stamp. Reaching for the compiled schema's own id would pair the *importing*
        // schema with an *imported* declaration's line, which is worse than absent. Recording the origin at
        // merge time is the prerequisite -- see BACKLOG.md.
        Diagnostic diagnostic = new Diagnostic(Optional.of(path), schemaLocation.map(SchemaLocation::pointer),
                "", code, message, expected, actual, position(), schemaLocation.flatMap(SchemaLocation::position));
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
