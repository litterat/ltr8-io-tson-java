package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;

/**
 * What reading a foreign value asks of the read it is part of ([TSON-SCHEMA] §7.9), which only the context's own
 * implementation can do: one cursor, receiver and policy run through the value, as one document's, while its names
 * and its schema location are the foreign schema's.
 */
@FunctionalInterface
public interface ScopeCrossing {

    /**
     * {@code reader}'s read of the value at {@code ctx}'s cursor inside the foreign value: no schema location
     * established, so the foreign reader seeds its own and the schema pointer runs into the foreign schema from here;
     * every diagnostic names {@code boundary} and each one enclosing it; and a schema document's sink hears nothing,
     * the names inside being the foreign schema's.
     */
    Object read(TsonReadContext ctx, ForeignScope boundary, TsonTypeReader<?> reader);
}
