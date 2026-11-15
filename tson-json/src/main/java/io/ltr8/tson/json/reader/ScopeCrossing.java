package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonReadContext;
import io.ltr8.tson.json.JsonTypeReader;

/**
 * What reading a foreign value asks of the read it is part of ([TSON-SCHEMA] §7.9), which only the context's own
 * class can do: one cursor, receiver and policy run through the value, as one document's, while its names and its
 * schema location are the foreign schema's.
 */
@FunctionalInterface
public interface ScopeCrossing {

    /**
     * {@code reader}'s read of the value at {@code ctx}'s cursor inside the foreign value: no schema location
     * established, so the foreign reader seeds its own and the schema pointer runs into the foreign schema from here;
     * every diagnostic names {@code boundary} and each one enclosing it.
     *
     * @param dropsTag whether the object at the cursor leads with the boundary's {@code $type}, which is the enclosing
     *                 scope's ([TSON-JSON] §8.6) and is consumed here, so {@code reader} reads the rest of the object
     *                 as an untagged value
     */
    Object read(JsonReadContext ctx, ForeignScope boundary, JsonTypeReader<?> reader, boolean dropsTag);
}
