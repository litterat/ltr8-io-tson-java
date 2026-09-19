package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClassBridge;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

/**
 * A bound component whose class is reached through a {@link DataClassBridge}: the position reads its wire value,
 * and the bridge makes the component's own class of it -- a registered third-party type, an enum constant, a
 * value class, or a wrapper over a record ({@code ToData}, {@code @Transparent}).
 *
 * <p>The bridge refusing is the class's own rule refusing the content. Over an atom that is a constraint on the
 * value, reported as one -- the same answer the schemaless bind read gives; over a record it is the class
 * rejecting what was read for it, the answer a bound record's own constructor gets.
 */
final class BridgedReader implements JsonTypeReader<Object> {

    private final JsonTypeReader<?> wire;
    private final DataClassBridge bridge;
    private final Class<?> type;
    private final boolean atom;

    private BridgedReader(JsonTypeReader<?> wire, DataClassBridge bridge, Class<?> type, boolean atom) {
        this.wire = wire;
        this.bridge = bridge;
        this.type = type;
        this.atom = atom;
    }

    static BridgedReader ofAtom(JsonTypeReader<?> wire, DataClassBridge bridge, Class<?> type) {
        return new BridgedReader(wire, bridge, type, true);
    }

    static BridgedReader ofRecord(JsonTypeReader<?> wire, DataClassBridge bridge, Class<?> type) {
        return new BridgedReader(wire, bridge, type, false);
    }

    @Override
    public Object read(JsonReadContext ctx) {
        Object value = wire.read(ctx);
        if (value == null) {
            return null;
        }
        try {
            return bridge.toObject().invoke(value);
        } catch (Throwable e) {
            if (atom) {
                ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "'%s' is not a %s: %s"
                        .formatted(value, type.getSimpleName(), e.getMessage()), "a " + type.getSimpleName(),
                        String.valueOf(value));
            } else {
                BindRecordBuilder.rejected(ctx, type, e);
            }
            return null;
        }
    }
}
