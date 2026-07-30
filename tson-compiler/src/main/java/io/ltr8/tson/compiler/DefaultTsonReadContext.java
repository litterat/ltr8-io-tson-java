package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The one backing implementation for both {@link TsonReadContext#throwing(TsonEventSource)} and
 * {@link TsonReadContext#collecting(TsonEventSource)}, parametrized by {@code failFast} rather than
 * two separate classes -- every other field is computed identically either way, only {@link #report}
 * itself branches. Package-private: a caller only ever reaches this through {@link TsonReadContext}'s
 * own static factories, never by name -- see that interface's own Javadoc for why it's an interface
 * at all.
 */
final class DefaultTsonReadContext implements TsonReadContext {

    /**
     * Every scoped copy of a single read (see {@link #field}/{@link #index}/{@link
     * #withSchemaPosition}/{@link #withPosition}) shares one of these -- the real event source, its
     * own live cursor position (mutated by {@link #peek}/{@link #next} regardless of which copy made
     * the call, since there is only ever one real cursor per read), and, in collecting mode, the
     * accumulating diagnostic sink.
     */
    private static final class Cursor {
        final TsonEventSource events;
        final List<Diagnostic> diagnostics;
        final boolean failFast;
        Optional<SourcePosition> position = Optional.empty();

        Cursor(TsonEventSource events, List<Diagnostic> diagnostics, boolean failFast) {
            this.events = events;
            this.diagnostics = diagnostics;
            this.failFast = failFast;
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

    static TsonReadContext throwing(TsonEventSource events) {
        return new DefaultTsonReadContext(new Cursor(events, null, true), "", Optional.empty(), Optional.empty());
    }

    static TsonReadContext collecting(TsonEventSource events) {
        return new DefaultTsonReadContext(new Cursor(events, new ArrayList<>(), false), "", Optional.empty(), Optional.empty());
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
    public boolean failFast() {
        return cursor.failFast;
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
        Diagnostic diagnostic = new Diagnostic(path, code, message, expected, actual, position(), schemaPosition);
        if (cursor.failFast) {
            throw new TsonReadException(diagnostic);
        }
        cursor.diagnostics.add(diagnostic);
    }

    @Override
    public List<Diagnostic> diagnostics() {
        return cursor.diagnostics == null ? List.of() : Collections.unmodifiableList(cursor.diagnostics);
    }

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
