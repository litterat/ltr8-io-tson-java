package io.ltr8.tson.parser.ast.schema;

/**
 * {@code type-def} (Part 2 §12.1) -- the right-hand side of a declaration (§5.1). Every
 * type-definition form resolves to a {@code type_definition} value (§8, not implemented at this
 * grammar-only stage); this sealed hierarchy models the surface syntax the resolver would consume,
 * one variant per top-level ABNF alternative:
 *
 * <ul>
 *   <li>{@link AtomRefinement} -- {@code "!" type-name ws "^" ws data-value} (§5.5)</li>
 *   <li>{@link Instance} -- {@code "!" type-name ws core-value} (§5.5, constructor application;
 *       corrected from the spec's own literal {@code data-value} -- see {@link Instance}'s own
 *       Javadoc and {@code SPEC-FEEDBACK.md})</li>
 *   <li>{@link StructuralTypeDef} -- {@code [type-params] ["~"] structural-def} (§5.7-§5.9)</li>
 *   <li>{@link ContainerTypeDef} -- {@code [type-params] container-def} (§5.3, declaration-level array/tuple)</li>
 *   <li>{@link ReferenceTypeDef} -- {@code [type-params] type-ref} (§8.3, a plain reference or inline sugar)</li>
 * </ul>
 */
public sealed interface TypeDef permits AtomRefinement, Instance, StructuralTypeDef, ContainerTypeDef, ReferenceTypeDef {
}
