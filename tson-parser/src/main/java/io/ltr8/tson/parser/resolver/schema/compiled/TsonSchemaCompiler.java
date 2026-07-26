package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.schema.TsonSchema;

/**
 * Compiles an already-materialized, already-validated {@link TsonSchema} into a {@link
 * TsonCompiledSchema} -- the "compile" stage of this project's own parse -&gt; resolve -&gt; link
 * -&gt; register -&gt; compile -&gt; read pipeline vocabulary. Deliberately thin: {@link #compile}
 * does no work itself beyond wrapping {@code schema}/{@code registry} into a fresh {@link
 * TsonCompiledSchema} -- see that class's own "Lazy, not eager" note for why the real compilation
 * work happens later, on first {@link TsonCompiledSchema#get}, not here.
 *
 * <p>A stateless utility, not an instantiable class -- one {@link TsonCompiledSchema} is a complete,
 * self-contained compilation on its own (it holds its own {@code registry}, its own {@code finished}/
 * {@code building} state), so there is nothing a {@code TsonSchemaCompiler} instance would hold that
 * a static method call doesn't already cover, matching {@code TsonSchemaLinker.link}'s own precedent.
 */
public final class TsonSchemaCompiler {

    private TsonSchemaCompiler() {
    }

    /** Wraps {@code schema}/{@code registry} for on-demand compilation -- see {@link TsonCompiledSchema}'s own "Lazy, not eager" note. */
    public static TsonCompiledSchema compile(TsonSchema schema, TsonParserFactoryRegistry registry) {
        return new TsonCompiledSchema(schema, registry);
    }
}
