package io.ltr8.tson;

import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeDefinition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Dispatching a body whose constructor name is not its bound class's own.
 *
 * <p>§5's array family -- {@code array}, {@code set}, {@code array_min}, {@code array_max}, {@code
 * array_ranged}, {@code vector} -- all resolve to one body shape, and this model binds all of them to
 * {@link ArrayBody}. The {@code DataNameBinder} is where that is written down. The union dispatcher used to
 * decide membership by matching the type-ref against each member's {@code @Typename} and simple class name
 * instead, which cannot see an alias at all, so a conforming {@code !set} body was refused as not a member
 * of {@code top} -- by the one part of the pipeline that had not been told what the binder knows.
 */
class AliasedConstructorDispatchTest {

    private static Tson tson() {
        return Tson.builder().dataBindContext(SchemaMetaNameBinder.defaultContext()).build();
    }

    /** A {@code type_definition} whose body is written with {@code constructor}'s own name. */
    private static TypeDefinition read(String body) {
        return tson().objectReader().withSchema(TsonBundledSchemas.META_ID)
                .readAs("!type_definition { kind: PRODUCT  body: " + body + " }",
                        "type_definition", TypeDefinition.class);
    }

    /** The name the class carries itself still works -- the fallback passes are untouched. */
    @Test
    void aBodyNamedForItsOwnClassDispatches() {
        assertInstanceOf(ArrayBody.class, read("!array { element_type: token }").body());
    }

    /** And the one that is only a name in the binder, which is the case that failed. */
    @Test
    void aBodyNamedByAnAliasOfItsClassDispatchesToo() {
        TypeDefinition definition = read("!set { element_type: token }");

        ArrayBody body = assertInstanceOf(ArrayBody.class, definition.body());
        assertEquals("token", body.elementType().name());
    }

    /**
     * {@code set} is the only alias the bundled schemas declare -- {@code array_min}/{@code array_max}/
     * {@code array_ranged}/{@code vector} are in the binder's table but in no kernel or meta namespace, so
     * naming one is an unknown type before dispatch is reached at all. The rule under test is per name, not
     * per family, and {@code set} is where the family is observable.
     */
    @Test
    void aConstructorTheNamespaceDoesNotDeclareIsUnknownBeforeDispatch() {
        assertEquals(1, tson().validate("""
                !!schema:"https://tson.io/2026/32/m/meta.tn"
                !type_definition { kind: PRODUCT  body: !array_min { element_type: token } }""")
                .size());
    }

    /** A name that is nobody's -- neither a class's own nor an alias -- is still not a member. */
    @Test
    void aNameNothingBindsIsStillRefused() {
        assertEquals(1, tson().validate("""
                !!schema:"https://tson.io/2026/32/m/meta.tn"
                !type_definition { kind: PRODUCT  body: !no_such_constructor { element_type: token } }""")
                .size());
    }

    /** A record body still dispatches to its own class, not to whatever the binder last resolved. */
    @Test
    void anUnaliasedBodyIsUnaffected() {
        assertInstanceOf(RecordBody.class, read("!record { fields: [] }").body());
    }
}
