package io.ltr8.tson.json.reader;

import io.ltr8.bind.DataClassBridge;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

/**
 * A bound component whose class is reached through a {@link DataClassBridge}: the position reads its wire value,
 * and the bridge makes the component's own class of it -- a registered third-party type, an enum constant, a
 * value class. The bridge refusing is the class's own rule refusing the content, which is a constraint on the
 * value and is reported as one, the same answer the schemaless bind read gives.
 */
final class BridgedReader implements JsonTypeReader<Object> {

    private final JsonTypeReader<?> wire;
    private final DataClassBridge bridge;
    private final Class<?> type;

    BridgedReader(JsonTypeReader<?> wire, DataClassBridge bridge, Class<?> type) {
        this.wire = wire;
        this.bridge = bridge;
        this.type = type;
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
            ctx.report(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, "'%s' is not a %s: %s"
                    .formatted(value, type.getSimpleName(), e.getMessage()), "a " + type.getSimpleName(),
                    String.valueOf(value));
            return null;
        }
    }
}
