package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonCompiledVocabularyRegistry;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code !choice { variants: [...] }} construction resolves. Each variant is a bare type name -- a
 * reference with no argument list, which {@code schema.meta.TypeRef} holds as an empty {@code arguments}, its
 * one "no arguments" state.
 */
class ChoiceConstructionResolutionTest {

    /** Importing the kernel, which defines the {@code choice} constructor; its compiled form bootstraps on demand. */
    private static SchemaResolver kernelImportingResolver() {
        return new SchemaResolver(new TsonCompiledVocabularyRegistry(SchemaModelNameBinder.defaultContext(),
                TsonBundledSchemas::fetch));
    }

    private static final String CHOICE_DOCUMENT = """
            !!id:"https://example.test/choice.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !schema {
              contact => !meta.choice { variants: [text integer] }
            }
            """;

    @Test
    void resolvesAChoiceConstructionWithBareVariantTypeRefs() {
        SchemaResolver resolver = kernelImportingResolver();
        SchemaDocument document = new TsonSchemaParser(CHOICE_DOCUMENT).parseSchemaDocument();

        TsonSchema resolved = resolver.resolveSchema(document);

        Top contact = resolved.entries().get("contact");
        ChoiceBody body = assertInstanceOf(ChoiceBody.class, contact);
        assertEquals(List.of("text", "integer"), body.variants().stream().map(TypeRef::name).toList());
        // Each variant is a bare reference -- empty arguments, never null.
        assertTrue(body.variants().stream().allMatch(variant -> variant.arguments().isEmpty()));
    }
}
