package io.ltr8.tson.compiler.ast.schema;

/**
 * A materialised template instantiation (Part 2 §8.2) -- the one form that becomes a genuine new entry.
 * {@code array_ranged<pixel, 1920, 1920>}, the desugaring of {@code [pixel; 1920]} (§5.3), instantiates to
 *
 * <pre>{@code
 * array_ranged_pixel_af3 => !type_definition {
 *   kind: PRODUCT
 *   source: { name: array_ranged  arguments: [ { name: pixel } { value: 1920 } { value: 1920 } ] }
 *   supertypes: [array product top]
 *   body: !array { element_type: pixel  min_items: 1920  max_items: 1920 }
 * }
 * }</pre>
 *
 * <p><b>No surface syntax corresponds to this.</b> Unlike every other {@link TypeDef}, it is never parsed --
 * {@code SchemaDesugarer} synthesises it, and §8.2 is explicit that instantiation entries "are
 * resolver-materialised, not declared" and "cannot be named from any schema source". It lives in this
 * hierarchy rather than being resolved on the spot because the desugar phase's whole shape is AST-to-AST:
 * the application is rewritten into a declaration, and the declaration is resolved by the ordinary path.
 *
 * <p><b>Why it is not just an {@link Instance}.</b> {@code body} alone would resolve correctly -- the
 * substituted binding record is headed by the nearest {@code ~} constructor in the source chain (§5.6), which
 * is exactly what an {@code Instance} denotes -- but §5.5 gives a construction only its target's *kind*, no
 * supertypes, while §8.2 requires an instantiation to keep "the template's supertypes, unchanged by
 * substitution" and to record the flattened application as its {@code source}. So the application itself is
 * carried alongside the body: the resolver reads the template's own entry to recover both, which keeps
 * resolved data out of the AST -- {@code application} is a grammar-layer {@link GenericRef}, not a
 * {@code TypeDefinition}.
 *
 * <p>Establishes IS-A, unlike {@link Instance}: a closure of {@code array_ranged} IS-A {@code array} and is
 * substitutable wherever an array is expected (§5.3).
 */
public record TemplateInstance(GenericRef application, Instance body) implements TypeDef {

    /** The template being applied -- a non-constructor entry with parameters (§5.10). */
    public String template() {
        return application.name();
    }
}
