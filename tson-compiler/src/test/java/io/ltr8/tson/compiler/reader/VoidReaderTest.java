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
            new VoidReader(SchemaLocation.of("example.test/s.tn", "void", Optional.empty()));

    @Test
    void acceptsTheAbsentSentinelAndReadsAsNull() {
        assertNull(READER.read(TestDocuments.document("_")));
    }

    /**
     * Absence has one spelling, so {@code void} admits one token: {@code _}. The unquoted {@code null} is a
     * string here as it is everywhere else, and fails this reader the way {@code frobnicate} does -- there is
     * no second spelling for the contract to concede to.
     */
    @Test
    void rejectsTheNullToken() {
        assertThrows(TsonReadException.class, () -> READER.read(TestDocuments.document("null")));
        assertThrows(TsonReadException.class, () -> READER.read(TestDocuments.document("\"null\"")));
    }

    @Test
    void rejectsAnOrdinaryToken() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> READER
                .read(TestDocuments.document("hello")));
        assertTrue(thrown.getMessage().contains("void"));
    }
}
