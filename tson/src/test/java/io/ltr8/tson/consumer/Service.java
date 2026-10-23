package io.ltr8.tson.consumer;

import io.ltr8.annotation.Field;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A consumer's {@code service} record, held under a key of a {@code services} namespace: the fields of its own a
 * namespace type cannot declare beside its facets, and its endpoints in a field typed by one. Two components bind
 * under wire names that are not their own -- {@code extends} is a Java keyword, and {@code endpoints} is held as
 * {@code byName} -- and the optional {@code fallback} is an {@code Optional}, so a value named through these fields
 * is found only where the binding put it.
 */
public record Service(@Field("extends") List<String> bases, Optional<Endpoint> fallback,
                      @Field("endpoints") Map<String, Endpoint> byName) {
}
