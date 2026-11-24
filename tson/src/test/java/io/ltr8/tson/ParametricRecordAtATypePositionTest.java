package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naming a parametric record at a <b>type position</b> (§5.10): refused, whatever the parametric record's body. A parametric
 * record is not a type, so no position names one bare -- a parametric record, a parametric record with a selector, a
 * parametric container, a parametric constructor application and a parametric alias alike -- and the message names the
 * remedy: write the application, or name the abstract record a family's applications compose. Reading a family declared
 * through a parametric record is {@code ParametricRecordIsNotATypeTest}'s.
 */
class ParametricRecordAtATypePositionTest {

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

    private static final String REMEDY = "no position names a parametric record bare -- write";

    @Test
    void aParametricRecordIsRefusedAtAFieldPosition() {
        String message = refusal("t1", """
                  pet    => <V> { value: V }
                  holder => { p: pet }
                """);
        assertTrue(message.contains("'pet' is a parametric record taking 1 type argument") && message.contains(REMEDY),
                message);
        assertTrue(message.contains("name the abstract record its applications compose"), message);
    }

    /** And in a container: "any pet" is written with a record base, never with the parametric record. */
    @Test
    void aParametricRecordIsRefusedAtAnElementPosition() {
        assertTrue(refusal("t2", """
                  pet    => <V> { value: V }
                  kennel => { pets: [pet] }
                """).contains(REMEDY));
    }

    /**
     * A selector on the parametric record changes nothing: it is each application's, and the parametric record is still no
     * type.
     */
    @Test
    void aParametricRecordWithASelectorIsRefusedToo() {
        assertTrue(refusal("t3", """
                  pet    => abstract <V> { type: text =?  value: V }
                  holder => { p: pet }
                """).contains(REMEDY));
    }

    /**
     * Every entry with parameters is a parametric record, so there is no other kind to name bare: a parameter list before a
     * container, a construction or a reference is refused at its own declaration (§5.10).
     */
    @Test
    void aParametricRecordOverAnythingButARecordIsRefusedWhereItIsDeclared() {
        for (String declaration : List.of("arr    => <T> [T]",
                "vector => <T> !meta.array { element_type: T  min_items: 3  max_items: 3 }",
                "pair   => <A, B> { first: A  second: B }\n  uuid_pair => <B> pair<uuid, B>")) {
            assertTrue(refusal("t4", "  " + declaration + "\n").contains("a parametric record's body is a record body"),
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
