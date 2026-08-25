package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code type-def} (Part 2 §12.1) -- the right-hand side of a declaration (§5.1). Every
 * type-definition form resolves to a {@code type_definition} value (§8, not implemented at this
 * grammar-only stage); this sealed hierarchy models the surface syntax the resolver would consume,
 * one variant per top-level ABNF alternative:
 *
 * <ul>
 *   <li>{@link AtomRefinement} -- {@code "!" type-name ws "^" ws record-def} (§5.5)</li>
 *   <li>{@link Instance} -- {@code "!" type-name ws core-value} (§5.5, constructor application -- see
 *       {@link Instance}'s own Javadoc)</li>
 *   <li>{@link InstanceTemplate} -- {@code type-params ws "!" type-name ws template-def} (§12.1, a
 *       constructor application carrying parameters -- a production of its own, since its payload resolves
 *       against different vocabulary from {@link Instance}'s)</li>
 *   <li>{@link StructuralTypeDef} -- {@code [type-params] ["~"] structural-def} (§5.7-§5.9)</li>
 *   <li>{@link ReferenceTypeDef} -- {@code [type-params] type-ref} (§8.3): a plain reference, or any
 *       container form, since a declaration-level container reaches this through {@code type-ref} like
 *       every other position</li>
 * </ul>
 *
 * <p>Every variant has an ABNF alternative behind it -- each is parsed, never synthesised. §5.3's sized sugar
 * is no exception: {@code [T; 1..2]} is rewritten by {@code SchemaDesugarer} into the {@link Instance} its
 * bindings denote -- the sugar names {@code array} and binds its fields directly, with no size template in
 * between.
 */
public sealed interface TypeDef
        permits AtomRefinement, Instance, InstanceTemplate, StructuralTypeDef, ReferenceTypeDef {
}
