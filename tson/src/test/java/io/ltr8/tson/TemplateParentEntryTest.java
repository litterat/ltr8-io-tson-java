package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The <b>parent entry</b> a record-bodied template gets ({@code SPEC-FEEDBACK.md} #13): a closed entry the
 * resolver mints, carrying whatever survives erasure, which a type position written {@code pet} resolves to.
 *
 * <p><b>Why an entry rather than the template itself.</b> §1.3 promises that a consumer ingesting only
 * resolved schema values is fully conforming with no support for templates, "since every entry a data
 * document's type can reach is closed by the closed-entry rule". A field typed by a template would break that
 * promise exactly. Minting the base keeps it literally: resolved output never names a template at a position,
 * and the entry is an ordinary closed record every consumer already reads.
 *
 * <p><b>What survives erasure</b> is stated by #13 and is what these cases pin: a field whose type is
 * parameter-free stays, dropping any value-parameter pin ({@code type: text = T} becomes {@code type: text});
 * a field whose type mentions a type parameter is omitted, nothing before dispatch reading it and the
 * member's own entry typing it. So a parent may legally be empty, and dispatches by tag when it is.
 *
 * <p><b>The extension is derived, never marked</b> -- SEALED where a discriminator survives, ABSTRACT
 * otherwise -- which is the parent's own level. The author's {@code @abstract} is the <em>instantiation's</em>
 * fact and does not reach here.
 */
class TemplateParentEntryTest {

    private static final String HEADER = """
            !!id:"https://example.test/%s.tn"
            !!meta:"%s"
            !!import:"%s"
            """.formatted("%s", TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static TsonLinkedSchema linked(String id, String declarations) {
        return Tson.standard().resolve(HEADER.formatted(id) + "{\n" + declarations + "}");
    }

    /** The entry a field typed by a bare template name actually resolves to. */
    private static TypeDefinition parentOf(TsonLinkedSchema schema, String holder, String field) {
        RecordBody body = assertInstanceOf(RecordBody.class,
                schema.schema().entries().get(holder).body(), holder + " is a record");
        String typeName = body.fields().stream().filter(f -> f.name().equals(field)).findFirst()
                .orElseThrow(() -> new AssertionError("no field " + field)).type().name();
        TypeDefinition parent = schema.schema().entries().get(typeName);
        assertTrue(parent != null, () -> "the field's type names no entry: " + typeName);
        return parent;
    }

    private static List<String> fieldNames(TypeDefinition definition) {
        return assertInstanceOf(RecordBody.class, definition.body()).fields().stream()
                .map(RecordField::name).toList();
    }

    // ── The entry exists and a position resolves to it ───────────────────

    /** The headline: a field typed by a bare template name is a closed record entry, not the template. */
    @Test
    void aFieldTypedByATemplateResolvesToAClosedEntry() {
        TsonLinkedSchema schema = linked("p1", """
                  dog_type => { breed: text }
                  pet      => <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        TypeDefinition parent = parentOf(schema, "holder", "p");
        assertInstanceOf(RecordBody.class, parent.body(), "the parent is an ordinary closed record");
        assertTrue(parent.parameters().isEmpty(), () -> "closed: " + parent.parameters());
    }

    /** §1.3's promise, stated as the property it is: no entry in resolved output is open at a position. */
    @Test
    void resolvedOutputNamesNoTemplateAtAPosition() {
        TsonLinkedSchema schema = linked("p2", """
                  dog_type => { breed: text }
                  pet      => <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        schema.schema().entries().forEach((name, definition) -> {
            if (!(definition.body() instanceof RecordBody record)) {
                return;
            }
            record.fields().forEach(field -> {
                TypeDefinition target = schema.schema().entries().get(field.type().name());
                assertFalse(target != null && !target.parameters().isEmpty(),
                        () -> name + "." + field.name() + " is typed by a template: " + field.type().name());
            });
        });
    }

    // ── What survives erasure ────────────────────────────────────────────

    /** A parameter-free field stays and loses its value-parameter pin; a parameter-typed field is omitted. */
    @Test
    void erasureKeepsTheParameterFreeFieldsOnly() {
        TsonLinkedSchema schema = linked("p3", """
                  dog_type => { breed: text }
                  pet      => <T, V> { type: text = T  name: text  value: V }
                  dogpet   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        TypeDefinition parent = parentOf(schema, "holder", "p");
        assertEquals(List.of("type", "name"), fieldNames(parent),
                "`value: V` is typed by a parameter and is omitted");
    }

    /** The pin goes with the parameter: `type: text = T` erases to a plain `text` field. */
    @Test
    void aValueParameterPinDoesNotSurvive() {
        TsonLinkedSchema schema = linked("p4", """
                  dog_type => { breed: text }
                  pet      => <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        RecordField type = assertInstanceOf(RecordBody.class, parentOf(schema, "holder", "p").body())
                .fields().get(0);
        assertTrue(type.value().isEmpty(), () -> "no pin survives: " + type.value());
    }

    /** A parent with nothing to keep is legal and empty -- it dispatches by tag. */
    @Test
    void aParentMayBeEmpty() {
        TsonLinkedSchema schema = linked("p5", """
                  dog_type => { breed: text }
                  pet      => <V> { value: V }
                  dogpet   => pet<dog_type>
                  holder   => { p: pet }
                """);

        assertEquals(List.of(), fieldNames(parentOf(schema, "holder", "p")));
    }

    // ── The derived extension ────────────────────────────────────────────

    @Test
    void aSurvivingDiscriminatorMakesTheParentSealed() {
        TsonLinkedSchema schema = linked("p6", """
                  dog_type => { breed: text }
                  pet      => <T, V> { @discriminator type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        assertEquals(RecordExtensionType.SEALED,
                assertInstanceOf(RecordBody.class, parentOf(schema, "holder", "p").body()).extension());
    }

    @Test
    void withoutOneTheParentIsAbstract() {
        TsonLinkedSchema schema = linked("p7", """
                  dog_type => { breed: text }
                  pet      => <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        assertEquals(RecordExtensionType.ABSTRACT,
                assertInstanceOf(RecordBody.class, parentOf(schema, "holder", "p").body()).extension());
    }

    /** And the members index under it, which is what step 4 built and what dispatch will read. */
    @Test
    void theMembersIndexUnderTheParentEntry() {
        TsonLinkedSchema schema = linked("p8", """
                  dog_type => { breed: text }
                  cat_type => { indoor: boolean }
                  pet      => <T, V> { @discriminator type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  catpet   => pet<"cat", cat_type>
                  holder   => { p: pet }
                """);

        assertEquals(2, parentOf(schema, "holder", "p").subtypes().size(),
                () -> "both members: " + parentOf(schema, "holder", "p").subtypes());
    }

    // ── An application is not a position typed by the parent ─────────────

    /**
     * The one distinction the rewrite must get right: {@code pet<"dog", dog_type>} is an instantiation's
     * <b>head</b>, not a position typed by the base. Repointing it would aim every member at the parent and
     * leave the family with no members at all, so the rename fires on a bare name only.
     */
    @Test
    void anApplicationKeepsNamingTheTemplate() {
        TsonLinkedSchema schema = linked("p10", """
                  dog_type => { breed: text }
                  pet      => <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        // `dogpet` is an alias: its own source names the instantiation materialisation minted, and that
        // entry is the one recording the application. Both hops matter, so both are asserted.
        String instantiation = schema.schema().entries().get("dogpet").source().orElseThrow().name();
        assertEquals("pet", schema.schema().entries().get(instantiation).source().orElseThrow().name(),
                "the member still closes `pet`, not the parent entry");
        assertEquals(2, schema.schema().entries().get(instantiation).source().orElseThrow().arguments().size());
    }

    /** And the template itself is still a template -- minting a parent does not close it. */
    @Test
    void theTemplateIsStillOpen() {
        TsonLinkedSchema schema = linked("p11", """
                  dog_type => { breed: text }
                  pet      => <T, V> { type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                  holder   => { p: pet }
                """);

        assertFalse(schema.schema().entries().get("pet").parameters().isEmpty(),
                "`pet` remains the open template it was declared as");
    }

    /**
     * An <b>imported</b> template's parent is the entry its own schema minted, so a position naming it
     * repoints exactly as a local one does. The name is a function of the form, which is what makes the two
     * schemas agree without either knowing about the other.
     */
    @Test
    void anImportedTemplateIsNamedAtAPositionToo() {
        String library = """
                !!id:"https://example.test/lib.tn"
                !!meta:"%s"
                !!import:"%s"
                {
                  dog_type => { breed: text }
                  pet      => <T, V> { @discriminator type: text = T  value: V }
                  dogpet   => pet<"dog", dog_type>
                }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);
        String user = """
                !!id:"https://example.test/user.tn"
                !!meta:"%s"
                !!import:"https://example.test/lib.tn"
                {
                  holder => { p: pet }
                }""".formatted(TsonBundledSchemas.META_ID);

        Tson tson = Tson.of(io.ltr8.tson.base.ProcessorConfig.defaults()
                .withSchemaAccess(io.ltr8.tson.base.source.SchemaAccess.of(
                        uri -> uri.contains("lib.tn") ? library : user)));
        tson.resolve(library);
        TsonLinkedSchema linked = tson.resolve(user);

        TypeDefinition parent = parentOf(linked, "holder", "p");
        assertInstanceOf(RecordBody.class, parent.body(), "an importer reaches the parent, not the template");
        assertTrue(parent.parameters().isEmpty(), () -> "closed: " + parent.parameters());
        assertEquals(RecordExtensionType.SEALED,
                assertInstanceOf(RecordBody.class, parent.body()).extension());
    }

    // ── A template with no parent is unchanged ───────────────────────────

    /** A container template mints no parent, so naming one at a position is still refused. */
    @Test
    void aContainerTemplateStillHasNoParentToName() {
        List<Diagnostic> diagnostics = Tson.standard().validateSchema(HEADER.formatted("p9") + """
                {
                  arr    => <T> [T]
                  texts  => arr<text>
                  holder => { p: arr }
                }""");

        assertTrue(diagnostics.stream().anyMatch(d -> d.message().contains("is a template taking")),
                () -> "still refused: " + diagnostics);
    }
}
