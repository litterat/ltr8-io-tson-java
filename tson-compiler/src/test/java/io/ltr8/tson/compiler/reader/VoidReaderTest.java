package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonReadException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidReaderTest {

    private static final VoidReader READER =
            new VoidReader(new SchemaLocation("example.test/s.tn", "void", Optional.empty()));

    @Test
    void acceptsTheAbsentSentinelAndReadsAsNull() {
        assertNull(READER.read(TestDocuments.document("_")));
    }

    /**
     * [TSON-SCHEMA] §7.3's void-position concession: the unquoted token {@code null} is accepted here as an
     * equivalent spelling of {@code _}. Local to {@code void} -- §7.3 is explicit that it "does not change
     * {@code null}'s meaning elsewhere", which is why the acceptance lives in this reader and not in the
     * token stream.
     */
    @Test
    void acceptsNullAsAnEquivalentSpellingOfTheSentinel() {
        assertNull(READER.read(TestDocuments.document("null")));
    }

    /** The concession is about the spelling of absence, not about text that reads that way (§4.4). */
    @Test
    void rejectsAQuotedNull() {
        assertThrows(TsonReadException.class, () -> READER.read(TestDocuments.document("\"null\"")));
    }

    @Test
    void rejectsAnOrdinaryToken() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> READER
                .read(TestDocuments.document("hello")));
        assertTrue(thrown.getMessage().contains("void"));
    }
}
