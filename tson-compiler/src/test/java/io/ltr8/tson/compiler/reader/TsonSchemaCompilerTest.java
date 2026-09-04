package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.tree.TsonValue;
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
 * (the reason {@link DeferredTypeReader} exists at all) and eager,
 * whole-schema build (every entry is built as soon as {@link TsonSchemaCompiler#compile} returns,
 * not deferred to whenever {@link TsonCompiledSchema#get} first asks for a given name -- see that
 * class's own "Eager, not lazy" note). {@link RecordTreeReaderTest}/{@link VariantTreeReaderTest}/
 * {@link EnumTreeReaderTest} exercise this compilation machinery too, but only ever incidentally,
 * through schemas with no real cycles -- this class targets it directly.
 */
class TsonSchemaCompilerTest {

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        return TsonSchemaCompiler.compile(linkedSchema, ValueReaderFactoryRegistry.tree());
    }

    @Test
    void mutualCycleCompilesWithoutStackOverflow() {
        // A -> B -> A. Compiling "A" at all (without a StackOverflowError) is what this proves --
        // B's own field circles back to A while A is still mid-construction, exactly the edge
        // DeferredTypeReader exists for.
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
        assertThrows(TsonReadException.class, () -> compiled.get("A").read(TestDocuments.document("{}")));
    }

    @Test
    void directSelfReferenceCompilesWithoutStackOverflow() {
        RecordBody selfReferencing = RecordBody.of(List.of(RecordField.required("child", TypeRef.of("Node"))));
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("Node", TypeDefinition.product(selfReferencing));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);

        TsonCompiledSchema compiled = compile(linkedSchema);

        assertThrows(TsonReadException.class, () -> compiled.get("Node").read(TestDocuments.document("{}")));
    }

    @Test
    void anEntryWithNoRegisteredFactoryDoesNotBlockCompilingTheRestOfTheSchemaButFailsOnlyWhenActuallyRead() {
        // "orphan" is a meta-layer constructor's instance (EndpointBody) -- [TSON-SCHEMA] §2.2.2's extension
        // point, and the only kind of entry left that this library can build no reader for, every kernel and
        // meta constructor having one. TsonSchemaCompiler.compile() still builds the whole schema eagerly,
        // including "orphan" itself (get("orphan") succeeds, unlike the old lazy behavior where it never got
        // attempted at all until asked for); "used" (which never references "orphan") reads normally either
        // way.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("used", TypeDefinition.product(RecordBody.of(List.of())));
        entries.put("orphan", new TypeDefinition(Optional.empty(), TypeKind.DATA,
                List.of(),  List.of(), List.of(), Optional.empty(), new EndpointBody("/search")));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);

        TsonCompiledSchema compiled = compile(linkedSchema);

        @SuppressWarnings("unchecked")
        Map<String, Object> used = (Map<String, Object>) Dom.of((TsonValue) compiled.get("used")
                .read(TestDocuments.document("{}")));
        assertTrue(used.isEmpty());

        // Compiling/getting "orphan" itself succeeds -- only reading an actual value against it fails.
        // Fail-fast, so the report raises: the gap is in `diagnostic().code()`, never in the exception type,
        // which is the same rule the schema pipeline follows (`Diagnostic.Code.NOT_IMPLEMENTED`).
        TsonTypeReader<?> orphan = compiled.get("orphan");
        TsonReadException thrown =
                assertThrows(TsonReadException.class, () -> orphan.read(TestDocuments.document("{}")));
        assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, thrown.diagnostic().code());
        assertTrue(thrown.getMessage().contains("orphan"), thrown.getMessage());
    }

    @Test
    void getOnAnUnknownNameThrowsBeforeAnyCompilationHappens() {
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), Map.of());
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        TsonCompiledSchema compiled = compile(linkedSchema);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> compiled.get("nope"));
        assertEquals("'nope' is not in this compiled schema, whose types are ()", thrown.getMessage());
    }

    @Test
    void getOnAnUnknownNameEnumeratesTheTypesTheSchemaDoesDeclare() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("first", TypeDefinition.product(RecordBody.of(List.of())));
        entries.put("second", TypeDefinition.product(RecordBody.of(List.of())));
        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);
        TsonCompiledSchema compiled = compile(new TsonLinkedSchema(schema));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> compiled.get("frist"));
        assertEquals("'frist' is not in this compiled schema, whose types are (first | second) -- did you "
                + "mean 'first'?", thrown.getMessage());
    }
}
