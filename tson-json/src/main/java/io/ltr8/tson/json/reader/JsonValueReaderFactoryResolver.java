package io.ltr8.tson.json.reader;

/**
 * Finds the {@link JsonValueReaderFactory} registered for a constructor name. {@link
 * JsonValueReaderFactoryRegistry} is the one real implementation, one instance per read mode.
 *
 * <p>Internal to {@code tson-json}: a consumer picks a mode by which reader they hold, never by naming this
 * type. It appears in {@code JsonSchemaCompiler}'s signature all the same, which is the accepted
 * {@code -Xlint:exports} warning {@code tson-compiler} takes for the same reason at the same seam.
 */
public interface JsonValueReaderFactoryResolver {

    JsonValueReaderFactory resolve(String name);
}
