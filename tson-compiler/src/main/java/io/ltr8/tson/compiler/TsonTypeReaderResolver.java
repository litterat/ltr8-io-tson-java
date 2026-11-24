package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;

/**
 * Resolves what a type slot holds to its own already-compiled {@link TsonTypeReader} -- what a composite reader's own
 * child-field resolution calls to reach the reader for a field's own type. A name ({@link #resolve(String)}) is an
 * entry's reader; a type written in place ({@link #resolveInline}) has a reader built from its body, which has no
 * entry and no name.
 *
 * <p>In practice this is always backed by {@link TsonSchemaCompiler}'s own per-compilation resolution, so a child
 * lookup recurses back into the same eager, cycle-safe machinery the top-level {@link TsonSchemaCompiler#compile}
 * walk itself uses -- an implementation of this interface never needs to know or care whether it's being called
 * recursively from a child field or from the top level.
 */
public interface TsonTypeReaderResolver {

    /** The reader of the entry {@code typeName}. */
    TsonTypeReader<?> resolve(String typeName);

    /**
     * The reader of the type a slot holds: the named entry's, or the one built from a type written in place. What
     * every reader resolving a slot calls.
     */
    default TsonTypeReader<?> resolve(TypeExpr type) {
        return switch (type) {
            case TypeRef reference -> resolve(reference.name());
            case TypeInline inline -> resolveInline(inline);
        };
    }

    /**
     * The reader built from a type written in place, its display name the form as written. A resolver over entry
     * names alone -- a stand-in that answers for a fixed set of entries -- holds no compilation to build one from, so
     * reaching it with an inline type is a caller wiring it where a compiled schema's resolver belongs.
     */
    default TsonTypeReader<?> resolveInline(TypeInline type) {
        throw new IllegalStateException("this resolver resolves entry names only, and was asked for the reader of "
                + "the type written in place '" + type.text() + "' -- an inline type's reader is built by the "
                + "compilation that holds the slot");
    }
}
