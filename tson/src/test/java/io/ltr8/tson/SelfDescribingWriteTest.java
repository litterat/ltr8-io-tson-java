package io.ltr8.tson;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.TsonWriteException;
import io.ltr8.tson.tree.TsonAtom;
import io.ltr8.tson.tree.TsonRecord;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writing a document that says what governs it -- the write-direction half of {@code !!schema}, and the
 * whole point of it: a document this library reads, it can now reproduce in the form it arrived in.
 *
 * <p><b>What this closes.</b> The readers have always honoured {@code !!schema}; the writers could not emit
 * one, so a response body was never self-describing and a receiver had to be told out of band what governed
 * it. These fixtures are that round trip: write, then validate the result through a {@link Tson} that is
 * given nothing but the bytes.
 */
class SelfDescribingWriteTest {

    private static final String ID = "https://example.test/point-1.tn";
    private static final String SCHEMA = """
            !!id:"https://example.test/point-1.tn"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            { point => { x: int32  y: int32 } }
            """;

    public record Point(long x, long y) {
    }

    private static Tson tson() {
        TsonSchemaSource source = uri -> {
            if (uri.startsWith(ID)) {
                return SCHEMA;
            }
            throw new IllegalStateException("no schema for " + uri);
        };
        return Tson.builder().schemaSource(source).build();
    }

    /** The default is untouched: a bare value, which is what every existing consumer of this output expects. */
    @Test
    void aPlainWriterStillWritesABareValue() {
        assertEquals("{ x: 3 y: 4 }", tson().objectWriter().toTson(new Point(3, 4)));
    }

    @Test
    void aDescribingObjectWriterEmitsTheSchemaAndTheRootType() {
        assertEquals("!!schema:\"" + ID + "\"\n!point { x: 3 y: 4 }",
                tson().objectWriter().describing(ID, "point").toTson(new Point(3, 4)));
    }

    /**
     * The claim, end to end: what the writer produced validates against nothing but itself. {@link
     * Tson#validate} is handed the bytes and works out the schema and the type from the document.
     */
    @Test
    void whatADescribingObjectWriterWritesValidatesWithNothingPassedAlongside() {
        String document = tson().objectWriter().describing(ID, "point").toTson(new Point(3, 4));

        List<Diagnostic> problems = tson().validate(document);

        assertEquals(List.of(), problems, document);
    }

    /** And the same for a tree, which is the shape a read produced in the first place. */
    @Test
    void aTreeReadFromASelfDescribingDocumentWritesBackAsOne() {
        Tson tson = tson();
        String original = """
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""";

        TsonValue tree = tson.treeReader().read(original);
        String written = tson.treeWriter().describing(ID).toTson(tree);

        assertTrue(written.startsWith("!!schema:\"" + ID + "\"\n!point "), written);
        assertEquals(List.of(), tson.validate(written), written);
    }

    /** {@code !!id} is the document's own identity, and §2.2 makes it the first line when both are written. */
    @Test
    void theIdDirectiveComesFirst() {
        String document = tson().objectWriter()
                .identifiedBy("https://example.test/doc-1.tn")
                .describing(ID, "point")
                .toTson(new Point(3, 4));

        assertEquals(List.of("!!id:\"https://example.test/doc-1.tn\"", "!!schema:\"" + ID + "\""),
                List.of(document.split("\n")[0], document.split("\n")[1]));
    }

    /** Derivation, not mutation: the writer it was derived from still writes what it always did. */
    @Test
    void derivingLeavesTheOriginalWriterAlone() {
        var plain = tson().objectWriter();
        var describing = plain.describing(ID, "point");

        assertEquals("{ x: 3 y: 4 }", plain.toTson(new Point(3, 4)));
        assertFalse(describing.toTson(new Point(3, 4)).equals(plain.toTson(new Point(3, 4))));
    }

    /**
     * Half self-describing is not self-describing, and the failure lands at the write rather than at
     * whoever reads it: a tree whose root names no type would declare a schema and then give a reader no
     * type to select with it.
     */
    @Test
    void aTreeWhoseRootNamesNoTypeCannotBeWrittenAsSelfDescribing() {
        Map<String, TsonValue> fields = new LinkedHashMap<>();
        fields.put("x", TsonAtom.of(BigInteger.valueOf(3)));
        TsonRecord untagged = new TsonRecord(fields, Optional.empty(), List.of());

        TsonWriteException thrown = assertThrows(TsonWriteException.class,
                () -> tson().treeWriter().describing(ID).toTson(untagged));

        assertTrue(thrown.getMessage().contains("needs a root type-ref"), thrown.getMessage());
    }
}
