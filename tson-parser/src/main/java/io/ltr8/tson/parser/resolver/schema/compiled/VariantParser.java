package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Set;

/**
 * Dispatches by declared subtype instead of reading a type's own body directly -- for a
 * declaration like {@code response} that has open type parameters (`<T>`) and therefore no
 * concrete shape of its own to read: real data never arrives typed bare {@code response}, only as
 * one of its concrete subtypes ({@code success_response}/{@code failure_response}, discovered via
 * {@link TypeDefinition#subtypes}, populated by {@code SchemaValidator.computeSubtypes} -- already
 * the full transitive closure, so a flat name lookup here covers the whole subtype hierarchy, not
 * just direct children). The same mechanism an explicit {@code choice}/union type uses ({@link
 * ChoiceParser} -- match the value's own {@code !typeName} annotation against a fixed member list),
 * except the member list is discovered from the reverse composition index instead of an explicit
 * {@code variants: [type_ref]} -- an open-ended union, not a closed one. The actual dispatch (once
 * each has its own candidate-name set) is identical between the two, factored into {@link
 * NamedDispatchParser}.
 *
 * <p>Not registered through {@link ParserFactoryRegistry}/{@link TsonParserFactory} -- unlike every
 * other compiled parser, the trigger for building one of these isn't the resolved body's own
 * constructor name (a parameterized declaration's body could structurally be anything, and isn't
 * actually read at all), it's a property of the {@link TypeDefinition} itself ({@code
 * parameters().isEmpty()} being false). {@link TsonSchemaParser}'s own compiler checks this
 * directly, the same way it special-cases {@code Reference} before ever consulting the registry.
 *
 * <p><b>Doesn't resolve any subtype's own parser until it's actually dispatched to.</b> A record's
 * fields (see {@link RecordParser}) are all resolved up front because every one of them is read on
 * every {@code read()} call -- but a variant's subtypes are alternatives, not a fixed set that's
 * all needed together: for any given value, exactly one of them applies. Resolving all of them at
 * {@link #forSubtypes} time (an earlier version of this class did) would force every subtype to be
 * buildable just because *one* of a type's several alternatives got touched -- exactly the
 * eager-building problem {@link TsonSchemaParser}'s own "lazy, not eager" note describes for the
 * whole schema, reintroduced at a smaller scale here. {@link NamedDispatchParser} captures {@code
 * ctx} and consults it at read time instead, once per distinct subtype actually dispatched to --
 * cheap on repeat dispatches to the same subtype, since {@code ctx}'s own underlying resolution
 * already memoizes.
 */
final class VariantParser {

    private VariantParser() {
    }

    /** @throws IllegalStateException if {@code definition} has no known subtypes to dispatch to */
    static TsonTypeParser<?> forSubtypes(String name, TypeDefinition definition, CompilationContext ctx) {
        if (definition.subtypes().isEmpty()) {
            throw new IllegalStateException("'" + name + "' has open type parameters " + definition.parameters()
                    + " and no known subtypes to dispatch to -- nothing compilable here");
        }
        return new NamedDispatchParser(name,
                "has open type parameters -- a value at this position requires an explicit type annotation "
                        + "(!typeName) naming one of its known subtypes to disambiguate",
                "known subtype", Set.copyOf(definition.subtypes()), ctx);
    }
}
