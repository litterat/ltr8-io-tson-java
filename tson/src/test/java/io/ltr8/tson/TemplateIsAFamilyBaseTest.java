package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A template carrying {@code extension} <b>is</b> the family base ({@code SPEC-FEEDBACK.md} #13): it takes
 * part in the IS-A chain, it is what a type position names, and it is what a host sealed type binds to.
 *
 * <p><b>Why the template itself rather than an entry derived from it.</b> The base is the thing an author
 * named and the thing a consumer binds: minting a separate entry for it hands the binder a content-derived
 * name, leaves diagnostics with nothing the author wrote to print, and makes {@code subtypes} live in one
 * place or another depending on whether some position happens to name the template. The template is already
 * an entry with a name, so the base needs no second one.
 *
 * <p><b>What makes it safe is elimination, not the body.</b> No value is ever read against the template: a
 * value at such a position is a value of some member, chosen by a tag (ABSTRACT) or by reading the
 * discriminator fields (SEALED), and every member is closed with its arguments already fixed. So the
 * existential goes away by dispatch and never by inferring arguments from the payload -- which is why a
 * container template, having no such dispatch, is still not a type.
 */
class TemplateIsAFamilyBaseTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static TsonLinkedSchema linked(String id, String declarations) {
        return Tson.standard().resolve(HEADER.formatted(id) + "{\n" + declarations + "}");
    }

    private static List<Diagnostic> load(String id, String declarations) {
        return Tson.standard().validateSchema(HEADER.formatted(id) + "{\n" + declarations + "}");
    }

    /**
     * The entry a {@code name => head<args>} declaration denotes: itself, since such a declaration <em>is</em>
     * its instantiation ({@code SPEC-FEEDBACK.md} #15), or the entry it aliases where an earlier declaration
     * already named the same application.
     */
    private static String target(TsonLinkedSchema schema, String alias) {
        return schema.schema().entries().get(alias).body() instanceof io.ltr8.tson.schema.meta.Reference ref
                ? ref.target().name() : alias;
    }

    private static List<Diagnostic> read(String id, String declarations, String document) {
        Tson tson = Tson.standard();
        tson.resolve(HEADER.formatted(id) + "{\n" + declarations + "}");
        List<Diagnostic> problems = new ArrayList<>();
        tson.treeReader().withDiagnostics(problems::add)
                .withSchema("https://example.test/" + id + ".tn").readAs(document, "holder");
        return problems;
    }

    private static final String SEALED_FAMILY = """
              dog_type => { breed: text }
              cat_type => { indoor: boolean }
              pet      => <T, V> { type: text = T  value: V }
              dogpet   => pet<"dog", dog_type>
              catpet   => pet<"cat", cat_type>
              holder   => { p: pet }
            """;

    // ── Gap 1: a marked template takes part in the IS-A chain ────────────

    /**
     * A subtype template is a subtype of its base. The instantiations already index under the base through
     * their own {@code supertypes}; what was missing is the template, which {@code computeSubtypes} skipped
     * outright for having parameters.
     */
    @Test
    void aMarkedTemplateIsIndexedUnderItsBase() {
        TsonLinkedSchema schema = linked("f1", """
                  pet_base => abstract { type: text =? }
                  pet      => <T, V> pet_base & { type: = T  value: V }
                  dog_type => { breed: text }
                  dogpet   => pet<"dog", dog_type>
                """);

        assertTrue(schema.schema().entries().get("pet_base").subtypes().contains("pet"),
                () -> "the template is a member of its base: "
                        + schema.schema().entries().get("pet_base").subtypes());
    }

    /** And it keeps its own instantiations, which is what #506 already gives it. */
    @Test
    void aMarkedTemplateHoldsItsInstantiations() {
        TsonLinkedSchema schema = linked("f2", SEALED_FAMILY);

        assertEquals(List.of(target(schema, "dogpet"), target(schema, "catpet")),
                schema.schema().entries().get("pet").subtypes());
    }

    // ── Gap 2: abstract on a template is a claim with a subject ───────────

    /** The shape the design exists for: the pin is a parameter, so the field is the selector by derivation. */
    @Test
    void aParametricPinMakesATemplateAFamilyBase() {
        assertEquals(List.of(), load("f3", """
                  dog_type => { breed: text }
                  pet      => abstract <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                """));
    }

    /**
     * No parametric pin and no {@code =?} is no family: the base is ABSTRACT, its members are selected by the
     * tag, and nothing here is refused for having nothing to dispatch on.
     */
    @Test
    void aTemplateWithNoSelectorIsMerelyAbstract() {
        assertEquals(List.of(), load("f4", """
                  dog_type => { breed: text }
                  pet      => <V> { value: V }
                  dogpet   => pet<dog_type>
                """));
    }

    /** {@code final} stays refused: every application is a subtype by construction, so it cannot hold. */
    @Test
    void finalIsStillRefusedOnATemplate() {
        assertTrue(!load("f5", """
                  pet => final <T> { type: text = T }
                """).isEmpty());
    }

    // ── Gap 3: reading at a template-typed position ──────────────────────

    @Test
    void aValueAtASealedTemplatePositionDispatchesToItsMember() {
        assertEquals(List.of(), read("f6", SEALED_FAMILY,
                "!holder { p: { type: \"dog\"  value: { breed: \"lab\" } } }"));
    }

    @Test
    void theOtherMemberIsSelectedByItsOwnPin() {
        assertEquals(List.of(), read("f7", SEALED_FAMILY,
                "!holder { p: { type: \"cat\"  value: { indoor: true } } }"));
    }

    /** A pin no member states is refused, and the refusal names the base the author wrote. */
    @Test
    void anUnmatchedPinIsRefusedNamingTheTemplate() {
        List<Diagnostic> problems = read("f8", SEALED_FAMILY,
                "!holder { p: { type: \"fish\"  value: { breed: \"x\" } } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, problems.get(0).code());
        // Not merely "mentions pet": OpenTemplateReader's refusal does that too, so the assertion has to be
        // one only a dispatch can satisfy -- the pin that matched nothing, and the pins that were on offer.
        assertTrue(problems.get(0).message().contains("fish"),
                () -> "names the pin that matched nothing: " + problems.get(0).message());
        assertTrue(problems.get(0).message().contains("dog") && problems.get(0).message().contains("cat"),
                () -> "offers the pins a document could write: " + problems.get(0).message());
    }

    /**
     * An ABSTRACT template dispatches by tag instead, over the aliases a document can write.
     *
     * <p><b>Unmarked, deliberately.</b> The template's own {@code extension} is derived ABSTRACT -- a record
     * body with no discriminator -- while each instantiation closes OPEN and so has direct instances for the
     * tag to select. Writing {@code abstract} here would state the <em>instantiation's</em> fact instead
     * (#504: the mark travels to every application identically), leaving {@code pet<dog_type>} abstract over
     * an empty family and nothing able to stand at it. The two levels are what {@code
     * AbstractTemplateFamilyTest} covers from the other side.
     */
    @Test
    void anAbstractTemplateDispatchesByTag() {
        assertEquals(List.of(), read("f9", """
                  dog_type => { breed: text }
                  pet      => <V> { value: V }
                  dogpet   => pet<dog_type>
                  holder   => { p: pet }
                """, "!holder { p: !dogpet { value: { breed: \"lab\" } } }"));
    }

    /** A container template has no dispatch, so naming one is still the error it always was. */
    @Test
    void aContainerTemplateIsStillNotAType() {
        assertTrue(load("f10", """
                  arr    => <T> [T]
                  texts  => arr<text>
                  holder => { p: arr }
                """).stream().anyMatch(d -> d.message().contains("is a template taking")));
    }
}
