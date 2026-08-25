package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * A body that describes <b>something other than a data value</b> -- the base kind for vocabulary a
 * meta-schema introduces beyond the kernel's own ({@code operation} describing an HTTP endpoint, say),
 * whose instances ride along in a schema map without being types.
 *
 * <p><b>The one deliberately open point in the body model.</b> {@link Top}'s other branches are sealed all
 * the way down: each leaf mirrors one kernel constructor, so a body's kind is decidable by inspection and
 * every switch over them is exhaustive. This branch is {@code non-sealed} because the constructors reaching
 * it are declared by meta-schemas this library has never seen, and their bodies are the consumer's own Java
 * classes. Implementing it is how such a class joins the model.
 *
 * <p><b>An implementation MUST carry {@code @Typename} naming the constructor it is the body of</b>
 * ({@code @Typename(name = "operation")}), and MUST be resolvable through the {@code DataNameBinder} of the
 * bind context the meta-schema is compiled with -- that is what {@code !operation { ... }} binds through,
 * and how the compiler dispatches an entry built with it. A constructor whose class is not resolvable is an
 * error at the declaration that writes it, not a value quietly carried in some generic form: a schema
 * asserting structure nothing can interpret is worth failing on.
 *
 * <p><b>Not a type, and the linker enforces it.</b> An entry whose body is a {@code Data} is refused where a
 * type is expected ({@code TsonSchemaLinker.validateTypeRef}), so {@code holder => { s: search }} is an
 * author error rather than something that resolves, links and compiles and then fails at read.
 *
 * <p><b>A kernel declaration stands behind this.</b> meta-kernel declares the fourth base kind,
 * {@code data => top & {}}, and {@code type_kind} carries {@code DATA} beside {@code ATOM}/{@code PRODUCT}/
 * {@code SUM}/{@code REFERENCE}, so a meta-schema composes its own constructor against it
 * ({@code operation => ~data & { ... }}) and the kind is a fact of the resolved schema rather than one the
 * Java side carries alone. See {@code SPEC-FEEDBACK.md} #57 for the argument that produced it.
 */
public non-sealed interface Data extends Top {

    /**
     * Every type this body names, for the linker to resolve like any other reference -- empty by default,
     * since a body naming none is the ordinary case.
     *
     * <p>Declared rather than discovered: a payload's Java shape says nothing about which of its components
     * are references, and asking the constructor's own declaration would only work for slots spelled
     * {@code type_ref}. An implementation holding a {@link TypeRef} component returns it here and the name
     * is checked at link time, against the same namespace every other reference is checked against.
     */
    default List<TypeRef> references() {
        return List.of();
    }
}
