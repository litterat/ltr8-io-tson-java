package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.diagnostics.TupleDiagnostics;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * What a tuple's schema fixes, resolved once when the schema compiles: [TSON-JSON] §6.4's half that does not
 * depend on what the read builds -- the arity, each position's state and schema reader, and the diagnostics. A
 * mode's factory decides which reader each position is read at and hands both to {@link TupleReader}; bind mode
 * keeps the plan to build the same position again for a component's own tuple class ({@link BindTargets}).
 */
record TuplePlan(String displayName, JsonSchemaLocation schemaLocation, boolean[] optional,
                 JsonTypeReader<?>[] schemaSlots, TupleDiagnostics rules) {

    static TuplePlan of(String name, TypeDefinition definition, ValueReaderContext context) {
        TupleBody body = (TupleBody) definition.body();
        int arity = body.elements().size();
        boolean[] optional = new boolean[arity];
        JsonTypeReader<?>[] slots = new JsonTypeReader<?>[arity];
        for (int i = 0; i < arity; i++) {
            optional[i] = body.elements().get(i).state() == ElementState.OPTIONAL;
            slots[i] = context.readers().resolve(body.elements().get(i).elementType().name());
        }
        String displayName = EntryDisplayName.of(name, definition, context.schema().entries());
        return new TuplePlan(displayName, context.locationOf(name, definition), optional, slots,
                new TupleDiagnostics(displayName, arity));
    }

    int arity() {
        return schemaSlots.length;
    }
}
