package io.ltr8.tson.schema.meta;

import java.util.List;
import java.util.Objects;

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
 * Java side carries alone. §9 adds the rule an extension author needs beside it: a constructor field
 * holding a type reference MUST be typed {@code type_ref}, which is what makes the slot participate in
 * flattening, {@code @alias} recording, and structural identity.
 *
 * <p><b>Two declared channels reach the linker</b>, and a body declares each rather than having it found:
 * {@link #references()} for its type references, and {@link #names()} for the values it holds by name
 * (SPEC-FEEDBACK.md #13) -- a slot typed by a constructor or a record, whose component is a {@link TypeRef}
 * carrying the qualified name as written. Both hold one host shape because both are references that must resolve:
 * a type reference is one segment and must name a type; a value by name may carry segments and must reach a value
 * whose declared type IS-A the slot's, which is why it travels with the slot it fills.
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
     *
     * <p><b>Never {@code null} -- return {@link List#of()} for a body that names no types.</b> The case to
     * watch is an implementation returning an OPTIONAL bound component directly: the binder hands an omitted
     * field to the constructor as {@code null} and does not normalise it to an empty list, so
     * {@code references()} inherits that {@code null}. Linking one is a {@code BindMismatchException}
     * naming the class, since it is the reading application's mistake rather than anything about the schema.
     */
    default List<TypeRef> references() {
        return List.of();
    }

    /**
     * Every value this body holds by name, each with the slot it fills -- empty by default, as for
     * {@link #references()}, and declared rather than discovered for the same reason.
     *
     * <p>The linker parses each name into its segments, resolves the first in the governed schema's namespace,
     * and judges the referent's declared type against the slot's, which it reads off the constructor's resolved
     * fields by walking {@link Named#slot()}. <b>Never {@code null}</b>, with the same {@code
     * BindMismatchException} as {@link #references()} for a body that returns one.
     *
     * <p><b>Interim, with the same hole as {@link #references()}</b>: a body that leaves a name out has it checked by
     * nothing. A reference type ({@code ref<T>}) makes the slot's declaration say what a {@code type_ref} slot says
     * today, and the two channels merge into one -- both are references, judged against a slot's declared type.
     */
    default List<Named> names() {
        return List.of();
    }

    /**
     * A value a body holds by name, and where: {@code slot} is the path from the body to the position the name
     * fills, one step per level of the constructor's declaration -- a field name at a record, a key at a map,
     * an index at an array -- so {@code ["methods", "place_order", "request"]} is the {@code request} field of
     * the {@code place_order} entry of the body's {@code methods} map. {@code name} is the reference as written,
     * its {@code name} the dotted token ({@code orders.methods.place_order}) and its arguments empty.
     */
    record Named(List<String> slot, TypeRef name) {

        public Named {
            slot = List.copyOf(slot);
            Objects.requireNonNull(name, "name");
            if (slot.isEmpty()) {
                throw new IllegalArgumentException("a name fills a slot of the body, so the path has a step");
            }
        }

        /** {@code name} at the slot the given steps reach. */
        public static Named at(TypeRef name, String... slot) {
            return new Named(List.of(slot), name);
        }
    }
}
