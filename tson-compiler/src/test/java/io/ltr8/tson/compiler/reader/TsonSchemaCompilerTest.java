package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct coverage of {@link TsonSchemaCompiler}/{@link TsonCompiledSchema}'s own cycle detection
 * (the reason {@link DeferredValueReader} exists at all) and eager,
 * whole-schema build (every entry is built as soon as {@link TsonSchemaCompiler#compile} returns,
 * not deferred to whenever {@link TsonCompiledSchema#get} first asks for a given name -- see that
 * class's own "Eager, not lazy" note). {@link RecordDomReaderTest}/{@link VariantDomReaderTest}/
 * {@link EnumDomReaderTest} exercise this compilation machinery too, but only ever incidentally,
 * through schemas with no real cycles -- this class targets it directly.
 */
class TsonSchemaCompilerTest {

    private static final DataValue EMPTY_RECORD = new DataValue(List.of(), Optional.empty(), new EmptyBrace());

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        TsonCompiledSchema placeholder = new TsonCompiledSchema(linkedSchema, Map.of());
        TsonCompiledMetaSchema bootstrapMeta = new TsonCompiledMetaSchema(placeholder, ValueReaderFactoryRegistry.dom());
        return TsonSchemaCompiler.compile(linkedSchema, bootstrapMeta);
    }

    @Test
    void mutualCycleCompilesWithoutStackOverflow() {
        // A -> B -> A. Compiling "A" at all (without a StackOverflowError) is what this proves --
        // B's own field circles back to A while A is still mid-construction, exactly the edge
        // DeferredValueReader exists for.
        RecordBody bodyA = RecordBody.of(List.of(RecordField.required("b", TypeRef.of("B"))));
        RecordBody bodyB = RecordBody.of(List.of(RecordField.required("a", TypeRef.of("A"))));
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("A", TypeDefinition.product(bodyA));
        entries.put("B", TypeDefinition.product(bodyB));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);

        TsonCompiledSchema compiled = compile(linkedSchema);

        // Reached compilation successfully; reading an empty record against a REQUIRED field then
        // fails for the ordinary reason (missing field), not a reader failure.
        assertThrows(TsonReadException.class, () -> compiled.get("A").read(EMPTY_RECORD));
    }

    @Test
    void directSelfReferenceCompilesWithoutStackOverflow() {
        RecordBody selfReferencing = RecordBody.of(List.of(RecordField.required("child", TypeRef.of("Node"))));
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("Node", TypeDefinition.product(selfReferencing));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);

        TsonCompiledSchema compiled = compile(linkedSchema);

        assertThrows(TsonReadException.class, () -> compiled.get("Node").read(EMPTY_RECORD));
    }

    @Test
    void anEntryWithNoRegisteredFactoryDoesNotBlockCompilingTheRestOfTheSchemaButFailsOnlyWhenActuallyRead() {
        // "orphan" uses a constructor ("email_type") with no compiled reader at all -- TsonSchemaCompiler
        // .compile() still builds the whole schema eagerly, including "orphan" itself (get("orphan")
        // succeeds, unlike the old lazy behavior where it never got attempted at all until asked for);
        // "used" (which never references "orphan") reads normally either way.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("used", TypeDefinition.product(RecordBody.of(List.of())));
        entries.put("orphan", new TypeDefinition(Optional.empty(), TypeKind.ATOM,
                List.of(), true, List.of(), List.of(), Optional.empty(), EmailType.UNCONSTRAINED));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);

        TsonCompiledSchema compiled = compile(linkedSchema);

        @SuppressWarnings("unchecked")
        Map<String, Object> used = (Map<String, Object>) compiled.get("used").read(EMPTY_RECORD);
        assertTrue(used.isEmpty());

        // Compiling/getting "orphan" itself succeeds -- only reading an actual value against it fails.
        TsonValueReader<?> orphan = compiled.get("orphan");
        UnsupportedOperationException thrown =
                assertThrows(UnsupportedOperationException.class, () -> orphan.read(EMPTY_RECORD));
        assertTrue(thrown.getMessage().contains("orphan"), thrown.getMessage());
    }

    @Test
    void getOnAnUnknownNameThrowsBeforeAnyCompilationHappens() {
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), Map.of());
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        TsonCompiledSchema compiled = compile(linkedSchema);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> compiled.get("nope"));
        assertEquals("'nope' is not in this compiled schema", thrown.getMessage());
    }
}
