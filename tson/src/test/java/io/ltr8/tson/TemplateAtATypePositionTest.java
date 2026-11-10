package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming a template at a <b>type position</b> (§5.10): refused, whatever the template's body. A template is not a
 * type, so no position names one bare -- a record template, a selector template, a container template, a
 * constructor application template and an alias template alike -- and the message names the remedy: write the
 * application, or name the abstract record a family's applications compose. Reading a family declared through a
 * template is {@code TemplateIsNotATypeTest}'s.
 */
class TemplateAtATypePositionTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!import:"%s"
            !!import:"%s"
            !schema
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static List<Diagnostic> validate(String id, String declarations) {
        return Tson.standard().validateSchema(HEADER.formatted(id) + "{\n" + declarations + "}");
    }

    private static String refusal(String id, String declarations) {
        List<Diagnostic> diagnostics = validate(id, declarations);
        assertTrue(!diagnostics.isEmpty(), "expected a refusal, got none");
        return diagnostics.stream().map(Diagnostic::message).reduce("", (a, b) -> a + "\n" + b);
    }

    private static final String REMEDY = "a template is not a type -- write";

    @Test
    void aRecordTemplateIsRefusedAtAFieldPosition() {
        String message = refusal("t1", """
                  pet    => <V> { value: V }
                  holder => { p: pet }
                """);
        assertTrue(message.contains("'pet' is a template taking 1 type argument") && message.contains(REMEDY),
                message);
        assertTrue(message.contains("name the abstract record its applications compose"), message);
    }

    /** And in a container: "any pet" is written with a record base, never with the template. */
    @Test
    void aRecordTemplateIsRefusedAtAnElementPosition() {
        assertTrue(refusal("t2", """
                  pet    => <V> { value: V }
                  kennel => { pets: [pet] }
                """).contains(REMEDY));
    }

    /** A selector on the template changes nothing: it is each application's, and the template is still no type. */
    @Test
    void aSelectorTemplateIsRefusedToo() {
        assertTrue(refusal("t3", """
                  pet    => abstract <V> { type: text =?  value: V }
                  holder => { p: pet }
                """).contains(REMEDY));
    }

    /**
     * Every template is an open record, so there is no other kind to name bare: a parameter list before a container,
     * a construction or a reference is refused at its own declaration (§5.10).
     */
    @Test
    void aTemplateOverAnythingButARecordIsRefusedWhereItIsDeclared() {
        for (String declaration : List.of("arr    => <T> [T]",
                "vector => <T> !meta.array { element_type: T  min_items: 3  max_items: 3 }",
                "pair   => <A, B> { first: A  second: B }\n  uuid_pair => <B> pair<uuid, B>")) {
            assertTrue(refusal("t4", "  " + declaration + "\n").contains("a template's body is a record body"),
                    declaration);
        }
    }

    /** An application has to match arity. */
    @Test
    void aWrongArgumentCountIsStillRefused() {
        assertTrue(refusal("t7", """
                  pet    => <T, V> { type: T  value: V }
                  holder => { p: pet<text> }
                """).contains("takes 2 type arguments"));
    }
}
