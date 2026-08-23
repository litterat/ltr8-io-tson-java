package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value parameter filling a {@code =} field ([TSON-SCHEMA] §5.7's "Open modifiers"): {@code status: int32
 * = S} in a template, applied as {@code <order, 201>}.
 *
 * <p>The literal form beside it is the control. Both say the same thing about the closed type -- the status
 * is 201 -- so a document writing 999 must be refused against either, and the templated form is the one an
 * API description actually writes.
 */
class ValueParamFixedFieldTest {

    private static final String ID = "https://example.test/value-param.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/value-param.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              order    => { id: text }
              literal  => { status: int32 = 201  body: order }
              response => <T, S> { status: int32 = S  body: T }
              created  => response<order, 201>
            }
            """;

    private static Tson tson() {
        TsonSchemaSource source = uri -> {
            if (TsonCanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return Tson.builder().schemaSource(source).build();
    }

    /** The field of the entry {@code created} resolves to, following its alias hop to the instantiation. */
    private static RecordField statusOf(TsonLinkedSchema linked, String entry) {
        var definition = linked.schema().entries().get(entry);
        if (definition.body() instanceof Reference alias) {
            definition = linked.schema().entries().get(alias.target());
        }
        return ((RecordBody) definition.body()).fields().stream()
                .filter(field -> field.name().equals("status")).findFirst().orElseThrow();
    }

    private static List<Diagnostic> validate(Tson tson, String type) {
        return tson.validate("!!schema:\"" + ID + "\"\n!" + type + " { status: 999  body: { id: \"a\" } }");
    }

    /**
     * The declaration is <b>right</b>, and deliberately so: §5.7 says a parametric {@code = P} places the
     * field in REQUIRED, "because nothing is fixed at declaration -- the value does not exist yet". Pinned
     * because the obvious fix for the defect below is to make this REQUIRED_FIXED, which would put this
     * implementation at odds with the one sentence the spec is explicit about.
     */
    @Test
    void anOpenTemplatesParametricFixedFieldStaysRequiredWithTheParameterRecorded() {
        TsonLinkedSchema linked = tson().resolve(SCHEMA);

        RecordField open = ((RecordBody) linked.schema().entries().get("response").body()).fields().get(0);
        assertEquals(FieldState.REQUIRED, open.state());
        assertTrue(open.value().isEmpty(), "nothing is fixed at declaration");
        assertEquals("S", open.valueParam().orElseThrow(), "the parameter rides the value channel");
    }

    /**
     * Substitution is where §5.7's "fixation happens downstream, where values are concrete" applies: the
     * argument arrives, so the field is fixed to it, and the templated form lands on exactly the state the
     * literal form beside it has. Before this, the closed entry carried the right value on a field whose
     * state no longer said it was fixed.
     */
    @Test
    void aMaterialisedValueParameterFieldIsFixedToItsArgument() {
        TsonLinkedSchema linked = tson().resolve(SCHEMA);

        RecordField materialised = statusOf(linked, "created");
        assertEquals("201", materialised.value().orElseThrow().text());
        assertEquals(statusOf(linked, "literal").state(), materialised.state(),
                "the templated form says what the literal form says");
        assertEquals(FieldState.REQUIRED_FIXED, materialised.state());
    }

    /**
     * A routed <em>default</em> is not promoted: §5.7 sends {@code ~ P} to REQUIRED_DEFAULT, and a default
     * that data may override is the whole difference between the two spellings. The promotion above reads
     * the state to tell them apart, so this is the half that says it reads it correctly.
     */
    @Test
    void aMaterialisedValueParameterDefaultStaysADefault() {
        String schema = SCHEMA.replace("status: int32 = S", "status: int32 ~ S");
        TsonSchemaSource source = uri -> schema;
        Tson tson = Tson.builder().schemaSource(source).build();

        RecordField materialised = statusOf(tson.resolve(schema), "created");

        assertEquals("201", materialised.value().orElseThrow().text());
        assertEquals(FieldState.REQUIRED_DEFAULT, materialised.state());
    }

    /** What it costs at read time, which is the whole reason it matters. */
    @Test
    void aWrongValueIsRefusedAgainstTheLiteralFormAndAcceptedAgainstTheTemplatedOne() {
        Tson tson = tson();

        assertEquals(Diagnostic.Code.FIELD_FIXED, validate(tson, "literal").get(0).code());
        assertEquals(Diagnostic.Code.FIELD_FIXED, validate(tson, "created").get(0).code(),
                "999 against a type whose schema says the status is 201 must be refused");
    }
}
