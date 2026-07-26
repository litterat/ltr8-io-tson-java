package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.registry.SchemaLinker;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of {@link VariantParser}: the {@code response}/{@code success_response}/{@code
 * failure_response} case this class was originally built for (dispatch by subtype), plus the
 * {@code top}-like case that motivated its redesign -- a value with no type annotation, or one
 * naming the declaration itself, reads against the declaration's *own* body rather than always
 * demanding a subtype. {@code response} here is given an intentionally empty body (mirroring
 * {@code top => top & {}}); {@link #ownBodyFallbackStillFailsIfItsOwnRequirementsArentMet} uses a
 * second type with a real required field of its own to prove the fallback isn't a rubber stamp --
 * it's an ordinary read against an ordinary body, which can still fail on its own terms.
 */
class VariantParserTest {

    private static TsonSchema compileableSchema() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("response", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of("T"), false,
                List.of(), List.of(), Optional.empty(), RecordBody.of(List.of())));
        entries.put("success_response", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("response"), List.of(), Optional.empty(),
                RecordBody.of(List.of(RecordField.required("value", TypeRef.of("integer"))))));
        entries.put("failure_response", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("response"), List.of(), Optional.empty(),
                RecordBody.of(List.of(RecordField.required("error_code", TypeRef.of("integer"))))));
        // A second, unrelated subtypes-bearing type whose OWN body has a real required field, to
        // prove the "no type-ref -> own body" fallback still enforces that body's own requirements.
        entries.put("shape", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of(), List.of(), Optional.empty(),
                RecordBody.of(List.of(RecordField.required("sides", TypeRef.of("integer"))))));
        entries.put("triangle", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false,
                List.of("shape"), List.of(), Optional.empty(), RecordBody.of(List.of())));
        return new TsonSchema("https://example.test/s.tn1", "https://example.test/meta.tn1",
                List.of(), entries);
    }

    private static TsonParserFactoryRegistry registry() {
        return TsonParserFactoryRegistry.builder()
                .register("integer_type", AtomTypeParser.INTEGER_TYPE)
                .register("record", RecordParser.FACTORY)
                .build();
    }

    private static TsonCompiledSchema compiled() {
        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        TsonSchema registered = schemaRegistry.register(SchemaLinker.link(compileableSchema(), schemaRegistry)).schema();
        return TsonSchemaCompiler.compile(registered, registry());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(TsonCompiledSchema compiled, String rootName, String source) {
        Document document = new TsonDataParser(source).parseDocument();
        return (Map<String, Object>) compiled.get(rootName).read(document.root());
    }

    private static Map<String, Object> read(TsonCompiledSchema compiled, String source) {
        return read(compiled, "response", source);
    }

    @Test
    void dispatchesToTheSubtypeNamedByTheValuesOwnTypeRef() {
        TsonCompiledSchema compiled = compiled();

        assertEquals(BigInteger.valueOf(42), read(compiled, "!success_response { value: 42 }").get("value"));
        assertEquals(BigInteger.valueOf(404), read(compiled, "!failure_response { error_code: 404 }").get("error_code"));
    }

    @Test
    void missingTypeRefFallsBackToTheDeclarationsOwnEmptyBody() {
        TsonCompiledSchema compiled = compiled();

        assertEquals(Map.of(), read(compiled, "{}"));
    }

    @Test
    void explicitTypeRefNamingTheDeclarationItselfAlsoReadsItsOwnBody() {
        TsonCompiledSchema compiled = compiled();

        assertEquals(Map.of(), read(compiled, "!response {}"));
    }

    @Test
    void ownBodyFallbackStillFailsIfItsOwnRequirementsArentMet() {
        TsonCompiledSchema compiled = compiled();

        // "shape" has subtypes ("triangle") but its own body REQUIRES "sides" -- the fallback reads
        // against that real body, not a free pass, so a missing required field still fails.
        assertThrows(IllegalArgumentException.class, () -> read(compiled, "shape", "{}"));
        assertEquals(BigInteger.valueOf(3), read(compiled, "shape", "{ sides: 3 }").get("sides"));
    }

    @Test
    void unknownTypeRefThrowsNamingTheOffendingValue() {
        TsonCompiledSchema compiled = compiled();

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> read(compiled, "!partial_response { value: 42 }"));
        assertTrue(thrown.getMessage().contains("partial_response"), thrown.getMessage());
    }
}
