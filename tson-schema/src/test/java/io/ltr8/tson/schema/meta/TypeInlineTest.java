package io.ltr8.tson.schema.meta;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A type written in place under the constructor that built it: identity is the constructor and the fields
 * ([TSON-SCHEMA] §1.2), and a message names it by its constructor's application where no sugar spells it.
 */
class TypeInlineTest {

    private static final ArrayBody AT_LEAST_ONE = new ArrayBody(TypeChain.EMPTY, TypeRef.of("uuid"), false, true,
            false, Optional.of(1L), Optional.empty());

    @Test
    void withNoConstructorGivenItIsTheOneTheBodyIsAnInstanceOf() {
        assertEquals("meta.array", new TypeInline(AT_LEAST_ONE).constructor());
        assertEquals("meta.reference_type", new TypeInline(ReferenceBody.of("order")).constructor());
        assertEquals(new TypeInline("meta.array", AT_LEAST_ONE), new TypeInline(AT_LEAST_ONE));
    }

    @Test
    void oneBodyUnderTwoConstructorsIsTwoTypes() {
        TypeInline sugar = new TypeInline(AT_LEAST_ONE);
        TypeInline refined = sugar.withConstructor("nonempty_type");
        assertNotEquals(sugar, refined);
        assertNotEquals(sugar.hashCode(), refined.hashCode());
        assertEquals(refined, new TypeInline("nonempty_type", AT_LEAST_ONE));
        assertEquals("nonempty_type", refined.withOf(ArrayBody.of(TypeRef.of("text"))).constructor());
    }

    @Test
    void aMessageNamesItBySugarOrByItsConstructorsApplication() {
        assertEquals("[uuid; 1..]", new TypeInline(AT_LEAST_ONE).text());
        assertEquals("nonempty_type<uuid>", new TypeInline("nonempty_type", AT_LEAST_ONE).text());
        ArrayBody set = new ArrayBody(TypeChain.EMPTY, TypeRef.of("text"), false, false, true, Optional.empty(),
                Optional.empty());
        assertEquals("set<text>", new TypeInline("meta.set_type", set).text());
        assertEquals("meta.ref<order>", new TypeInline("meta.ref", ReferenceBody.of("order")).text());
        assertEquals("{text => nonempty_type<uuid>}", new TypeInline(MapBody.of(TypeRef.of("text"),
                new TypeInline("nonempty_type", AT_LEAST_ONE))).text());
    }
}
