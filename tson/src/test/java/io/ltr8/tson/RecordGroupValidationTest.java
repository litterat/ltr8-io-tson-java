package io.ltr8.tson;

import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonValueReader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record field group (§5.11) -- {@code ( email: text | phone: text )}, a labelled "one of" --
 * enforces its own presence multiplicity at read time: a bare (REQUIRED) group admits exactly one
 * member, a {@code ?} (OPTIONAL) group at most one. The group's members flatten into ordinary
 * OPTIONAL fields during resolution, so this presence count is the one thing only the reader can
 * check; see {@code RecordAbstractReader.validateGroups}.
 */
class RecordGroupValidationTest {

    /** {@code contact} has a bare (REQUIRED) group: exactly one of email/phone must be present. */
    private static TsonValueReader<?> contactReader() {
        String schema = """
                !!id:"https://example.test/group-1.tn"
                !!meta:"https://tson.io/2026/32/m/meta.tn"
                !!import:"https://tson.io/2026/32/m/core.tn"
                {
                  contact => {
                    name: text
                    ( email: text | phone: text )
                  }
                }""";
        Tson tson = Tson.builder().build();
        return tson.treeRegistry().compile(tson.resolve(schema)).get("contact");
    }

    @Test
    void exactlyOneMemberValidates() {
        contactReader().read("{ name: \"Ada\"  email: \"ada@example.com\" }"); // no exception
    }

    @Test
    void twoPresentMembersAreRejected() {
        TsonReadException e = assertThrows(TsonReadException.class,
                () -> contactReader().read("{ name: \"Ada\"  email: \"ada@example.com\"  phone: \"111\" }"));
        assertTrue(e.getMessage().contains("at most one"), e.getMessage());
    }

    @Test
    void zeroPresentMembersAreRejectedForARequiredGroup() {
        TsonReadException e = assertThrows(TsonReadException.class,
                () -> contactReader().read("{ name: \"Ada\" }"));
        assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
    }
}
