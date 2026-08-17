package io.ltr8.tson.compiler.ast.schema;

/**
 * A materialised template instantiation (Part 2 §8.2) -- the one form that becomes a genuine new entry.
 * {@code array_ranged<pixel, 1920, 1920>}, the desugaring of {@code [pixel; 1920]} (§5.3), instantiates to
 *
 * <pre>{@code
 * array_ranged_pixel_af3 => !type_definition {
 *   kind: PRODUCT
 *   source: { name: array_ranged  arguments: [ { name: pixel } { value: 1920 } { value: 1920 } ] }
 *   supertypes: []
 *   body: !array { element_type: pixel  min_items: 1920  max_items: 1920 }
 * }
 * }</pre>
 *
 * <p>§8.2's own example prints {@code supertypes: [array product top]} there; this implementation records
 * none ({@code SPEC-FEEDBACK.md} #45). A size template's chain begins at the constructor it refines, and a
 * constructor is not a type anything can be a subtype of, so a sized array sits in the hierarchy exactly
 * where {@code [pixel]} does.
 *
 * <p><b>No surface syntax corresponds to this.</b> Unlike every other {@link TypeDef}, it is never parsed --
 * {@code SchemaDesugarer} synthesises it, and §8.2 is explicit that instantiation entries "are
 * resolver-materialised, not declared" and "cannot be named from any schema source". It lives in this
 * hierarchy rather than being resolved on the spot because the desugar phase's whole shape is AST-to-AST:
 * the application is rewritten into a declaration, and the declaration is resolved by the ordinary path.
 *
 * <p><b>Why it is not just an {@link Instance}.</b> {@code body} alone would resolve to the right *value* --
 * the substituted binding record is headed by the nearest {@code ~} constructor in the source chain (§5.6),
 * which is exactly what an {@code Instance} denotes -- but §8.2 requires an instantiation to record the
 * flattened application as its {@code source}, and a plain construction records only its target's name. So
 * the application itself is carried alongside the body, which keeps resolved data out of the AST --
 * {@code application} is a grammar-layer {@link GenericRef}, not a {@code TypeDefinition}.
 */
public record TemplateInstance(GenericRef application, Instance body) implements TypeDef {

    /** The template being applied -- a non-constructor entry with parameters (§5.10). */
    public String template() {
        return application.name();
    }
}
