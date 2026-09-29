package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code identifier_type} end to end: a text family whose facets apply inside the naming grammar, so a naming
 * convention is a {@code pattern} and a closed vocabulary of names is {@code members} -- each refused as a
 * facet, where a name outside the grammar is refused as the grammar. Reached both by refining core's
 * {@code identifier} and by constructing the family directly.
 */
class IdentifierFamilyTest {

    private static final String ID = "https://example.test/names-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/names-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              snake_name => !identifier ^ { pattern: "[a-z][a-z0-9_]*" }
              direction => !identifier_type { members: [north south] }
              route => { name: snake_name  heading: direction }
              handlers => { identifier => text }
            }
            """;

    private static Tson tson() {
        SchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(ID)) {
                return SCHEMA;
            }
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND,
                    "this fixture serves only " + ID, null);
        };
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    private static List<Diagnostic> validate(String body) {
        return tson().validate("!!schema:\"" + ID + "\"\n" + body);
    }

    @Test
    void theSchemaItselfLoads() {
        assertEquals(List.of(), tson().validateSchema(SCHEMA));
    }

    @Test
    void aNameInsideTheGrammarAndTheFacetsIsAdmitted() {
        assertEquals(List.of(), validate("!route { name: order_id  heading: north }"));
    }

    @Test
    void aFacetRefusesAWellFormedName() {
        List<Diagnostic> refused = validate("!route { name: orderId  heading: east }");
        assertEquals(2, refused.size(), refused.toString());
        refused.forEach(d -> assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, d.code(), d.toString()));
    }

    /** A name outside §7.7 is refused as the grammar, before any facet is asked. */
    @Test
    void theGrammarRefusesBeforeAFacet() {
        List<Diagnostic> refused = validate("!route { name: \"2fast\"  heading: north }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals("an identifier", refused.getFirst().expected());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, refused.getFirst().code());
    }

    /** Core's {@code identifier} types a map key as a name, so a key outside the grammar is refused. */
    @Test
    void anIdentifierKeyedMapRefusesAKeyOutsideTheGrammar() {
        assertEquals(List.of(), validate("!handlers { create => a  delete-all => b }"));

        List<Diagnostic> refused = validate("!handlers { create => a  \"2fast\" => b }");
        assertEquals(1, refused.size(), refused.toString());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, refused.getFirst().code());
    }
}
