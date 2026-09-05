package io.ltr8.tson.compiler;

/**
 * How a read reaches a schema the document names <em>inside</em> itself -- [TSON-SCHEMA] §7.8's scope push,
 * where a value at a {@code scoped} position carries its own {@code !!schema} and the type it names has to
 * be resolved, linked and compiled before that value can be read.
 *
 * <p><b>Read-time, unlike every other name a reader resolves.</b> A compiled reader's children are wired as
 * real Java references at compile time, because the schema being compiled names all of them. A foreign
 * schema is named by the <em>document</em>, so which one it is cannot be known until the value arrives.
 * What can be fixed in advance is where to go and ask, and that is this: {@code TsonCompiledSchemaRegistry}
 * hands its own lookup to every compile it performs, so a scope push resolves through the same cache, the
 * same loader and the same read mode as the schema that admitted it.
 *
 * <p>A compile with no registry behind it -- the bootstrap, a standalone compile in a test -- passes {@link
 * #none()}, whose every lookup is a {@code SCHEMA_NOT_PERMITTED}: nothing was configured to supply a foreign
 * schema, which is a fact about this deployment and not a verdict on the document.
 */
@FunctionalInterface
public interface ForeignSchemas {

    /**
     * The compiled schema at {@code uri}, in the compiling registry's own read mode.
     *
     * @throws TsonSchemaFetchException      if no source would supply it
     * @throws RuntimeException              if it was supplied and could not be resolved, linked or compiled
     */
    TsonCompiledSchema get(String uri);

    /**
     * {@link #get} with every failure classified and reported through {@code ctx} rather than thrown,
     * yielding {@code null} when the schema could not be had.
     *
     * <p>Here rather than at the caller because {@link SchemaFailure} is what keeps a schema nobody would
     * supply apart from a schema that is wrong, and it is package-private -- the same classification both
     * read facades apply to a document's own {@code !!schema}, applied to a value's. A fault in this library
     * still propagates as itself, {@code SchemaFailure} rethrowing anything none of its branches claims.
     */
    default TsonCompiledSchema get(String uri, TsonReadContext ctx) {
        try {
            return get(uri);
        } catch (RuntimeException e) {
            SchemaFailure failure = SchemaFailure.of(e);
            ctx.report(failure.code(), e.getMessage(), failure.expected(), uri);
            return null;
        }
    }

    /** A lookup for a compile with no registry behind it -- see this interface's own note. */
    static ForeignSchemas none() {
        return uri -> {
            throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.NOT_PERMITTED,
                    "this read has no schema source behind it, so the scope it names cannot be loaded -- read "
                            + "through a Tson facade, whose registry supplies one", null);
        };
    }
}
