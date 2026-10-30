package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Dispatching a body whose constructor name is not its bound class's own.
 *
 * <p>§5's array family -- {@code array} and {@code set_type} -- resolves to one body shape, and this model
 * binds both to {@link ArrayBody}. The {@code DataNameBinder} is where that is written down. The union
 * dispatcher used to decide membership by matching the type-ref against each member's {@code @Typename} and
 * simple class name instead, which cannot see an alias at all, so a conforming {@code !set_type} body was
 * refused as not a member of {@code top} -- by the one part of the pipeline that had not been told what the
 * binder knows.
 */
class AliasedConstructorDispatchTest {

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaMetaNameBinder.defaultContext()));
    }

    /** An entry's value, written with {@code constructor}'s own name, read at a {@code top} position. */
    private static Top read(String body) {
        return tson().objectReader().withSchema(TsonBundledSchemas.META_ID).readAs(body, "top", Top.class);
    }

    /** The name the class carries itself still works -- the fallback passes are untouched. */
    @Test
    void aBodyNamedForItsOwnClassDispatches() {
        assertInstanceOf(ArrayBody.class, read("!array { element_type: text }"));
    }

    /** And the one that is only a name in the binder, which is the case that failed. */
    @Test
    void aBodyNamedByAnAliasOfItsClassDispatchesToo() {
        Top definition = read("!set_type { element_type: text }");

        ArrayBody body = assertInstanceOf(ArrayBody.class, definition);
        assertEquals("text", body.elementType().name());
    }

    /** A name that is nobody's -- neither a class's own nor an alias -- is still not a member. */
    @Test
    void aNameNothingBindsIsStillRefused() {
        assertEquals(1, tson().validate("""
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !schema { x => !no_such_constructor { element_type: token } }""")
                .size());
    }

    /** A record body still dispatches to its own class, not to whatever the binder last resolved. */
    @Test
    void anUnaliasedBodyIsUnaffected() {
        assertInstanceOf(RecordBody.class, read("!record { fields: {} }"));
    }
}
