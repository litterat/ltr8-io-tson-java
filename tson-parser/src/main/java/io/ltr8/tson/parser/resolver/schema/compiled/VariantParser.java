package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.Set;

/**
 * Dispatches by declared subtype instead of reading a type's own body directly -- for a
 * declaration like {@code response} that has open type parameters (`<T>`) and therefore no
 * concrete shape of its own to read: real data never arrives typed bare {@code response}, only as
 * one of its concrete subtypes ({@code success_response}/{@code failure_response}, discovered via
 * {@link TypeDefinition#subtypes}, populated by {@code SchemaValidator.computeSubtypes} -- already
 * the full transitive closure, so a flat name lookup here covers the whole subtype hierarchy, not
 * just direct children). The same mechanism an explicit {@code choice}/union type uses (match the
 * value's own {@code !typeName} annotation against a fixed member list), except the member list is
 * discovered from the reverse composition index instead of an explicit {@code variants: [type_ref]}
 * -- an open-ended union, not a closed one.
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
 * whole schema, reintroduced at a smaller scale here. {@code ctx} is captured and consulted at
 * {@link #read} time instead, once per distinct subtype actually dispatched to -- cheap on repeat
 * dispatches to the same subtype, since {@code ctx}'s own underlying resolution already memoizes.
 */
final class VariantParser implements TsonTypeParser<Object> {

    /** @throws IllegalStateException if {@code definition} has no known subtypes to dispatch to */
    static TsonTypeParser<?> forSubtypes(String name, TypeDefinition definition, CompilationContext ctx) {
        if (definition.subtypes().isEmpty()) {
            throw new IllegalStateException("'" + name + "' has open type parameters " + definition.parameters()
                    + " and no known subtypes to dispatch to -- nothing compilable here");
        }
        return new VariantParser(name, Set.copyOf(definition.subtypes()), ctx);
    }

    private final String name;
    private final Set<String> subtypeNames;
    private final CompilationContext ctx;

    private VariantParser(String name, Set<String> subtypeNames, CompilationContext ctx) {
        this.name = name;
        this.subtypeNames = subtypeNames;
        this.ctx = ctx;
    }

    @Override
    public Object read(DataValue value) {
        String typeRef = value.typeRef().orElseThrow(() -> new IllegalArgumentException("'" + name
                + "' has open type parameters -- a value at this position requires an explicit type "
                + "annotation (!typeName) naming one of its known subtypes to disambiguate: " + subtypeNames));
        if (!subtypeNames.contains(typeRef)) {
            throw new IllegalArgumentException(
                    "'" + typeRef + "' is not a known subtype of '" + name + "' -- expected one of " + subtypeNames);
        }
        // value passed through unchanged, typeRef included -- unlike TsonMapperReader.toUnion (which
        // strips it before recursing, since its own toAtom *does* branch on typeRef to look up a
        // built-in vocabulary type), no compiled parser in this package ever reads DataValue.typeRef
        // at all: RecordParser/AtomTypeParser both go straight to coreValue(), because the schema
        // position already fixed which parser applies at compile time -- there's nothing here for a
        // leftover type-ref to be misread as.
        return ctx.resolve(typeRef).read(value);
    }
}
