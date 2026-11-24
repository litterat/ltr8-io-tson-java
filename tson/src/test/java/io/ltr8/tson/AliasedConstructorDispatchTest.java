package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;

import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dispatching a body at a {@code meta.top} position by its tag, through what the {@code DataNameBinder} binds the
 * tag to: a constructor's own name dispatches to its class, and a name that is no type's -- an incomplete entry, a
 * name nothing declares -- is no member of {@code top}.
 */
class AliasedConstructorDispatchTest {

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaModelNameBinder.defaultContext()));
    }

    /** An entry's value, written with {@code constructor}'s own name, read at a {@code meta.top} position. */
    private static Top read(String body) {
        return tson().objectReader().withSchema(TsonBundledSchemas.META_ID).readAs(body, "meta.top", Top.class);
    }

    /** The name the class carries itself dispatches. */
    @Test
    void aBodyNamedForItsOwnClassDispatches() {
        assertInstanceOf(ArrayBody.class, read("!meta.array { element_type: text }"));
    }

    /** A set is an array: {@code meta.set_type} is an incomplete entry, no type, and its name is no tag (§5.5). */
    @Test
    void aBodyTaggedByAnIncompleteEntryIsRefused() {
        assertTrue(assertThrows(ReadException.class, () -> read("!meta.set_type { element_type: text }"))
                .getMessage().contains("meta.set_type"));
        ArrayBody set = assertInstanceOf(ArrayBody.class,
                read("!meta.array { element_type: text  ordered: false  unique_items: true }"));
        assertEquals("text", set.elementType().text());
    }

    /** A name that is nobody's -- neither a class's own nor an alias -- is still not a member. */
    @Test
    void aNameNothingBindsIsStillRefused() {
        assertEquals(1, tson().validate("""
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema { x => !no_such_constructor { element_type: token } }""")
                .size());
    }

    /** A record body still dispatches to its own class, not to whatever the binder last resolved. */
    @Test
    void anUnaliasedBodyIsUnaffected() {
        assertInstanceOf(RecordBody.class, read("!meta.record { fields: {} }"));
    }
}
