package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a {@code !choice { variants: [...] }} construction resolves. Each variant is a bare type
 * name -- a positional-form {@code type_ref} whose OPTIONAL {@code arguments} field is absent. That
 * absence binds to {@code null} (the faithful binding of an absent {@code ?} field), which NPEd in {@code
 * schema.meta.TypeRef}'s own {@code List.copyOf(arguments)}, so no choice type could be declared at all
 * (`bindAtomInstance` wrapped it as an {@code UnsupportedOperationException}). {@code TypeRef} now
 * normalizes an absent ({@code null}) {@code arguments} to the empty list -- its own documented "empty
 * means no arguments" state.
 */
class ChoiceConstructionResolutionTest {

    /** Governed by meta-kernel, which defines the {@code choice} constructor; its compiled form bootstraps on demand. */
    private static SchemaResolver metaKernelGovernedResolver() {
        return new SchemaResolver(new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext()));
    }

    private static final String CHOICE_DOCUMENT = """
            !!id:"https://example.test/choice.tn"
            !!meta:"https://tson.io/2026/32/m/meta-kernel.tn"
            {
              contact => !choice { variants: [text integer] }
            }
            """;

    @Test
    void resolvesAChoiceConstructionWithBareVariantTypeRefs() {
        SchemaResolver resolver = metaKernelGovernedResolver();
        SchemaDocument document = new TsonSchemaParser(CHOICE_DOCUMENT).parseSchemaDocument();

        TsonSchema resolved = resolver.resolveSchema(document);

        TypeDefinition contact = resolved.entries().get("contact");
        ChoiceBody body = assertInstanceOf(ChoiceBody.class, contact.body());
        assertEquals(List.of("text", "integer"), body.variants().stream().map(TypeRef::name).toList());
        // Each variant is a bare reference -- empty arguments (the null that used to be here was the NPE).
        assertTrue(body.variants().stream().allMatch(variant -> variant.arguments().isEmpty()));
    }
}
