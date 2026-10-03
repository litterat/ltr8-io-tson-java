package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.RecordBody;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A field group inside a template body (§5.10, §5.11). The body is held as text until an application
 * materialises it, so the group travels through the held wire form and back into {@code field_group} -- options,
 * optional members and state intact.
 */
class FieldGroupInTemplateTest {

    private static final String ID = "https://example.test/grouped-template.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/grouped-template.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              fragment => <T> { ( include: T | name?: T  type?: T )? }
              contact => <T> { ( email: T | phone: T )+ }
              text_fragment => fragment<text>
              text_contact => contact<text>
            }
            """;

    private static TsonCompiledSchema compiled() {
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        return TsonCompiledSchemaRegistry.tree(
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source)).get(ID);
    }

    private static List<FieldGroup> groupsOf(TsonCompiledSchema schema, String entry) {
        return assertInstanceOf(RecordBody.class, schema.schema().entries().get(entry).body()).groups();
    }

    @Test
    void anApplicationKeepsTheGroupsOptionsAndMarks() {
        TsonCompiledSchema schema = compiled();
        assertEquals(List.of(new FieldGroup(List.of(List.of("include"), List.of("name", "type")),
                List.of("name", "type"), ElementState.OPTIONAL)), groupsOf(schema, "text_fragment"));
        assertEquals(List.of(new FieldGroup(List.of(List.of("email", "phone")), List.of("email", "phone"),
                ElementState.REQUIRED)), groupsOf(schema, "text_contact"));
    }
}
