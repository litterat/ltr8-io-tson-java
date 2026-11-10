package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.FamilySelectors;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template's {@code extension} and selectors are <b>each application's</b> (§5.10). A template is no type, so it is
 * no family base whatever it states ({@code FamilySelectors} answers nothing for it); the record each application
 * closes to carries the template's {@code extension} and {@code discriminators}, a selector making the template
 * ABSTRACT as it makes a record so.
 */
class TemplateExtensionTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!import:"%s"
            !!import:"%s"
            !schema
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static Namespace<Top> entries(String id, String declarations) {
        return Tson.standard().resolve(HEADER.formatted(id) + "{\n" + declarations + "}").schema().entries();
    }

    private static String refusal(String id, String declarations) {
        List<Diagnostic> diagnostics = Tson.standard()
                .validateSchema(HEADER.formatted(id) + "{\n" + declarations + "}");
        assertTrue(!diagnostics.isEmpty(), "expected a refusal, got none");
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    /** No template is a family base: an unmarked one, a marked one and a selector one alike. */
    @Test
    void noTemplateIsAFamilyBase() {
        Namespace<Top> entries = entries("e1", """
                  box  => <V> { item: V }
                  abox => abstract <V> { item: V }
                  pet  => <V> { type: text =?  value: V }
                  arr  => <T> { items: [T] }
                """);
        for (String template : List.of("box", "abox", "pet", "arr")) {
            assertEquals(Optional.empty(), FamilySelectors.extensionOf(entries.get(template)), template);
            assertEquals(List.of(), FamilySelectors.namesOf(entries.get(template)), template);
        }
    }

    /** Unmarked and with no selector, each application is OPEN, as an unmarked record is. */
    @Test
    void anUnmarkedTemplatesApplicationIsOpen() {
        Namespace<Top> entries = entries("e2", "  box => <V> { item: V }\n  bt => box<text>\n");

        assertEquals(RecordExtensionType.OPEN, ((RecordBody) entries.get("box")).extension());
        assertEquals(RecordExtensionType.OPEN, ((RecordBody) entries.get("bt")).extension());
    }

    /** {@code abstract} on a template makes each application abstract. */
    @Test
    void anAbstractTemplatesApplicationIsAbstract() {
        Namespace<Top> entries = entries("e3", "  box => abstract <V> { item: V }\n  bt => box<text>\n");

        assertEquals(RecordExtensionType.ABSTRACT, ((RecordBody) entries.get("bt")).extension());
    }

    /** A selector makes the template ABSTRACT, and each application an abstract record dispatching on it. */
    @Test
    void aSelectorTemplatesApplicationDispatchesOnIt() {
        Namespace<Top> entries = entries("e4", "  pet => <V> { type: text =?  value: V }\n  pt => pet<text>\n");

        assertEquals(RecordExtensionType.ABSTRACT, ((RecordBody) entries.get("pet")).extension());
        RecordBody applied = (RecordBody) entries.get("pt");
        assertEquals(RecordExtensionType.ABSTRACT, applied.extension());
        assertEquals(List.of("type"), applied.discriminators());
        assertTrue(FamilySelectors.dispatchesOnMembers(applied));
    }

    /**
     * A discriminator's <em>type</em> is read before the member is known, so a type that varies per application
     * is no selector a family can be written against.
     */
    @Test
    void aDiscriminatorTypedByAParameterIsRefused() {
        String refusal = refusal("e5", "  bad => <K, V> { kind: K =?  v: V }\n");

        assertTrue(refusal.contains("discriminator field 'kind'"), refusal);
        assertTrue(refusal.contains("typed by the type parameter 'K'"), refusal);
    }

    /** The same rule where the parameter is an argument of the selector's type, which varies with it just as much. */
    @Test
    void aDiscriminatorTypedByAnApplicationOfAParameterIsRefused() {
        String refusal = refusal("e6", """
                  pet     => <M> { kind: meta.ref<M> =?  name: text }
                """);

        assertTrue(refusal.contains("discriminator field 'kind'"), refusal);
        assertTrue(refusal.contains("typed by an application of the parameter 'M'"), refusal);
    }
}
