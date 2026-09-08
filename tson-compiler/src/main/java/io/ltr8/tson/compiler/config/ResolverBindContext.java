package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.atom.TsonAtomContext;
import io.ltr8.tson.base.SourcePosition;

/**
 * {@link TsonAtomContext}'s vocabulary plus the one registration that cannot live beside it.
 *
 * <p>{@link SourcePosition} is bridged by {@link SourcePositionStringBridge}, which names this module's own
 * {@code Position} -- the only concrete implementation there is -- so the bridge cannot travel to
 * {@code tson-atom} with the rest and this engine applies it on top. It is needed wherever a
 * {@code schema.meta.TypeDefinition} is itself bound, which is schema resolution and the resolved-form
 * round trip, and nowhere a consumer's data goes.
 *
 * <p>So the split is by what needs it rather than by module convenience: the shared list is what a
 * consumer's classes bind, and this is what binding <em>this library's own schema model</em> additionally
 * needs.
 */
public final class ResolverBindContext {

    private ResolverBindContext() {
    }

    /** A fresh context carrying the atom vocabulary and the {@code SourcePosition} bridge. */
    public static DataBindContext defaultContext() {
        return registerDefaults(DataBindContext.builder().build());
    }

    /** {@code context} with the atom vocabulary and the {@code SourcePosition} bridge applied. */
    public static DataBindContext registerDefaults(DataBindContext context) {
        TsonAtomContext.registerDefaults(context);
        try {
            context.registerAtom(SourcePosition.class, new SourcePositionStringBridge());
        } catch (DataBindException e) {
            throw new IllegalStateException("failed to register the SourcePosition bridge on a context", e);
        }
        return context;
    }
}
