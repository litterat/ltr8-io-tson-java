package io.ltr8.tson;

import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.tson.schema.meta.TypeChain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A <b>closed application at a composition operand</b> -- {@code dog => pet<"dog"> & { breed: text }}, where
 * the argument is a literal rather than the declaring declaration's own parameter (§5.10).
 *
 * <p><b>The member is the author's own declaration and nothing is minted for the operand.</b> §5.8 absorbs
 * the operand's fields, substitution puts the argument in the routed {@code = T}, already FIXED where it is
 * written, and what outlives the substitution is that {@code dog} IS-A {@code pet}. An entry for
 * {@code pet<"dog">} would be a derivation step wearing an entry's clothes -- nothing names it, and the
 * member a document actually writes is {@code dog}.
 *
 * <p><b>So the edge is to the family base itself</b>, which is a participant in IS-A because its held body
 * carries {@code extension} (§5.10 as #13 corrects it). That is what puts {@code dog} in {@code pet.subtypes}
 * and makes a {@code pet} position dispatch -- by the discriminators where the base is SEALED, by a tag where
 * it is ABSTRACT.
 *
 * <p>The sibling shape, where the argument is the declaration's own parameter ({@code ok => <T> result<T> &
 * { … }}), is {@code SubtypeTemplateFamilyTest}'s: that operand stays open, the declaration is held, and each
 * instantiation mints its own edge as it closes.
 */
class ClosedOperandFamilyTest {

    private static final String SEALED = """
              pet    => <T> { pet_type?: text = T  name: text }
              dog    => pet<"dog"> & { breed: text }
              cat    => pet<"cat"> & { indoor: boolean }
              holder => { p: pet }
            """;

    private static final String ABSTRACT = """
              pet    => abstract <T> { pet_type?: text = T  name: text }
              dog    => pet<"dog"> & { breed: text }
              cat    => pet<"cat"> & { indoor: boolean }
              holder => { p: pet }
            """;

    private static String schema(String id, String body) {
        return """
                !!id:"https://example.test/%s.tn"
                !!meta:"%s"
                !!import:"%s"
                {
                %s}
                """.formatted(id, TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID, body);
    }

    private static TsonValue read(String id, String body, String document) {
        Tson tson = Tson.standard();
        tson.resolve(schema(id, body));
        return tson.treeReader().withSchema("https://example.test/" + id + ".tn").readAs(document, "holder");
    }

    @Test
    void theMemberIsIndexedUnderTheFamilyBaseItComposes() {
        TsonLinkedSchema linked = Tson.standard().resolve(schema("cof1", SEALED));

        assertEquals(java.util.List.of("dog", "cat"), TypeChain.subtypesOf(linked.schema().entries().get("pet")),
                "the base's members are the declarations that compose it");
        assertTrue(TypeChain.supertypesOf(linked.schema().entries().get("dog")).contains("pet"),
                () -> "dog IS-A pet: " + TypeChain.supertypesOf(linked.schema().entries().get("dog")));
    }

    /** The operand mints nothing: the only entries are the four the author wrote. */
    @Test
    void theOperandMintsNoEntryOfItsOwn() {
        TsonLinkedSchema linked = Tson.standard().resolve(schema("cof2", SEALED));

        assertEquals(java.util.List.of(), linked.schema().entries().keySet().stream()
                        .filter(name -> name.startsWith("pet_")).toList(),
                "an entry for the application would be a derivation step wearing an entry's clothes");
    }

    /** SEALED dispatches on the discriminator, so the member needs no tag. */
    @Test
    void aSealedBaseDispatchesOnThePinnedDiscriminator() {
        TsonValue value = read("cof3", SEALED, "{ p: { pet_type: \"dog\"  name: \"Rex\"  breed: \"corgi\" } }");

        assertEquals("corgi", value.at("/p/breed").asString().orElseThrow());
    }

    /** ABSTRACT dispatches on a tag, and the tag is the author's own member name. */
    @Test
    void anAbstractBaseDispatchesOnTheMembersOwnName() {
        TsonValue value = read("cof4", ABSTRACT,
                "{ p: !dog { pet_type: \"dog\"  name: \"Rex\"  breed: \"corgi\" } }");

        assertEquals("corgi", value.at("/p/breed").asString().orElseThrow());
    }
}
