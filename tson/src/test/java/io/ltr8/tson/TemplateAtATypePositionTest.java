package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming a template at a <b>type position</b> ({@code SPEC-FEEDBACK.md} #13): admitted where the template has
 * a parent, refused where it has none.
 *
 * <p><b>Why a record-bodied template may be named and a container may not.</b> "A template is not a type" is
 * a rule about <em>elimination</em>: a position typed {@code arr} would have to read a value against an
 * element type nothing has fixed, leaving a reader to infer arguments from the payload -- which is not
 * underspecified but ill-defined, an empty array determining nothing and two instantiations accepting one
 * document. None of that reaches a parent: a value there is a value of some member, selected by a tag or by
 * the discriminators, and every member is a closed entry with its arguments already fixed. The existential is
 * eliminated by dispatch, never by inference.
 *
 * <p>So the test is the parent's existence, which {@code template.extension} states: present for a record
 * body, absent for a container, a constructor application and a reference template alike.
 *
 * <p><b>Reading such a position is a later step.</b> Until the family dispatcher lands, a value at a
 * template-typed position still meets {@code OpenTemplateReader}'s refusal -- what this step changes is
 * whether the schema loads at all.
 */
class TemplateAtATypePositionTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static List<Diagnostic> validate(String id, String declarations) {
        return Tson.standard().validateSchema(HEADER.formatted(id) + "{\n" + declarations + "}");
    }

    private static String refusal(String id, String declarations) {
        List<Diagnostic> diagnostics = validate(id, declarations);
        assertTrue(!diagnostics.isEmpty(), "expected a refusal, got none");
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    // ── Admitted: the template has a parent ──────────────────────────────

    @Test
    void aRecordTemplateMayBeNamedAtAFieldPosition() {
        assertEquals(List.of(), validate("t1", """
                  pet    => <T, V> { type: text = T  value: V }
                  holder => { p: pet }
                """));
    }

    /** And in a container, which is the position the shape exists for: "any pet". */
    @Test
    void aRecordTemplateMayBeNamedAtAnElementPosition() {
        assertEquals(List.of(), validate("t2", """
                  pet    => <T, V> { type: text = T  value: V }
                  kennel => { pets: [pet] }
                """));
    }

    /** A labelled sum is the same rule with a discriminator: its parent is SEALED rather than ABSTRACT. */
    @Test
    void aSealedRecordTemplateMayBeNamedToo() {
        assertEquals(List.of(), validate("t3", """
                  pet    => <T, V> { @discriminator type: text = T  value: V }
                  holder => { p: pet }
                """));
    }

    // ── Refused: no parent to name ───────────────────────────────────────

    @Test
    void aContainerTemplateIsStillRefused() {
        assertTrue(refusal("t4", """
                  arr    => <T> [T]
                  holder => { p: arr }
                """).contains("is a template taking 1 type argument"));
    }

    @Test
    void aConstructorApplicationTemplateIsStillRefused() {
        assertTrue(refusal("t5", """
                  vector => <T, N> !array { element_type: T  min_items: N  max_items: N }
                  holder => { p: vector }
                """).contains("a template is not a type until it is applied"));
    }

    /** A reference is indirection, not a type of its own, so there is nothing for a parent to hold. */
    @Test
    void aReferenceTemplateIsStillRefused() {
        assertTrue(refusal("t6", """
                  pair      => <A, B> { first: A  second: B }
                  uuid_pair => <B> pair<uuid, B>
                  holder    => { p: uuid_pair }
                """).contains("a template is not a type until it is applied"));
    }

    /** An application still has to match arity: relaxing the bare-name case changes nothing else. */
    @Test
    void aWrongArgumentCountIsStillRefused() {
        assertTrue(refusal("t7", """
                  pet    => <T, V> { type: text = T  value: V }
                  holder => { p: pet<text> }
                """).contains("takes 2 type arguments"));
    }
}
