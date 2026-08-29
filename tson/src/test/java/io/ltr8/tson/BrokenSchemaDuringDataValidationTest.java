package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validating *data* whose `!!schema` names a schema that doesn't resolve. The read path reaches the schema
 * through the compiled-schema registry, which used to resolve fail-fast -- so the whole broken schema was
 * flattened into one `SCHEMA_ERROR` carrying the *data* file's position and nothing about which declaration
 * was at fault, however many problems the schema actually had.
 *
 * <p>The point of these tests is parity: `tson validate` and `tson compile` should give the same account of
 * the same broken schema, since a schema is equally broken either way and an author fixes it from the same
 * information.
 */
class BrokenSchemaDuringDataValidationTest {

    private static final String ID = "https://example.test/broken-during-read.tn";

    /** Two independent faults, neither a consequence of the other, plus the type the data actually uses. */
    private static final String BROKEN_SCHEMA = """
            !!id:"https://example.test/broken-during-read.tn"
            !!meta:"https://tson.io/2026/34/m/meta.tn"
            !!import:"https://tson.io/2026/34/m/core.tn"
            {
              widens => !uint8 ^ { min: -10  max: 300 }
              point => { x: int32  y: int32 }
              declared_twice => { value: int32  value: int32 }
            }
            """;

    private static final String DATA = """
            !!schema:"https://example.test/broken-during-read.tn"
            !point { x: 3  y: 4 }""";

    private static Tson tson() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(ID)) {
                return BROKEN_SCHEMA;
            }
            throw new IllegalStateException("no schema for " + uri);
        };
        return Tson.builder().schemaSource(source).build();
    }

    /**
     * A schema that doesn't even *parse* reports the same way, and as many times: the read path parses the
     * named schema with the same recovering parse {@code validateSchema} uses, so the account an author gets
     * from {@code tson validate} still matches the one {@code tson compile} gives.
     */
    @Test
    void aSchemaThatDoesNotParseIsReportedPerDeclarationToo() {
        String unparseable = """
                !!id:"https://example.test/broken-during-read.tn"
                !!meta:"https://tson.io/2026/34/m/meta.tn"
                !!import:"https://tson.io/2026/34/m/core.tn"
                {
                  first => { x: }
                  point => { x: int32  y: int32 }
                  second => { q: !int32 ^ { min: 1 } }
                }
                """;
        Tson tson = Tson.builder().schemaSource(uri -> unparseable).build();

        List<Diagnostic> problems = tson.validate(DATA);

        assertEquals(List.of("/first", "/second"),
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).toList(),
                () -> "expected both syntax errors, got " + problems);
        assertEquals(Optional.empty(), problems.get(0).path(), "a schema problem has no data location");
    }

    @Test
    void everyProblemWithTheSchemaIsReported() {
        List<Diagnostic> problems = tson().validate(DATA);

        assertEquals(List.of("/declared_twice", "/widens"),
                problems.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList(),
                () -> "expected both schema faults, got " + problems);
    }

    /** Each keeps the declaration it belongs to and where that declaration is *in the schema*. */
    @Test
    void eachCarriesItsSchemaIdentityAndPosition() {
        for (Diagnostic problem : tson().validate(DATA)) {
            assertEquals("example.test/broken-during-read.tn", problem.schemaId());
            assertTrue(problem.schemaPosition().isPresent(),
                    () -> "no schema position on " + problem.schemaPointer().orElseThrow());
        }
    }

    /**
     * The misattribution this closes: the old path reported through the read context, which stamped the
     * *data* cursor's position (1:1:0) onto a problem that is in the schema and has no data location at all.
     */
    @Test
    void noDataPositionIsStampedOnAProblemThatIsInTheSchema() {
        for (Diagnostic problem : tson().validate(DATA)) {
            assertEquals(Optional.empty(), problem.path());
            assertTrue(problem.dataPosition().isEmpty(),
                    () -> "a schema problem was given a data position: " + problem.dataPosition());
        }
    }

    /** Parity: the same schema gives the same diagnostics whether reached through validate or validateSchema. */
    @Test
    void validatingDataAndValidatingTheSchemaGiveTheSameAccount() {
        List<Diagnostic> viaData = tson().validate(DATA);
        List<Diagnostic> viaSchema = tson().validateSchema(BROKEN_SCHEMA);

        assertEquals(viaSchema.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList(),
                viaData.stream().map(d -> d.schemaPointer().orElseThrow()).sorted().toList());
        assertEquals(viaSchema.stream().map(Diagnostic::message).sorted().toList(),
                viaData.stream().map(Diagnostic::message).sorted().toList());
    }

    /** A schema that reported is not cached as though it compiled, so a second read reports the same again. */
    @Test
    void aBrokenSchemaIsNotCachedAsCompiled() {
        Tson tson = tson();

        assertEquals(2, tson.validate(DATA).size());
        assertEquals(2, tson.validate(DATA).size(), "second read should report the same, not succeed");
    }

    /** A schema that cannot be reached at all is still one problem -- there is nothing to enumerate. */
    @Test
    void anUnreachableSchemaIsStillASingleDiagnostic() {
        List<Diagnostic> problems = Tson.builder()
                .schemaSource(uri -> {
                    throw new TsonSchemaFetchException(uri, TsonSchemaFetchException.Reason.TRANSPORT,
                            "nothing here", null);
                })
                .build()
                .validate(DATA);

        assertEquals(1, problems.size());
        assertFalse(problems.get(0).message().isEmpty());
    }

    /**
     * <b>A source that fails any other way has a bug, and it surfaces as one.</b> {@code
     * TsonSchemaSource.fetch} names {@link TsonSchemaFetchException} for "cannot supply this", so anything
     * else out of a source is that source malfunctioning -- and reporting it as a diagnostic would tell the
     * caller their document is invalid on the strength of someone else's crash. {@code Tson.validate}'s
     * promise is that a bad *document* never throws; a bad *source* is not a document.
     */
    @Test
    void aSourceFailingAnyOtherWayIsAFaultAndNotAVerdict() {
        IllegalStateException fault = new IllegalStateException("the cache is in an impossible state");
        Tson tson = Tson.builder().schemaSource(uri -> {
            throw fault;
        }).build();

        assertSame(fault, assertThrows(IllegalStateException.class, () -> tson.validate(DATA)));
    }
}
