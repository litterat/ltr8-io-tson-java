package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct coverage of {@link TsonSchemaParser}'s own compiler -- cycle detection (the reason {@link
 * ParserHandle.Direct}/{@link ParserHandle.Indirect} exist at all) and laziness (nothing gets built
 * until {@link TsonSchemaParser#get} actually asks for it). {@link RecordParserTest}/{@link
 * VariantParserTest}/{@link EnumTypeParserFactoryTest} exercise this compiler too, but only ever
 * incidentally, through schemas with no real cycles -- this class targets the compiler itself.
 */
class TsonSchemaParserTest {

    private static final ParserFactoryRegistry RECORD_ONLY = ParserFactoryRegistry.builder()
            .register("record", RecordParser.FACTORY)
            .build();

    private static final DataValue EMPTY_RECORD = new DataValue(List.of(), Optional.empty(), new EmptyBrace());

    @Test
    void mutualCycleCompilesWithoutStackOverflow() {
        // A -> B -> A. Compiling "A" at all (without a StackOverflowError) is what this proves --
        // B's own field circles back to A while A is still mid-construction, exactly the edge
        // ParserHandle.Indirect exists for.
        RecordBody bodyA = RecordBody.of(List.of(RecordField.required("b", TypeRef.of("B"))));
        RecordBody bodyB = RecordBody.of(List.of(RecordField.required("a", TypeRef.of("A"))));
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("A", TypeDefinition.product(bodyA));
        entries.put("B", TypeDefinition.product(bodyB));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);

        TsonSchemaParser compiled = TsonSchemaParser.compile(schema, RECORD_ONLY);

        // Reached compilation successfully; reading an empty record against a REQUIRED field then
        // fails for the ordinary reason (missing field), not a compiler failure.
        assertThrows(IllegalArgumentException.class, () -> compiled.get("A").read(EMPTY_RECORD));
    }

    @Test
    void directSelfReferenceCompilesWithoutStackOverflow() {
        RecordBody selfReferencing = RecordBody.of(List.of(RecordField.required("child", TypeRef.of("Node"))));
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("Node", TypeDefinition.product(selfReferencing));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);

        TsonSchemaParser compiled = TsonSchemaParser.compile(schema, RECORD_ONLY);

        assertThrows(IllegalArgumentException.class, () -> compiled.get("Node").read(EMPTY_RECORD));
    }

    @Test
    void unrelatedEntryWithNoRegisteredFactoryDoesNotBlockCompilingWhatYouActuallyAskFor() {
        // "orphan" uses a constructor ("unit") with no registered factory at all -- compiling/reading
        // "used" (which never references "orphan") must still work, proving TsonSchemaParser.compile()
        // doesn't eagerly build every entry in the schema.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("used", TypeDefinition.product(RecordBody.of(List.of())));
        entries.put("orphan", new TypeDefinition(Optional.empty(), TypeKind.ATOM,
                List.of(), false, List.of(), List.of(), Optional.empty(), new Unit()));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);

        TsonSchemaParser compiled = TsonSchemaParser.compile(schema, RECORD_ONLY);

        @SuppressWarnings("unchecked")
        Map<String, Object> used = (Map<String, Object>) compiled.get("used").read(EMPTY_RECORD);
        assertTrue(used.isEmpty());

        // Only fails once "orphan" is actually asked for.
        assertThrows(IllegalStateException.class, () -> compiled.get("orphan"));
    }

    @Test
    void getOnAnUnknownNameThrowsBeforeAnyCompilationHappens() {
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), Map.of());
        TsonSchemaParser compiled = TsonSchemaParser.compile(schema, RECORD_ONLY);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> compiled.get("nope"));
        assertEquals("'nope' is not in this compiled schema", thrown.getMessage());
    }
}
