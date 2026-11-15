package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator decides the body ([TSON-SCHEMA] §5.7, §5.8, §12.1): {@code ^} follows {@code !name} and its body is
 * values, and {@code &} follows a bare name and its body is field definitions, the one way to derive a record and so
 * the one way to declare a constructor. A {@code ^} after a bare name is a parse error whatever the name is, and a
 * record before {@code !…^} is refused at resolution, as a tuple or a choice is.
 */
class RefinementTakesValuesTest {

    private static final String LIBRARY_ID = "https://example.test/lib.tn";

    private static final String LIBRARY = """
            !!id:"https://example.test/lib.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              bounded => meta.array & { max_items?: = 9 }
            }
            """;

    private static String schema(String declarations) {
        return """
                !!id:"https://example.test/m.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !!import:"https://example.test/lib.tn"
                !schema {
                %s
                }
                """.formatted(declarations);
    }

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of(LIBRARY_ID, LIBRARY)))));
    }

    private static List<Diagnostic> problems(String declarations) {
        return tson().validateSchema(schema(declarations));
    }

    private static Diagnostic theOneProblem(String declarations) {
        List<Diagnostic> problems = problems(declarations);
        assertEquals(1, problems.size(), problems::toString);
        return problems.getFirst();
    }

    /** Over a record, a constructor, an imported constructor, an application, and in a parameterised declaration. */
    @ParameterizedTest
    @ValueSource(strings = {
            "  person => { name: text }\n  vip => person ^ {}",
            "  minlist => meta.array ^ { min_items?: = 1 }",
            "  code_type => unicode.text_type ^ { max_length?: = 4 }",
            "  few => bounded ^ { min_items?: = 1 }",
            "  box => <T> { v?: T }\n  fixed => box<text> ^ { v?: = \"a\" }",
            "  box => <T> { v?: T }\n  narrowed => <T> box<T> ^ {}"})
    void aCaretAfterABareNameIsAParseError(String declarations) {
        Diagnostic refusal = theOneProblem(declarations);
        assertTrue(refusal.message().contains("'^' follows '!name' and takes values (§5.5, §5.7)"), refusal::message);
        assertTrue(refusal.message().contains("a record's fields are restated in a composition"), refusal::message);
    }

    /** A record states no rule for a value body, so it takes no {@code ^}; the refusal names the composition. */
    @Test
    void aRecordTakesNoValueRefinement() {
        Diagnostic refusal = theOneProblem("  person => { name: text }\n  vip => !person ^ { name: \"x\" }");
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, refusal.code(), refusal::toString);
        assertEquals(Optional.of("/vip"), refusal.schemaPointer(), refusal::toString);
        assertTrue(refusal.message().contains("a record, which takes no '^'"), refusal::message);
        assertTrue(refusal.message().contains("'person & { ... }' (§5.8)"), refusal::message);
    }

    /** {@code person & {}} is the nominal subtype: IS-A {@code person}, its whole field set, and another entry. */
    @Test
    void anEmptyCompositionIsTheNominalSubtype() {
        var entries = tson().resolve(schema("  person => { name: text }\n  vip => person & {}")).schema().entries();
        RecordBody vip = assertInstanceOf(RecordBody.class, entries.get("vip"));

        assertEquals(List.of("person"), TypeChain.supertypesOf(vip));
        assertEquals(((RecordBody) entries.get("person")).fields().keySet(), vip.fields().keySet());
    }

    /** A body entry with a type that names no inherited field adds the field: the composition declares it. */
    @Test
    void aTypedEntryNamingNoInheritedFieldAddsIt() {
        var entries = tson().resolve(schema("  person => { age: int32 }\n  adult => person & { agee: int32 }"))
                .schema().entries();

        assertEquals(List.of("age", "agee"),
                List.copyOf(assertInstanceOf(RecordBody.class, entries.get("adult")).fields().keySet()));
    }

    /** A modifier-only entry is a tightening, so one naming no inherited field has nothing to tighten. */
    @Test
    void aModifierOnlyEntryNamingNoInheritedFieldIsRefused() {
        Diagnostic refusal = theOneProblem("  person => { age: int32 }\n  adult => person & { agee: = 18 }");
        assertTrue(refusal.message().contains("names no inherited field"), refusal::message);
    }

    /**
     * What a constructor's partial filling and a new constructor are written as: its construction left open, a type
     * it builds refined where it has no required field, and a composition.
     */
    @Test
    void theConstructorFormsLoad() {
        assertEquals(List.of(), problems("  minlist => !meta.array ^ { min_items: 1 }\n  tags => minlist<text>"));
        assertEquals(List.of(), problems("  t => !unicode.text_type {}\n  code => !t ^ { max_length: 4 }"));
        assertEquals(List.of(), problems("  minlist => meta.array & { min_items?: = 1 }"));
        assertEquals(List.of(), problems("  few => !bounded ^ { min_items: 1 }"));
    }
}
