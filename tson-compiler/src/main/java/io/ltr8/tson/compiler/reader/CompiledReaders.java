package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;

import java.util.Objects;

/**
 * The name-to-reader resolution a compiled schema offers, across both of the phases in which it is needed.
 * A reader may resolve a sibling <em>while being built</em> (a record resolving its field types, a map its
 * key/value types) and again <em>while reading</em> (a dispatch reader picking a variant from the wire
 * type-ref, an annotation resolving the type it names) -- and those two phases have different rightful
 * sources.
 *
 * <p><b>Why this exists.</b> During the compile walk the only source is the compilation's own in-progress
 * state; afterwards the rightful source is the finished {@link TsonCompiledSchema}, which is immutable and is
 * the artifact a read is actually being performed against. Without this indirection a reader that resolves at
 * read time has to retain the compile-time resolver, which is a bound method reference onto {@code
 * TsonSchemaCompiler}'s own {@code Compilation} -- so its mutable {@code finished}/{@code building}
 * collections stay reachable for as long as any reader does, contradicting {@code Compilation}'s own
 * documented invariant that they never escape a single compile call. Every such read-time resolution was
 * safe only because each call site happens to gate on a name it already knows is present; that is a property
 * of four call sites agreeing, not of the design.
 *
 * <p>So the handle starts out delegating to the compilation and is {@linkplain #bind bound} to the finished
 * schema exactly once, at the end of the walk. Binding <b>replaces</b> the delegate rather than adding to
 * it, which is the point: the compilation becomes unreachable the moment the schema exists, and what every
 * reader retains from then on is one reference to an immutable map.
 */
public final class CompiledReaders implements TsonTypeReaderResolver {

    /**
     * Volatile because the write in {@link #bind} happens on the compiling thread and the reads happen on
     * whatever threads later perform reads. Both delegates answer identically for every name the schema
     * declares, so a stale read would still be correct -- this is for safe publication of the {@link
     * TsonCompiledSchema} the bound delegate closes over, not for correctness of the switch itself.
     */
    private volatile TsonTypeReaderResolver delegate;

    /** Starts out resolving through {@code whileCompiling} -- the compilation's own recursive, cycle-aware build. */
    public CompiledReaders(TsonTypeReaderResolver whileCompiling) {
        this.delegate = Objects.requireNonNull(whileCompiling, "whileCompiling");
    }

    @Override
    public TsonTypeReader<?> resolve(String typeName) {
        return delegate.resolve(typeName);
    }

    /**
     * Switches resolution over to the finished schema, releasing the compilation. Called once, by {@code
     * TsonSchemaCompiler}, as the last step of a compile -- a second call is a programming error, since two
     * different schemas answering for one set of readers would be silently wrong rather than obviously so.
     */
    public void bind(TsonCompiledSchema compiled) {
        Objects.requireNonNull(compiled, "compiled");
        if (!(delegate instanceof CompiledSchemaResolver)) {
            delegate = new CompiledSchemaResolver(compiled);
            return;
        }
        throw new IllegalStateException("this CompiledReaders is already bound to a compiled schema");
    }

    /** A named type rather than a lambda, so {@link #bind} can tell a bound handle from an unbound one. */
    private record CompiledSchemaResolver(TsonCompiledSchema compiled) implements TsonTypeReaderResolver {

        @Override
        public TsonTypeReader<?> resolve(String typeName) {
            return compiled.get(typeName);
        }
    }
}
