package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TemplateBody;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A selector inside a <b>template</b> body survives being held and closed.
 *
 * <p>§5.10 holds an open entry's body as the application written out, and {@code WireForm} is the one
 * spelling of that text. A record's fields travel through it, and so does the enclosing statement of which of
 * them a family dispatches on ({@code template.discriminators}); that statement did not, so an author's mark
 * was dropped between the declaration and the entry materialisation minted -- the pin arriving at the member
 * while the fact that gives it meaning did not.
 *
 * <p>Nothing reads it back specially: {@code discriminators} is a declared kernel member, so the
 * constructor's own reader binds it when the closed body is read, exactly as it binds the pins beside it.
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

    /**
     * The entry a {@code name => head<args>} declaration denotes: itself, since such a declaration <em>is</em>
     * its instantiation ({@code SPEC-FEEDBACK.md} #15), or the entry it aliases where an earlier declaration
     * already named the same application.
     */
    private static String target(TsonCompiledSchema compiled, String alias) {
        return compiled.schema().entries().get(alias).body() instanceof io.ltr8.tson.schema.meta.Reference ref
                ? ref.target().name() : alias;
    }

    private static RecordBody bodyOf(TsonCompiledSchema compiled, String entry) {
        return (RecordBody) compiled.schema().entries().get(entry).body();
    }

    private static RecordField fieldOf(TsonCompiledSchema compiled, String entry, String field) {
        return bodyOf(compiled, entry).fields().stream()
                .filter(f -> f.name().equals(field)).findFirst().orElseThrow();
    }

    private static final String FAMILY = """
              dog_type => { breed: text }
              pet      => <T, V> { type: text = T  value: V }
              dogpet   => pet<"dog", dog_type>
            """;

    /**
     * <b>The held body names the selector, because the template's field is the base's.</b> It is REQUIRED with
     * the parameter standing in {@code value} (§5.7 fixes it downstream), so the statement belongs there --
     * and without it a template family could never be sealed, the fact having been dropped between the
     * declaration and anything able to read it.
     */
    @Test
    void theHeldBodyNamesTheSelector() {
        TsonCompiledSchema compiled = compile(FAMILY);

        TemplateBody held = (TemplateBody) compiled.schema().entries().get("pet").body();

        assertEquals(List.of("type"), held.discriminators(),
                () -> "the field the author marked survives into the held body: " + held.template());
    }

    /**
     * <b>And the instantiation names none, because it has pinned the selector.</b> Which field a family
     * dispatches on is the base's statement; a member restates the selector to pin it and carries the value
     * instead, so fixation is where the statement stops (§5.2). A member naming one would fail the rule its
     * own closing created -- {@code abstract} does not travel either.
     */
    @Test
    void theClosedMemberCarriesThePinAndNamesNoSelector() {
        TsonCompiledSchema compiled = compile(FAMILY);

        String instantiation = target(compiled, "dogpet");
        RecordField selector = fieldOf(compiled, instantiation, "type");

        assertEquals("dog", selector.value().orElseThrow().text(), "the argument's pin arrives");
        assertTrue(bodyOf(compiled, instantiation).discriminators().isEmpty(),
                () -> "and the statement stays with the base rather than being copied onto a member: "
                        + bodyOf(compiled, instantiation));
    }
}
