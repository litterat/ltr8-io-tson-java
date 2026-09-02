package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
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
            definition = linked.schema().entries().get(alias.target().name());
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
     *
     * <p>The template's body is <b>held</b>, so the state and the parameter are read off the wire record it
     * holds rather than off a {@code RecordField}: {@code record_field.value_param} is gone, the parameter
     * standing in the ordinary {@code value} slot with §8.1's shadowing rule to tell it from a literal.
     */
    @Test
    void anOpenTemplatesParametricFixedFieldStaysRequiredWithTheParameterRecorded() {
        TsonLinkedSchema linked = tson().resolve(SCHEMA);

        TemplateBody held = assertInstanceOf(TemplateBody.class,
                linked.schema().entries().get("response").body());

        assertTrue(held.names().contains("S"), () -> "the parameter is named in the body: " + held.names());
        assertFalse(held.names().contains(FieldState.REQUIRED_FIXED.name()),
                () -> "nothing is fixed at declaration: " + held.names());
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

    /**
     * <b>All three template shapes route a value the same way</b>, which is what holding every open body
     * bought: a record template, a composition template absorbing a supertype's fields, and a refinement
     * template tightening its source all reach {@code REQUIRED_FIXED} with the applied argument. The two
     * absorbing shapes hold the <em>flattened</em> body, so what is substituted is one record however many
     * declarations contributed to it.
     */
    @Test
    void everyTemplateShapeFixesARoutedValueTheSameWay() {
        String schema = """
                !!id:"https://example.test/value-param.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  order    => { id: text }
                  base     => { status: int32  body: order }
                  fresh    => <T, S> { status: int32 = S  body: T }
                  composed => <T, S> base & { status: = S  body: T }
                  refined  => <S> base ^ { status: = S }
                  a => fresh<order, 201>
                  b => composed<order, 201>
                  c => refined<201>
                }
                """;
        TsonSchemaSource source = uri -> schema;
        TsonLinkedSchema linked = Tson.builder().schemaSource(source).build().resolve(schema);

        for (String entry : List.of("a", "b", "c")) {
            RecordField status = statusOf(linked, entry);
            assertEquals(FieldState.REQUIRED_FIXED, status.state(), entry);
            assertEquals("201", status.value().orElseThrow().text(), entry);
        }
    }

    /**
     * <b>The kernel declares one value channel, and a schema exercising every template shape resolves
     * against it.</b> {@code record_field}'s labelled {@code ( value | value_param )?} group is gone: a
     * routed parameter rides {@code value} like any other token, with §8.1's shadowing rule to tell it from a
     * literal. The separate channel existed only because a body read as constructor vocabulary at its
     * declaration cannot otherwise say which of the two a token is, and no body is read that way any more.
     *
     * <p>The fixture covers all four shapes at once, because the assertion is about the vocabulary rather
     * than about one field: if anything still needed a second channel, one of these would fail to resolve.
     */
    @Test
    void everyTemplateShapeResolvesAgainstTheSingleValueChannel() {
        String schema = """
                !!id:"https://example.test/value-param.tn"
                !!meta:"https://tson.io/2026/35/m/meta.tn"
                !!import:"https://tson.io/2026/35/m/core.tn"
                {
                  order    => { id: text }
                  base     => { status: int32  body: order }
                  fresh    => <T, S> { status: int32 = S  body: T }
                  composed => <T, S> base & { status: = S  body: T }
                  refined  => <S> base ^ { status: = S }
                  sized    => <N> { xs: [text; N..] }
                  a => fresh<order, 201>
                  b => composed<order, 201>
                  c => refined<201>
                  d => sized<2>
                }
                """;
        TsonSchemaSource source = uri -> schema;
        TsonLinkedSchema linked = Tson.builder().schemaSource(source).build().resolve(schema);

        // The kernel field is gone, so the compile-time proof is that this resolves at all; what is worth
        // asserting beyond that is that each closure kept the value its argument supplied.
        for (String entry : List.of("a", "b", "c")) {
            assertEquals("201", statusOf(linked, entry).value().orElseThrow().text(), entry);
        }
        assertTrue(linked.schema().entries().containsKey("d"), "the sized shape resolved too");
    }
}
