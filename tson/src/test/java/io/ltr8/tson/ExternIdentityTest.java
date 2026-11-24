package io.ltr8.tson;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.RefBody;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static io.ltr8.tson.ExternWorld.ORDER_1;
import static io.ltr8.tson.ExternWorld.ORDER_2;
import static io.ltr8.tson.ExternWorld.extern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extern is a hop whose identity is the foreign entry's ([TSON-SCHEMA] §5.13, §8.3): every judgement through it
 * compares the declaring schema and path of the entry it lands on, never a spelling, the two namespaces spelling their
 * names independently.
 */
class ExternIdentityTest {

    private static final String LIB = "https://example.test/lib.tn";

    private final ExternWorld world = new ExternWorld().schema(LIB, """
            count    => !integer ^ { min: 0 }
            ext_card => !meta.extern { schema: "https://example.test/order-1.tn"  type: card }
            """);

    @Test
    void aReferenceOverAConstructorAdmitsAnExternWhereTheForeignEntrysConstructorIsIt() {
        String o1 = extern("o1", ORDER_1, "order");
        assertEquals(List.of(), world.validate(o1 + "h => { f?: meta.ref<meta.record> ~ o1  g?: meta.type_ref ~ o1 }"));
        assertEquals(List.of(), world.validate(extern("cnt", LIB, "count")
                + "h => { f?: meta.ref<numeric.integer_type> ~ cnt }"));
        List<String> refused = world.validate(o1 + "h => { f?: meta.ref<meta.enum_type> ~ o1 }");
        assertEquals(1, refused.size(), String.join("\n", refused));
        assertTrue(refused.getFirst().contains("'o1' is not a member of the kind"), refused.getFirst());
    }

    @Test
    void aKindsMembersListTheExternsLandingOnItsMembers() {
        TsonLinkedSchema linked = world.tson().resolve(ExternWorld.text("https://example.test/subject.tn",
                extern("o1", ORDER_1, "order") + extern("cnt", LIB, "count") + "own => { a: text }\n"
                        + "records => meta.ref<meta.record>"));
        RefBody records = assertInstanceOf(RefBody.class, linked.schema().entries().get("records"));
        assertTrue(records.memberNames().containsAll(List.of("o1", "own")), records.memberNames().toString());
        assertFalse(records.memberNames().contains("cnt"), records.memberNames().toString());
    }

    @Test
    void twoExternsOfOneEntryAreOneVariant() {
        assertEquals(List.of("SCHEMA_ERROR /c: 'c' variants 'o1' and 'o1b' both resolve to 'order' of "
                        + "'example.test/order-1.tn' -- §5.4 requires each variant to resolve to a distinct type"),
                world.validate(extern("o1", ORDER_1, "order") + extern("o1b", ORDER_1, "order") + "c => (o1 | o1b)"));
    }

    @Test
    void anImportBesideAnExternOfTheSameEntryIsOneVariant() {
        assertEquals(List.of("SCHEMA_ERROR /c: 'c' variants 'order' and 'o1' both resolve to 'order' of "
                        + "'example.test/order-1.tn' -- §5.4 requires each variant to resolve to a distinct type"),
                world.validate(extern("o1", ORDER_1, "order") + "c => (order | o1)", ORDER_1));
    }

    @Test
    void anExternOfAnExternBesideItsTargetIsOneVariant() {
        assertEquals(List.of("SCHEMA_ERROR /c: 'c' variants 'ec' and 'cc' both resolve to 'card' of "
                        + "'example.test/order-1.tn' -- §5.4 requires each variant to resolve to a distinct type"),
                world.validate(extern("ec", LIB, "ext_card") + extern("cc", ORDER_1, "card") + "c => (ec | cc)"));
    }

    @Test
    void anExternIsClassifiedForDisjointnessAtTheEntryItLandsOn() {
        TsonLinkedSchema linked = world.tson().resolve(ExternWorld.text("https://example.test/subject.tn",
                extern("o1", ORDER_1, "order") + extern("o2", ORDER_2, "order") + extern("cnt", LIB, "count")
                        + "two => (o1 | o2)\nmixed => (o1 | cnt)"));
        assertEquals(Optional.of(false),
                assertInstanceOf(ChoiceBody.class, linked.schema().entries().get("two")).disjoint());
        assertEquals(Optional.of(true),
                assertInstanceOf(ChoiceBody.class, linked.schema().entries().get("mixed")).disjoint());
        assertEquals(List.of(), world.validate(extern("o1", ORDER_1, "order") + "c => @meta.disjoint (o1 | text)"));
        List<String> refused = world.validate(extern("o1", ORDER_1, "order") + extern("o2", ORDER_2, "order")
                + "c => @meta.disjoint (o1 | o2)");
        assertEquals(1, refused.size(), String.join("\n", refused));
        assertTrue(refused.getFirst().contains("asserts @meta.disjoint, but its variants [o1, o2] are not disjoint"),
                refused.getFirst());
    }

    @Test
    void aRestatedFieldTypedByAnotherExternOfTheSameEntryNarrows() {
        assertEquals(List.of(), world.validate(extern("o1", ORDER_1, "order") + extern("o1b", ORDER_1, "order")
                + "base => { f: o1 }\nnarrow => base & { f: o1b }"));
    }
}
