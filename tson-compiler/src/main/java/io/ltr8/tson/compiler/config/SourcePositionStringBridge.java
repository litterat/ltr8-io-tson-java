package io.ltr8.tson.compiler.config;

import io.ltr8.annotation.DataBridge;
import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.schema.meta.SourcePosition;

/**
 * Bridge for {@link SourcePosition}, converting to/from a compact {@code "line:column:byteOffset"}
 * string -- same reasoning as {@code tson-bind}'s own {@code PatternStringBridge}: {@code
 * SourcePosition} is an interface with no {@code sealed}/{@code @Union} signal {@code tson-bind}
 * could auto-detect (and couldn't be given one even if this project wanted to -- {@code
 * schema.meta.TypeDefinition}'s own {@code position} field is typed {@code SourcePosition}
 * specifically so {@code tson-schema} never has to name {@link Position}, its own real
 * implementation, at all), so a caller registers this explicitly. Lives here, not {@code
 * tson-bind.bridge}, since -- unlike {@code Pattern}/{@code EnumStringBridge}'s own targets -- both
 * {@code SourcePosition} and {@link Position} are this project's own types, not generic reusable
 * ones.
 *
 * <p>{@link #toObject} always produces a real {@link Position} -- the only concrete {@code
 * SourcePosition} that exists anywhere in this codebase -- even though nothing currently reads a
 * {@code TypeDefinition} generically from data (only writes one, for test verification via {@code
 * TsonObjectWriter.toTson}); implementing the read direction correctly costs nothing and avoids
 * leaving a bridge that's only honest in one direction.
 */
public final class SourcePositionStringBridge implements DataBridge<String, SourcePosition> {

    public SourcePositionStringBridge() {
    }

    @Override
    public String toData(SourcePosition b) {
        return b.line() + ":" + b.column() + ":" + b.byteOffset();
    }

    @Override
    public SourcePosition toObject(String s) {
        String[] parts = s.split(":", 3);
        return new Position(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
}
