package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TemplateBody;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @discriminator} written inside a <b>template</b> body survives being held and closed.
 *
 * <p>§5.10 holds an open entry's body as the application written out, and {@code WireForm} is the one
 * spelling of that text. A field's name, type, state and value all travel through it; the discriminator flag
 * did not, so an author's mark was dropped between the declaration and the entry materialisation minted --
 * the pin arriving at the member while the mark that gives it meaning did not.
 *
 * <p>Nothing reads it back specially: {@code record_field.discriminator} is a declared kernel member, so the
 * constructor's own reader binds it when the closed body is read, exactly as it binds the pin beside it.
 */
class TemplateDiscriminatorTest {

    private static final String ID = "https://example.test/template-discriminator.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/template-discriminator.tn"
                !!meta:"https://tson.io/2026/36/m/meta.tn"
                !!import:"https://tson.io/2026/36/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    /** The entry a `name => head<args>` alias resolves to -- the instantiation materialisation minted. */
    private static String target(TsonCompiledSchema compiled, String alias) {
        return compiled.schema().entries().get(alias).source().orElseThrow().name();
    }

    private static RecordField fieldOf(TsonCompiledSchema compiled, String entry, String field) {
        RecordBody body = (RecordBody) compiled.schema().entries().get(entry).body();
        return body.fields().stream().filter(f -> f.name().equals(field)).findFirst().orElseThrow();
    }

    private static final String FAMILY = """
              dog_type => { breed: text }
              pet      => <T, V> { @discriminator type: text = T  value: V }
              dogpet   => pet<"dog", dog_type>
            """;

    /**
     * <b>The held body states the mark, because the template's field is the base's.</b> It is REQUIRED with
     * the parameter standing in {@code value} (§5.7 fixes it downstream), so the mark belongs there -- and
     * without it a template family could never be sealed, the mark having been dropped between the
     * declaration and anything able to read it.
     */
    @Test
    void theHeldBodyStatesTheMark() {
        TsonCompiledSchema compiled = compile(FAMILY);

        TemplateBody held = (TemplateBody) compiled.schema().entries().get("pet").body();

        assertTrue(held.template().contains("discriminator: true"),
                () -> "the mark the author wrote survives into the held text: " + held.template());
    }

    /**
     * <b>And the instantiation does not, because it has pinned the selector.</b> The mark says which field a
     * family dispatches on, which is the base's statement; a member restates the selector to pin it and
     * carries the value instead, so fixation is where the mark stops (§5.2).
     */
    @Test
    void theClosedMemberCarriesThePinAndNotTheMark() {
        TsonCompiledSchema compiled = compile(FAMILY);

        RecordField selector = fieldOf(compiled, target(compiled, "dogpet"), "type");

        assertEquals("dog", selector.value().orElseThrow().text(), "the argument's pin arrives");
        assertEquals(false, selector.discriminator(),
                () -> "and the mark stays with the base rather than being copied onto a member: " + selector);
    }

    /** A field the author never marked is unmarked either way: the member is written only where it is set. */
    @Test
    void anUnmarkedFieldStaysUnmarked() {
        TsonCompiledSchema compiled = compile(FAMILY);

        assertEquals(false, fieldOf(compiled, target(compiled, "dogpet"), "value").discriminator());
    }
}
