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
 * <p><b>No kernel declaration stands behind this yet.</b> The natural spelling is a fourth base kind,
 * {@code data => top & {}} in meta-kernel, with {@code operation => ~data & { ... }} composing against it
 * and a matching {@code TypeKind}. Neither exists in Revision 32 -- {@code type_kind} is
 * {@code !enum [ATOM PRODUCT SUM REFERENCE]} and the kernel has no {@code data} -- so a meta-schema writes
 * {@code ~top & { ... }} and the Java side carries the distinction alone. See {@code SPEC-FEEDBACK.md} #57.
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
