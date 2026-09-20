package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template's <b>parent</b> extension ({@code SPEC-FEEDBACK.md} #13): {@code template.extension}, derived by
 * the resolver rather than stated by an author.
 *
 * <p><b>Two levels, and this is the upper one.</b> A parent has no direct instances whatever anyone writes --
 * nothing can name the template itself at a value position, only one of its applications -- and its
 * applications are subtypes by construction, so OPEN and FINAL are not available to it and no mark could add
 * anything. The author's {@code abstract} is the <em>instantiation's</em> fact and lives inside the held
 * text, which is what {@code AbstractTemplateFamilyTest} pins; the two never collide.
 *
 * <p><b>Absent means "no type".</b> Only a record body has fields for a parent to carry, an {@code extension}
 * field to state and members something can select, so a container, a constructor application and a reference
 * template carry nothing -- and absence is the fact that a type position naming them stays an error.
 */
class TemplateParentExtensionTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static Optional<RecordExtensionType> parentOf(String id, String declarations, String entry) {
        TypeDefinition definition = Tson.standard()
                .resolve(HEADER.formatted(id) + "{\n" + declarations + "}")
                .schema().entries().get(entry);
        return ((TemplateBody) definition.body()).extension();
    }

    /** The selector names the same parent states -- what makes the family member-dispatched. */
    private static List<String> selectorsOf(String id, String declarations, String entry) {
        TypeDefinition definition = Tson.standard()
                .resolve(HEADER.formatted(id) + "{\n" + declarations + "}")
                .schema().entries().get(entry);
        return ((TemplateBody) definition.body()).discriminators();
    }

    private static String refusal(String id, String declarations) {
        List<Diagnostic> diagnostics = Tson.standard()
                .validateSchema(HEADER.formatted(id) + "{\n" + declarations + "}");
        assertTrue(!diagnostics.isEmpty(), "expected a refusal, got none");
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    // ── Present: a record body ───────────────────────────────────────────

    @Test
    void aRecordTemplateWithNoDiscriminatorIsAbstract() {
        assertEquals(Optional.of(RecordExtensionType.ABSTRACT),
                parentOf("p1", "  box => <V> { item: V }\n", "box"));
    }

    /**
     * The labelled sum: `type: text = T` survives erasure, so the parent dispatches on it. The extension is
     * ABSTRACT like any other base -- what says the members are placed by their pins is `discriminators`.
     */
    @Test
    void aLabelledSumDispatchesOnItsSurvivingSelector() {
        assertEquals(Optional.of(RecordExtensionType.ABSTRACT),
                parentOf("p2", "  pet => <T, V> { type: text = T  value: V }\n", "pet"));
        assertEquals(List.of("type"),
                selectorsOf("p2b", "  pet => <T, V> { type: text = T  value: V }\n", "pet"));
    }

    /** A composition template holds its flattened body, and the parent is derived over that. */
    @Test
    void aCompositionTemplateIsAbstractToo() {
        assertEquals(Optional.of(RecordExtensionType.ABSTRACT),
                parentOf("p3", "  base => { x: int32 }\n  box => <V> base & { item: V }\n", "box"));
    }

    // ── Absent: every other body shape ───────────────────────────────────

    @Test
    void aContainerTemplateHasNoParent() {
        assertEquals(Optional.empty(), parentOf("p4", "  arr => <T> [T]\n", "arr"));
    }

    @Test
    void aConstructorApplicationTemplateHasNoParent() {
        assertEquals(Optional.empty(), parentOf("p5",
                "  vector => <T, N> !array { element_type: T  min_items: N  max_items: N }\n", "vector"));
    }

    /** §5.10's partial application: a reference is indirection, so it has nothing to be abstract over. */
    @Test
    void aReferenceTemplateHasNoParent() {
        assertEquals(Optional.empty(), parentOf("p6",
                "  pair => <A, B> { first: A  second: B }\n  uuid_pair => <B> pair<uuid, B>\n", "uuid_pair"));
    }

    // ── The one condition the derivation needs ───────────────────────────

    /**
     * A discriminator's <em>pin</em> is what an argument supplies, and does. Its <em>type</em> is read before
     * the member is known, so a type that varies per application is one no decoder can read.
     */
    @Test
    void aDiscriminatorTypedByAParameterIsRefused() {
        String refusal = refusal("p7", "  bad => <K, V> { kind: K =?  v: V }\n");

        assertTrue(refusal.contains("discriminator field 'kind'"), refusal);
        assertTrue(refusal.contains("typed by the type parameter 'K'"), refusal);
    }
}
