package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.TsonCompiledRegistry;
import io.ltr8.tson.compiler.resolver.DefaultTsonCompiledSchemaLoader;
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
 * <p>{@link #defaultLoader} is the same kind of wrapper for {@link DefaultTsonCompiledSchemaLoader}
 * (also {@code resolver}-package, also unexported) -- the loader a caller actually wants in the
 * ordinary case: check an already-compiled registry first, special-case meta-kernel's own bootstrap,
 * and otherwise fetch/resolve/register/compile a schema on demand (see {@link
 * TsonCompiledSchemaLoader}'s own Javadoc for why a bare {@link TsonCompiledRegistry} lookup alone
 * isn't enough).
 */
public class TsonSchemaResolver {

    /** {@link #defaultLoader(TsonCompiledRegistry, TsonSchemaSource)} with {@link TsonSchemaSource#registeredOnly()} -- nothing is ever fetched. */
    public static TsonCompiledSchemaLoader defaultLoader(TsonCompiledRegistry registry) {
        return new DefaultTsonCompiledSchemaLoader(registry);
    }

    /** @param source where to fetch a schema's own source text from, for a URI that isn't already registered/compiled and isn't meta-kernel's own bootstrap case. */
    public static TsonCompiledSchemaLoader defaultLoader(TsonCompiledRegistry registry, TsonSchemaSource source) {
        return new DefaultTsonCompiledSchemaLoader(registry, source);
    }

    private final SchemaResolver resolver;

    /** @param loader consulted to resolve {@code document}'s own {@code !!meta}/{@code !!import} targets -- see {@link #defaultLoader} for the ordinary case. */
    public TsonSchemaResolver(TsonCompiledSchemaLoader loader) {
        this.resolver = new SchemaResolver(loader);
    }

    /** Resolves {@code document}'s own header directives and every declaration in its body -- see {@link SchemaResolver#resolveSchema}'s own Javadoc for the full contract. */
    public TsonSchema resolveSchema(SchemaDocument document) {
        return this.resolver.resolveSchema(document);
    }
}
