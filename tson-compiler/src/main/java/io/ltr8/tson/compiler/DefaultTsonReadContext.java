package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one backing implementation for both {@link TsonReadContext#throwing()} and {@link
 * TsonReadContext#collecting}, parametrized by {@code failFast} rather than two separate classes --
 * every other field is computed identically either way, only {@link #report} itself branches.
 * Package-private: a caller only ever reaches this through {@link TsonReadContext}'s own static
 * factories, never by name -- see that interface's own Javadoc for why it's an interface at all.
 */
final class DefaultTsonReadContext implements TsonReadContext {

    private final Map<CoreValue, Position> dataPositions;
    private final String path;
    private final Optional<SourcePosition> position;
    private final Optional<SourcePosition> schemaPosition;
    private final List<Diagnostic> diagnostics;
    private final boolean failFast;

    private DefaultTsonReadContext(Map<CoreValue, Position> dataPositions, String path, Optional<SourcePosition> position,
                                    Optional<SourcePosition> schemaPosition, List<Diagnostic> diagnostics, boolean failFast) {
        this.dataPositions = dataPositions;
        this.path = path;
        this.position = position;
        this.schemaPosition = schemaPosition;
        this.diagnostics = diagnostics;
        this.failFast = failFast;
    }

    static TsonReadContext throwing(Map<CoreValue, Position> dataPositions) {
        return new DefaultTsonReadContext(dataPositions, "", Optional.empty(), Optional.empty(), null, true);
    }

    static TsonReadContext collecting(Map<CoreValue, Position> dataPositions) {
        return new DefaultTsonReadContext(dataPositions, "", Optional.empty(), Optional.empty(), new ArrayList<>(), false);
    }

    @Override
    public Optional<SourcePosition> position() {
        return position;
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
        return failFast;
    }

    /**
     * A missing value ({@code value == null}, e.g. a missing required field) has no {@code CoreValue}
     * of its own to look up, so this keeps the *current* {@link #position()} rather than clearing it
     * to empty -- the enclosing record/array/tuple's own position remains the nearest real anchor a
     * caller (e.g. an LLM retry loop) can point at in the actual submitted text, and is strictly more
     * useful than reporting no position at all.
     */
    @Override
    public TsonReadContext field(String name, DataValue value) {
        Optional<SourcePosition> childPosition = value == null ? position : positionOf(value);
        return new DefaultTsonReadContext(dataPositions, path + "/" + escape(name), childPosition, schemaPosition,
                diagnostics, failFast);
    }

    @Override
    public TsonReadContext index(int i, DataValue value) {
        Optional<SourcePosition> childPosition = value == null ? position : positionOf(value);
        return new DefaultTsonReadContext(dataPositions, path + "/" + i, childPosition, schemaPosition,
                diagnostics, failFast);
    }

    @Override
    public TsonReadContext withSchemaPosition(Optional<SourcePosition> schemaPosition) {
        return new DefaultTsonReadContext(dataPositions, path, position, schemaPosition, diagnostics, failFast);
    }

    @Override
    public TsonReadContext at(DataValue value) {
        return new DefaultTsonReadContext(dataPositions, path, positionOf(value), schemaPosition, diagnostics, failFast);
    }

    @Override
    public void report(Diagnostic.Code code, String message, String expected, String actual) {
        Diagnostic diagnostic = new Diagnostic(path, code, message, expected, actual, position, schemaPosition);
        if (failFast) {
            throw new TsonReadException(diagnostic);
        }
        diagnostics.add(diagnostic);
    }

    @Override
    public List<Diagnostic> diagnostics() {
        return diagnostics == null ? List.of() : Collections.unmodifiableList(diagnostics);
    }

    private Optional<SourcePosition> positionOf(DataValue value) {
        if (value == null) {
            return Optional.empty();
        }
        return Optional.<SourcePosition>ofNullable(dataPositions.get(value.coreValue()));
    }

    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
