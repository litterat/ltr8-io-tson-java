package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Token;

/**
 * {@link AtomContext}'s vocabulary plus the two registrations that cannot live beside it -- what binding
 * <b>this library's own resolved schema model</b> needs on top of what a consumer's classes need.
 *
 * <p>Neither could travel, and for the same kind of reason: {@link Token} is {@code tson-schema}'s, and
 * {@link SourcePosition}'s bridge names this module's own {@code Position}, the only concrete
 * implementation there is. Both are needed wherever a {@code schema.meta.TypeDefinition} is itself bound --
 * schema resolution and the resolved-form round trip -- and nowhere a consumer's data goes: a user schema's
 * {@code !!meta} chain does not contribute the kernel's {@code value}, so no field a consumer declares can
 * be typed by the escape hatch {@code Token} carries.
 *
 * <p>So the split is by what needs it rather than by module convenience.
 */
public final class ResolverBindContext {

    private ResolverBindContext() {
    }

    /** A fresh context carrying the atom vocabulary and the schema model's own two registrations. */
    public static DataBindContext defaultContext() {
        return registerDefaults(DataBindContext.builder()).build();
    }

    /** {@code builder} with the atom vocabulary and the schema model's own two registrations added. */
    public static DataBindContext.Builder registerDefaults(DataBindContext.Builder builder) {
        return builder.registerAtoms(AtomContext.hostTypes())
                .registerAtom(Token.class)
                .registerAtom(SourcePosition.class, new SourcePositionStringBridge());
    }
}
