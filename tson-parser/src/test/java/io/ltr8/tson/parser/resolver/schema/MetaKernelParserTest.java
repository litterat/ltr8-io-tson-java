package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UriType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bootstrap against meta-kernel.tn1 as packaged on the classpath (see this module's
 * {@code build.gradle.kts}): the header directives carry straight through, the 36 declarations
 * {@code SchemaResolver} already resolves via ordinary schema-grammar resolution are all present,
 * and all 13 {@code Instance} declarations the second pass covers (three {@code unit} instances,
 * {@code integer}, {@code text}/{@code uri}/{@code regex}, and six {@code enum} instances,
 * including one -- {@code boolean} -- declared *before* {@code enum} itself in source order)
 * resolve to the expected kind/body -- all 49 of the real fixture's declarations resolve.
 */
class MetaKernelParserTest {

    @Test
    void headerDirectivesCarryThroughFromTheDocument() {
        MetaSchema schema = MetaKernelParser.parse();

        // §1.5: meta-kernel's own !!meta names itself -- the one deliberate circularity.
        assertEquals(schema.id(), java.util.Optional.of(schema.meta()));
        assertTrue(schema.meta().endsWith("meta-kernel.tn1"));
        assertEquals(List.of(), schema.imports());
    }

    @Test
    void resolvesAllThirtySixOrdinarilyResolvableDeclarations() {
        MetaSchema schema = MetaKernelParser.parse();

        // A sample spanning every construct SchemaResolver already handles on its own.
        for (String name : List.of("top", "atom", "product", "sum", "reference", "integer_size",
                "integer_type", "record", "array", "map", "tuple", "choice", "schema")) {
            assertTrue(schema.entries().containsKey(name), name + " should resolve via SchemaResolver alone");
        }
    }

    @Test
    void unitInstancesResolveToAnEmptyUnitBodyWithAtomKindTransferredFromUnit() {
        MetaSchema schema = MetaKernelParser.parse();

        for (String name : List.of("value", "token", "void")) {
            TypeDefinition resolved = schema.entries().get(name);
            assertEquals(TypeKind.ATOM, resolved.kind());
            assertInstanceOf(Unit.class, resolved.body());
            assertEquals(List.of(), resolved.supertypes());
            assertEquals("unit", resolved.source().orElseThrow().name());
        }
    }

    @Test
    void integerResolvesToAnUnconstrainedIntegerTypeBodyWithAtomKind() {
        MetaSchema schema = MetaKernelParser.parse();

        TypeDefinition integer = schema.entries().get("integer");
        assertEquals(TypeKind.ATOM, integer.kind());
        assertEquals(IntegerType.UNCONSTRAINED, integer.body());
    }

    @Test
    void booleanResolvesEvenThoughEnumItselfIsDeclaredLaterInTheFile() {
        // boolean => !enum [true false] appears near the top of the file; enum => ~atom & {...}
        // isn't declared until much later -- the two-pass design exists precisely for this.
        MetaSchema schema = MetaKernelParser.parse();

        TypeDefinition booleanDef = schema.entries().get("boolean");
        assertEquals(TypeKind.ATOM, booleanDef.kind());
        assertEquals(new EnumBody(List.of("true", "false")), booleanDef.body());
    }

    @Test
    void everyEnumInstanceInTheFixtureResolves() {
        MetaSchema schema = MetaKernelParser.parse();

        assertEquals(new EnumBody(List.of("INDEX", "NAMED")), schema.entries().get("product_access_type").body());
        assertEquals(new EnumBody(List.of("FIXED", "VARIABLE")), schema.entries().get("product_size_type").body());
        for (String name : List.of("field_state", "element_state", "type_kind")) {
            assertInstanceOf(EnumBody.class, schema.entries().get(name).body());
        }
    }

    @Test
    void textUriRegexResolveToTheirUnconstrainedTypeBodiesWithAtomKind() {
        MetaSchema schema = MetaKernelParser.parse();

        TypeDefinition text = schema.entries().get("text");
        assertEquals(TypeKind.ATOM, text.kind());
        assertEquals(TextType.UNCONSTRAINED, text.body());

        TypeDefinition uri = schema.entries().get("uri");
        assertEquals(TypeKind.ATOM, uri.kind());
        assertEquals(UriType.UNCONSTRAINED, uri.body());

        TypeDefinition regex = schema.entries().get("regex");
        assertEquals(TypeKind.ATOM, regex.kind());
        assertEquals(RegexType.UNCONSTRAINED, regex.body());
    }

    @Test
    void allFortyNineRealFixtureDeclarationsNowResolve() {
        MetaSchema schema = MetaKernelParser.parse();

        assertEquals(49, schema.entries().size());
    }
}
