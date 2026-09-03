package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.ForeignSchemas;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * The compilation environment a {@link ValueReaderFactory} builds within, beyond its own entry's {@code
 * name}/{@code definition} (which stay direct {@code create} arguments): the whole {@link #linked} schema
 * being compiled -- the namespace of sibling entries a factory may need to reach a level up (a choice
 * classifies its variants for untagged recovery from it), plus where each of those entries was declared --
 * and the {@link #readers} a composite factory calls to resolve its child fields'/elements' own readers.
 *
 * <p>A context object rather than a widening parameter list: a factory that later needs a further handle
 * gains a field here instead of every factory's signature churning.
 *
 * <p>{@link #foreign} is the one handle that is not about the schema being compiled: [TSON-SCHEMA] §7.8's
 * scope push resolves a schema the <em>document</em> names, so {@link ScopedReader} is given where to go and
 * ask rather than an answer. Every other factory ignores it.
 */
public record ValueReaderContext(TsonLinkedSchema linked, TsonTypeReaderResolver readers, ForeignSchemas foreign) {

    /** The resolved schema being compiled -- what a factory reaching a sibling entry wants. */
    public TsonSchema schema() {
        return linked.schema();
    }

    /**
     * The {@link SchemaLocation} the reader for entry {@code name} offers as its own declaration. The only
     * place one is built: the identity has to come from {@link TsonLinkedSchema#originOf} so that it and the
     * position are the same document's -- an imported entry's line belongs to the schema that declared it,
     * never to the one importing it.
     */
    public SchemaLocation locationOf(String name, TypeDefinition definition) {
        return SchemaLocation.of(linked.originOf(name), name, definition.position());
    }
}
