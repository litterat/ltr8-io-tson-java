package io.ltr8.tson.json.reader;

import io.ltr8.tson.json.JsonTypeReader;

/**
 * How a composite reader reaches the reader for one of its children, by entry name -- the seam that lets a
 * compile wire real object references rather than leaving every position to consult a name-keyed map on
 * every read.
 */
@FunctionalInterface
public interface JsonTypeReaderResolver {

    JsonTypeReader<?> resolve(String name);
}
