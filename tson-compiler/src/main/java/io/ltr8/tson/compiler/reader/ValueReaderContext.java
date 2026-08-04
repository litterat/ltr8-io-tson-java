package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.schema.TsonSchema;

/**
 * The compilation environment a {@link ValueReaderFactory} builds within, beyond its own entry's {@code
 * name}/{@code definition} (which stay direct {@code create} arguments): the whole {@link #schema} being
 * compiled -- the namespace of sibling entries a factory may need to reach a level up (a choice classifies
 * its variants for untagged recovery from it) -- and the {@link #readers} a composite factory calls to
 * resolve its child fields'/elements' own readers.
 *
 * <p>A context object rather than a widening parameter list: a factory that later needs a further handle
 * gains a field here instead of every factory's signature churning.
 */
public record ValueReaderContext(TsonSchema schema, TsonValueReaderResolver readers) {
}
