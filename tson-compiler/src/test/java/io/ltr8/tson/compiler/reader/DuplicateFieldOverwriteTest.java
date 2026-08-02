package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchemaRegistry;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
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

/**
 * Proves the deliberate, streaming-driven behavior change from this codebase's own pre-streaming
 * design (see {@code SPEC-FEEDBACK.md}'s entry on it, and {@link RecordAbstractReader}'s own class
 * Javadoc): a record field named twice reads and validates <em>every</em> occurrence, forward, in
 * stream order -- a shadowed (non-final) duplicate's own value is genuinely decoded, so its own
 * problems still surface as a diagnostic, even though the field's own final stored value always
 * ends up being whichever occurrence came <em>last</em> (§2.5's "last value wins", applied per
 * decode rather than by skipping every occurrence but the last unread).
 */
class DuplicateFieldOverwriteTest {

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        TsonCompiledMetaRegistry core = new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext());
        return TsonCompiledSchemaRegistry.tree(core).compile(linkedSchema);
    }

    @Test
    void aMalformedFirstOccurrenceStillReportsEvenThoughTheValidSecondOccurrenceWins() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("int8", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), new IntegerType(new IntegerSize(8, true))));
        entries.put("holder", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("value", TypeRef.of("int8"))))));
        TsonSchema schema = new TsonSchema("https://example.test/dup-field.tn",
                "https://example.test/meta.tn", List.of(), entries);
        TsonCompiledSchema compiled = compile(new TsonLinkedSchema(schema));

        // "value" appears twice: first as 999 (out of int8's own -128..127 range), then as 42 (valid).
        String dataSource = "{ value: 999  value: 42 }";
        TsonReadContext ctx = TsonReadContext.collecting(dataSource);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) Dom.of((io.ltr8.tson.compiler.tree.TsonNode) compiled.get("holder").read(ctx));

        // The malformed first occurrence was genuinely read/validated -- exactly one diagnostic,
        // for the out-of-range 999, not silently skipped the way pre-streaming backward-scan-and-
        // skip would have (it never touched a shadowed value at all).
        assertEquals(1, ctx.diagnostics().size(), ctx.diagnostics().toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, ctx.diagnostics().get(0).code());

        // Despite that, the field's own final stored value is the second, valid occurrence --
        // forward overwrite, matching §2.5's "last value wins". DOM mode still narrows a real
        // int8-typed atom down to a Java byte (AtomValueReader.INTEGER_TYPE, unrelated to this test).
        assertEquals((byte) 42, result.get("value"));
    }
}
