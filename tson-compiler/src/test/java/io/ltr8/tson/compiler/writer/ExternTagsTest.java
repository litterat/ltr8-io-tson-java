package io.ltr8.tson.compiler.writer;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonCompiledVocabularyRegistry;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.encoding.TsonSchemaBinding;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link ExternTags} finds a record's class as a read binds it ({@link TsonLinkedSchema#bindingCandidates}), so a
 * boundary is tagged wherever the read would bind the record holding it.
 */
class ExternTagsTest {

    private static final String HOST = "https://example.test/tags-host.tn";

    private static final Map<String, String> SOURCES = Map.of(HOST, """
            !!id:"https://example.test/tags-host.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              y     => !meta.extern { schema: "https://example.test/tags-lib.tn"  type: x }
              h     => { v: y }
              ships => h
            }
            """, "https://example.test/tags-lib.tn", """
            !!id:"https://example.test/tags-lib.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              x => { n: int32 }
            }
            """);

    public record X(int n) {
    }

    public record H(X v) {
    }

    /** {@code linked} with every entry's source position dropped, as a resolved form read back holds it. */
    private static TsonLinkedSchema positionless(TsonLinkedSchema linked) {
        TsonSchema schema = linked.schema();
        Map<String, TsonSchema.EntryFacts> facts = new LinkedHashMap<>();
        schema.facts().forEach((name, fact) -> facts.put(name,
                new TsonSchema.EntryFacts(Optional.empty(), fact.annotations(), fact.constructor())));
        return new TsonLinkedSchema(new TsonSchema(schema.id(), schema.imports(), schema.entries(), schema.bootstrap(),
                schema.valuesByName(), schema.heldValues(), schema.restatements(), facts), linked.entryOrigins(),
                linked.enumForms(), linked.nameTypes(), linked.externs());
    }

    /** A positionless record bound only through an alias of it holds its boundary as the read binds it. */
    @Test
    void aRecordBoundThroughAnAliasOfAPositionlessEntryHoldsItsBoundary() {
        TsonLinkedSchema linked = positionless(TsonCompiledVocabularyRegistry.withStandardLibrary(
                SchemaModelNameBinder.defaultContext(), SchemaSource.ofMap(SOURCES)).resolveLinked(HOST));
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("ships", H.class, "x", X.class))).build();

        ExternTags.Boundary boundary = ExternTags.of(List.of(TsonSchemaBinding.load(linked, context))).at(H.class, "v");

        assertNotNull(boundary);
        assertEquals("y", boundary.tagFor(X.class));
    }
}
