package io.ltr8.tson;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.SchemaFetchException;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * meta.tn's {@code declared_type} constructor read end to end through core's {@code declared}: the value names
 * its own type, by its tag, among the types the governing schema declares or imports, and is read in full
 * against it ([TSON-SCHEMA] §7.8).
 */
class DeclaredReadTest {

    private static final String HOST = "https://example.test/declared-host.tn";
    private static final String OTHER = "https://example.test/declared-other.tn";

    private static final Map<String, String> SCHEMAS = Map.of(
            HOST, """
                    !!id:"https://example.test/declared-host.tn"
                    !!meta:"https://tson.io/2026/39/m/meta.tn"
                    !!import:"https://tson.io/2026/39/m/core.tn"
                    {
                      note     => { body: text }
                      memo     => { body: text  urgent: boolean }
                      envelope => { local: declared }
                      inbox    => { items: [declared] }
                      closed   => { n: int32 }
                    }
                    """,
            OTHER, """
                    !!id:"https://example.test/declared-other.tn"
                    !!meta:"https://tson.io/2026/39/m/meta.tn"
                    !!import:"https://tson.io/2026/39/m/core.tn"
                    {
                      claim => { id: text  amount: int32 }
                    }
                    """);

    private static final SchemaSource SOURCE = uri -> {
        for (Map.Entry<String, String> document : SCHEMAS.entrySet()) {
            if (CanonicalIdentity.sameIdentity(uri, document.getKey())) {
                return document.getValue();
            }
        }
        throw new SchemaFetchException(uri, SchemaFetchException.Reason.NOT_FOUND, "not one of these", null);
    };

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SOURCE)));
    }

    private static TsonValue read(String data) {
        return tson().treeReader().withSchema(HOST).read("!!schema:\"" + HOST + "\"\n" + data);
    }

    private static List<Diagnostic> problems(String data) {
        return tson().validate("!!schema:\"" + HOST + "\"\n" + data);
    }

    /**
     * The value chooses the type and the name resolves in the governing namespace, exactly as any other type-ref
     * in this schema does -- {@code note} and {@code memo} both stand where one field is declared.
     */
    @Test
    void aDeclaredPositionTakesAnyTypeTheGoverningSchemaDeclares() {
        for (String local : List.of("!note { body: hi }", "!memo { body: hi  urgent: true }")) {
            TsonValue value = read("!envelope { local: " + local + " }");

            assertEquals("hi", value.at("/local/body").asString().orElseThrow(), local);
        }
    }

    /** The named type validates in full: its own constraints are the ones that judge the value. */
    @Test
    void theNamedTypeValidatesTheValueInFull() {
        List<Diagnostic> problems = problems("!envelope { local: !memo { body: hi } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.FIELD_REQUIRED, problems.getFirst().code());
        assertEquals(Optional.of("/local/urgent"), problems.getFirst().path());
    }

    /** And nothing else: a name the governing namespace does not hold is an unresolved reference. */
    @Test
    void aDeclaredPositionRefusesANameTheGoverningSchemaDoesNotHold() {
        List<Diagnostic> problems = problems("!envelope { local: !claim { id: a  amount: 1 } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, problems.getFirst().code());
        assertEquals(Optional.of("/local"), problems.getFirst().path());
    }

    /**
     * A declared position is open, so there is nothing to infer a type from and a value naming none is a
     * validation error rather than a vocabulary-only read: nothing failed to resolve.
     */
    @Test
    void aDeclaredValueNamingNoTypeIsAnError() {
        List<Diagnostic> problems = problems("!envelope { local: { body: b } }");

        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
        assertEquals(Optional.of("/local"), problems.getFirst().path());
    }

    /** Each element of {@code [declared]} names its own type. */
    @Test
    void anArrayOfDeclaredTakesATypePerElement() {
        TsonValue value = read("!inbox { items: [ !note { body: a }, !memo { body: b  urgent: false } ] }");

        assertEquals("a", value.at("/items/0/body").asString().orElseThrow());
        assertEquals(false, value.at("/items/1/urgent").as(Boolean.class).orElseThrow());
    }

    /**
     * A directive stands only in the header ([TSON-DATA] §3.3), so a {@code !!schema} prefixing a value is a
     * parse error wherever it stands -- at a declared position as at any other.
     */
    @Test
    void aSchemaDirectiveAtAValuePositionIsAParseError() {
        for (String data : List.of(
                "!envelope { local: !!schema:\"" + OTHER + "\" !claim { id: a  amount: 1 } }",
                "!closed { n: !!schema:\"" + OTHER + "\" 1 }")) {
            List<Diagnostic> problems = problems(data);

            assertEquals(1, problems.size(), problems::toString);
            // The base-syntax code: the document does not parse, so nothing past the directive is judged.
            assertEquals(Diagnostic.Code.VALIDATION_ERROR, problems.getFirst().code());
            assertTrue(problems.getFirst().message().contains("stands only in the document header"),
                    problems::toString);
        }
    }

    /** The tree keeps each value's tag, so a read round-trips through the writer. */
    @Test
    void aDeclaredValueIsWrittenBackWithItsTag() {
        String written = new TsonTreeWriter().toTson(read("!envelope { local: !memo { body: b  urgent: true } }"));

        assertTrue(written.contains("!memo"), written);
        assertEquals(List.of(), tson().validate("!!schema:\"" + HOST + "\"\n" + written), written);
    }
}
