package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.resolver.SchemaResolver;
import io.ltr8.tson.schema.TsonSchema;

/**
 * The public front door for the "resolve" stage of this project's own parse -&gt; resolve -&gt; link
 * -&gt; register -&gt; compile -&gt; read pipeline vocabulary -- a thin wrapper over {@link
 * SchemaResolver}, which does the actual work (header-directive validation, structure-namespace
 * derivation, {@code !!import} merging, per-declaration resolution). {@code resolver} isn't an
 * exported package (see "Naming convention" in this project's own CLAUDE.md: internal machinery a
 * consumer of this library never names directly), so a consumer resolving a document from outside
 * this module needs a class in an exported package to reach it -- this is that class, not a
 * reimplementation.
 *
 * <p>The {@link TsonCompiledSchemaLoader} it is constructed with resolves the document's own {@code
 * !!meta}/{@code !!import} targets on demand (fetch/resolve/register/compile), rather than requiring
 * them to be pre-registered -- {@code TsonCompiledRegistry} is the implementation a caller ordinarily
 * uses (it is both the compiled-schema registry and that loader).
 */
public class TsonSchemaResolver {

    private final SchemaResolver resolver;

    /** @param loader consulted to resolve {@code document}'s own {@code !!meta}/{@code !!import} targets on demand. */
    public TsonSchemaResolver(TsonCompiledSchemaLoader loader) {
        this.resolver = new SchemaResolver(loader);
    }

    /**
     * Resolves {@code document}'s own header directives and every declaration in its body -- see {@link
     * SchemaResolver#resolveSchema}'s own Javadoc for the full contract.
     */
    public TsonSchema resolveSchema(SchemaDocument document) {
        return this.resolver.resolveSchema(document);
    }
}
