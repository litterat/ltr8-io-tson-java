package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonTypeReader;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * Builds the {@link JsonTypeReader} for one resolved schema entry -- the unit of work {@link
 * ValueReaderFactoryRegistry} dispatches to, keyed by the entry's resolved body's constructor name
 * ({@code "record"}, {@code "array"}, {@code "integer_type"}).
 *
 * <p>{@code name} is the <em>declaration's</em> own name, not the constructor's: the two coincide only for a
 * meta-schema's own declarations, and every other declaration differs ({@code float32}, constructed via
 * {@code float_type}). A reader names the declaration in what it reports, and {@code boolean}'s enum reads to a
 * host boolean by that name.
 */
public interface ValueReaderFactory {

    JsonTypeReader<?> create(String name, TypeDefinition typeDefinition, ValueReaderContext context);
}
