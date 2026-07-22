package io.ltr8.tson.parser.ast;

/**
 * {@code absent = "_"} (§2.9): the explicitly-absent sentinel, distinct from any typed value
 * including base-type null.
 *
 * <p>The spec forbids {@code _} in map-key position, but as a <em>resolver-layer</em> rule, not
 * a grammar one ("the map-entry production accepts any value in key position, and the resolver
 * rejects absent keys", §2.9). The structural parser deliberately does not reject it here.
 */
public record AbsentValue() implements CoreValue {
}
