package io.ltr8.tson.json.reader;

import io.ltr8.tson.base.diagnostics.ArrayDiagnostics;
import io.ltr8.tson.json.JsonSchemaLocation;
import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.math.BigInteger;
import java.util.Optional;

/**
 * What an array or set's schema fixes, resolved once when the schema compiles: [TSON-JSON] §6.3's half that does
 * not depend on what the read builds. A mode's factory builds one, decides which reader the elements are read at,
 * and hands both to {@link ArrayReader}; bind mode keeps the plan to build the same position again for a
 * component's own collection class ({@link BindTargets}).
 *
 * <p><b>Sets share the wire form and differ only in what they refuse.</b> A set-typed position is the same JSON
 * array; duplicates under the element type's equality contract are validation errors at the repeated occurrence
 * ([TSON-SCHEMA] §7.5), and element order on the wire is meaningless. The resolved body's own {@code unique_items}
 * decides whether the duplicate check runs -- refinement never adds or removes a field, so there is no second shape.
 */
record ArrayPlan(String displayName, JsonSchemaLocation schemaLocation, boolean optionalElements, boolean unique,
                 Optional<BigInteger> minItems, Optional<BigInteger> maxItems, JsonTypeReader<?> schemaElement,
                 ArrayDiagnostics rules) {

    static ArrayPlan of(String name, TypeDefinition definition, ValueReaderContext context) {
        ArrayBody body = (ArrayBody) definition.body();
        String displayName = EntryDisplayName.of(name, definition, context.schema().entries());
        return new ArrayPlan(displayName, context.locationOf(name, definition),
                body.state() == ElementState.OPTIONAL, body.uniqueItems(), body.minItems(), body.maxItems(),
                context.readers().resolve(body.elementType().name()), new ArrayDiagnostics(displayName));
    }
}
