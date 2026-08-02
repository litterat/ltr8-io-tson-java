package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The end-to-end stripe: a missing-required-field {@link TsonReadException} carries enough for a
 * caller to report both where the offending record sits in the <em>data</em> source and where the
 * field was <em>declared</em> in the <em>schema</em> source -- proving {@link
 * TsonSchemaParser#declarationPositions()}, {@link TypeDefinition#position()}, and every real
 * {@code TsonEvent}'s own {@code position()} (the data side -- there's no separate position
 * side-table on this path anymore, {@link TsonReadContext#position()} reflects whichever event the
 * stream most recently produced) genuinely connect end to end, not just that each piece compiles in
 * isolation.
 *
 * <p>Deliberately narrow, matching this stripe's own scope: only the fresh-record-construction
 * resolution path is exercised (a schema with no supertypes/refinement/instances at all), and only
 * the missing-required-field failure mode.
 */
class PositionalReadErrorsTest {

    private static int lineOf(String source, String needle) {
        int index = source.indexOf(needle);
        assertTrue(index >= 0, "expected to find '" + needle + "' in the source text");
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        return TsonSchemaCompiler.compile(linkedSchema, TsonSchemaCompiler.dom());
    }

    @Test
    void missingRequiredFieldErrorCarriesBothDataAndSchemaPositions() {
        String schemaSource = """
                !!id:"https://tson.io/test-suite/scratch/position-demo.tn"
                !!meta:"https://tson.io/test-suite/scratch/fake-meta.tn"
                {
                  placeholder => {}

                  my_record => {
                    value: placeholder
                  }
                }
                """;
        TsonSchemaParser schemaParser = new TsonSchemaParser(schemaSource);
        SchemaDocument schemaDocument = schemaParser.parseSchemaDocument();
        Map<SchemaMap.Declaration, Position> declarationPositions = schemaParser.declarationPositions();

        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        DefinitionResolver resolver = new DefinitionResolver(
                (type, value) -> { throw new UnsupportedOperationException("not exercised by this test"); },
                name -> null, entries::get);

        SchemaMap.Declaration placeholderDeclaration = schemaDocument.body().declarations().get("placeholder");
        entries.put("placeholder", resolver.resolve(placeholderDeclaration));

        SchemaMap.Declaration myRecordDeclaration = schemaDocument.body().declarations().get("my_record");
        Position myRecordPosition = declarationPositions.get(myRecordDeclaration);
        entries.put("my_record", resolver.resolve(myRecordDeclaration, Optional.of(myRecordPosition)));

        TsonSchema schema = new TsonSchema("test-schema", "test-meta", List.of(), entries);
        TsonCompiledSchema compiled = compile(new TsonLinkedSchema(schema));

        String dataSource = "{}";
        TsonReadContext ctx = TsonReadContext.throwing(dataSource);
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> compiled.get("my_record").read(ctx));

        assertTrue(thrown.getMessage().contains("value"), thrown.getMessage());

        SourcePosition dataPosition = thrown.diagnostic().dataPosition().orElseThrow();
        assertEquals(new Position(lineOf(dataSource, "{"), 1, 0), dataPosition);

        assertTrue(thrown.diagnostic().schemaPosition().isPresent());
        SourcePosition schemaPosition = thrown.diagnostic().schemaPosition().get();
        assertEquals(lineOf(schemaSource, "my_record"), schemaPosition.line());

        String enriched = thrown.getMessage() + " (data at line " + dataPosition.line()
                + ", schema declared at line " + schemaPosition.line() + ")";
        assertEquals("missing required field 'value' for 'my_record' (data at line 1, schema declared at line 6)",
                enriched);
    }
}
