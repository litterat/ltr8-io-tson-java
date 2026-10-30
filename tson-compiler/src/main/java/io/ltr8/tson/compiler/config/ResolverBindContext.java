package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.Ref;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * {@link AtomContext}'s vocabulary plus the registrations that cannot live beside it -- what binding
 * <b>this library's own resolved schema model</b> needs on top of what a consumer's classes need.
 *
 * <p>None could travel, and for the same kind of reason: {@link Token}, {@link Ref} and {@link TypeRef} are
 * {@code tson-schema}'s, a reference's text read by {@code tson-atom}'s grammar ({@link TypeRefTextBridge}), and
 * {@link SourcePosition}'s bridge names this module's own {@code Position}, the only concrete implementation there
 * is. They are needed wherever a {@code schema.meta} value is itself bound --
 * schema resolution and the resolved-form round trip -- and nowhere a consumer's data goes: a consumer's own
 * classes bind through the context the consumer supplies, which carries none of them.
 *
 * <p>So the split is by what needs it rather than by module convenience.
 */
public final class ResolverBindContext {

    private ResolverBindContext() {
    }

    /** A fresh context carrying the atom vocabulary and the schema model's own registrations. */
    public static DataBindContext defaultContext() {
        return registerDefaults(DataBindContext.builder()).build();
    }

    /** {@code builder} with the atom vocabulary and the schema model's own registrations added. */
    public static DataBindContext.Builder registerDefaults(DataBindContext.Builder builder) {
        return builder.registerAtoms(AtomContext.hostTypes())
                .registerAtom(Token.class)
                .registerAtom(SourcePosition.class, new SourcePositionStringBridge())
                .registerAtom(Ref.class, new RefNameBridge())
                .registerAtom(TypeRef.class, new TypeRefTextBridge());
    }

    /**
     * {@link #registerDefaults}, adding each registration only where {@code builder} does not already hold it -- for
     * a builder derived from a consumer's context, which already registers the atom vocabulary it binds.
     */
    public static DataBindContext.Builder registerAbsent(DataBindContext.Builder builder) {
        for (Class<?> atom : AtomContext.hostTypes()) {
            if (!builder.registersAtom(atom)) {
                builder.registerAtom(atom);
            }
        }
        if (!builder.registersAtom(Token.class)) {
            builder.registerAtom(Token.class);
        }
        if (!builder.registersAtom(SourcePosition.class)) {
            builder.registerAtom(SourcePosition.class, new SourcePositionStringBridge());
        }
        if (!builder.registersAtom(Ref.class)) {
            builder.registerAtom(Ref.class, new RefNameBridge())
                .registerAtom(TypeRef.class, new TypeRefTextBridge());
        }
        return builder;
    }
}
