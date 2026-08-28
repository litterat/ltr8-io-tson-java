package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchema;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the bootstrap against meta-kernel.tn1 as packaged on the classpath (see this module's
 * {@code build.gradle.kts}): the header directives carry straight through, the 36 declarations
 * {@code DefinitionResolver} already resolves via ordinary schema-grammar resolution are all present,
 * and all 13 {@code Instance} declarations the second pass covers (three {@code unit} instances,
 * {@code integer}, {@code text}/{@code uri}/{@code regex}, and six {@code enum} instances,
 * including one -- {@code boolean} -- declared *before* {@code enum} itself in source order)
 * resolve to the expected kind/body -- all 50 of the real fixture's declarations resolve, alongside the
 * nine entries {@link SchemaDesugarer} injects for their argument-bearing applications.
 */
class MetaKernelBootstrapResolverTest {

    @Test
    void headerDirectivesCarryThroughFromTheDocument() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        // §1.5: meta-kernel's own !!meta names itself -- the one deliberate circularity. By identity,
        // not raw string: its !!id carries a ?sha256= pin its self-!!meta cannot (pinning it would be
        // circular), so they differ as strings but name the same identity.
        assertEquals(TsonCanonicalIdentity.canonicalize(schema.id()),
                TsonCanonicalIdentity.canonicalize(schema.meta()));
        assertTrue(schema.meta().endsWith("meta-kernel.tn"));
        assertEquals(List.of(), schema.imports());
        // getMetaKernelSchema() is the one and only place that ever sets this -- see TsonSchema's own Javadoc.
        assertTrue(schema.bootstrap());
    }

    @Test
    void resolvesAllThirtySixOrdinarilyResolvableDeclarations() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        // A sample spanning every construct DefinitionResolver already handles on its own.
        for (String name : List.of("top", "atom", "product", "sum", "reference", "integer_size",
                "integer_type", "record", "array", "map", "tuple", "choice", "schema")) {
            assertTrue(schema.entries().containsKey(name), name + " should resolve via DefinitionResolver alone");
        }
    }

    @Test
    void unitInstancesResolveToAnEmptyUnitBodyWithAtomKindTransferredFromUnit() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        for (String name : List.of("value", "identifier", "void")) {
            TypeDefinition resolved = schema.entries().get(name);
            assertEquals(TypeKind.ATOM, resolved.kind());
            assertInstanceOf(Unit.class, resolved.body());
            assertEquals(List.of(), resolved.supertypes());
            assertEquals("unit", resolved.source().orElseThrow().name());
        }
    }

    @Test
    void integerResolvesToAnUnconstrainedIntegerTypeBodyWithAtomKind() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        TypeDefinition integer = schema.entries().get("integer");
        assertEquals(TypeKind.ATOM, integer.kind());
        assertEquals(IntegerType.UNCONSTRAINED, integer.body());
    }

    @Test
    void booleanResolvesEvenThoughEnumItselfIsDeclaredLaterInTheFile() {
        // boolean => !enum [true false] appears near the top of the file; enum => ~atom & {...}
        // isn't declared until much later -- the two-pass design exists precisely for this.
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        TypeDefinition booleanDef = schema.entries().get("boolean");
        assertEquals(TypeKind.ATOM, booleanDef.kind());
        assertEquals(new EnumBody(List.of("true", "false")), booleanDef.body());
    }

    @Test
    void everyEnumInstanceInTheFixtureResolves() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        assertEquals(new EnumBody(List.of("INDEX", "NAMED")), schema.entries().get("product_access_type").body());
        assertEquals(new EnumBody(List.of("FIXED", "VARIABLE")), schema.entries().get("product_size_type").body());
        for (String name : List.of("field_state", "element_state", "type_kind")) {
            assertInstanceOf(EnumBody.class, schema.entries().get(name).body());
        }
    }

    @Test
    void textUriRegexResolveToTheirUnconstrainedTypeBodiesWithAtomKind() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

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

    /**
     * The bootstrap runs {@link SchemaDesugarer} over its own document like every other schema does, so its
     * output is the 51 declarations the fixture writes plus one injected declaration per distinct sugar form
     * within them -- eight {@code array} entries from §5.3's {@code [X]} field-type sugar and one {@code map}
     * entry from the {@code {K => V}} sugar in {@code instance_template.bindings}. They are the same entries
     * the linker used to synthesize; producing them here is what leaves the linker with nothing to
     * materialize (see {@code MetaKernelSchemaRegistryTest}). {@code enum}'s member set is not among them:
     * it is the fixture's own {@code enum_set} declaration, since {@code set} has no sugar and a {@code !}
     * form stays prohibited at a field position (§5.2).
     */
    @Test
    void theFortyNineFixtureDeclarationsResolveAlongsideEightDesugaredEntries() {
        TsonSchema schema = MetaKernelBootstrapResolver.getMetaKernelSchema();

        assertEquals(57, schema.entries().size());
        for (String head : List.of("array_tuple_element", "array_field_name", "array_type_ref",
                "array_type_name", "array_type_argument", "array_param_name", "array_field_group",
                "array_record_field")) {
            assertTrue(schema.entries().keySet().stream().anyMatch(name -> name.startsWith(head + "_")),
                    "expected a desugared entry with head '" + head + "'");
        }
    }

    // ── instanceBody's own defensive branches -- neither reachable through the real fixture ──

    private static Instance emptyInstance(String target) {
        return new Instance(new DataValue(List.of(), Optional.of(target), new EmptyBrace()));
    }

    @Test
    void unrecognizedInstanceTargetCompilesToEmpty() {
        assertEquals(Optional.empty(), MetaKernelBootstrapResolver.instanceBody(emptyInstance("something_else")));
    }

    @Test
    void aNonEmptyBodyForAnEmptyBodiedTargetThrows() {
        Instance nonEmpty = new Instance(new DataValue(List.of(), Optional.of("unit"),
                new TokenValue("oops", TokenForm.UNQUOTED)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> MetaKernelBootstrapResolver.instanceBody(nonEmpty));
        assertTrue(thrown.getMessage().contains("unit"));
    }
}
