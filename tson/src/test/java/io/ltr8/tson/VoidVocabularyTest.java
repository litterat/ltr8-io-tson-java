package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kernel's two kinds of nothing (SPEC-FEEDBACK.md #21): {@code voidable} where the void sentinel may stand in a
 * slot -- a container's element, a map's value, a tuple position -- and {@code optional} where a slot may be
 * missing, a field group as a whole among them. Each is written the same way as a construction and as the sugar it
 * lowers from, and the retired {@code state} is refused rather than read under another meaning.
 */
class VoidVocabularyTest {

    private static final String ID = "https://example.test/2026/37/void-vocabulary-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/2026/37/void-vocabulary-1.tn"
            !!meta:"https://tson.io/2026/37/m/meta.tn"
            !!import:"https://tson.io/2026/37/m/core.tn"
            {
              slots   => !array { element_type: int32  voidable: true }
              entries => !map { key_type: text  value_type: int32  voidable: true }
              pair    => !tuple { elements: [{ element_type: int32 } { element_type: text  voidable: true }] }
              one     => { only: voidable_tuple1<int32> }
              contact => !record { fields: [ { name: email  type: text  optional: true }
                                             { name: phone  type: text  optional: true } ]
                                   groups: [ { members: [[email] [phone]]  optional: true } ] }
            }
            """;

    private static Tson tson(String schema) {
        SchemaSource source = uri -> {
            if (uri.startsWith(ID)) {
                return schema;
            }
            throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND, "only " + ID, null);
        };
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
    }

    private static List<Diagnostic.Code> codes(String body) {
        return tson(SCHEMA).validate("!!schema:\"" + ID + "\"\n" + body).stream().map(Diagnostic::code).toList();
    }

    @Test
    void theConstructionsLoad() {
        assertEquals(List.of(), tson(SCHEMA).validateSchema(SCHEMA));
    }

    /** A voidable slot admits {@code _} and is never left out: the void element still counts. */
    @Test
    void aVoidableSlotAdmitsTheVoidSentinel() {
        assertEquals(List.of(), codes("!slots [1 _ 3]"));
        assertEquals(List.of(), codes("!entries { a => _ }"));
        assertEquals(List.of(), codes("!pair [1 _]"));
        assertEquals(List.of(), codes("!one { only: [_] }"));
        assertEquals(List.of(Diagnostic.Code.WRONG_ARITY), codes("!pair [1]"));
    }

    /** An optional group may be missing as a whole, and still admits at most one option. */
    @Test
    void anOptionalGroupMayBeMissing() {
        assertEquals(List.of(), codes("!contact {}"));
        assertEquals(List.of(), codes("!contact { email: a }"));
        assertEquals(1, codes("!contact { email: a  phone: b }").size());
    }

    /** The retired {@code state} is no field of the vocabulary, so a stale construction is refused, not reread. */
    @Test
    void theRetiredStateIsRefused() {
        String stale = SCHEMA.replace("!array { element_type: int32  voidable: true }",
                "!array { element_type: int32  state: OPTIONAL }");
        List<Diagnostic> refused = tson(stale).validateSchema(stale);
        assertEquals(1, refused.size(), refused.toString());
        assertTrue(refused.getFirst().message().contains("state"), refused.toString());
    }
}
