package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonReadException;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidReaderTest {

    private static final VoidReader READER = new VoidReader(Optional.empty());

    @Test
    void acceptsTheAbsentSentinelAndReadsAsNull() {
        assertNull(READER.read(TsonReadContext.document("_")));
    }

    @Test
    void rejectsAnOrdinaryToken() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> READER
                .read(TsonReadContext.document("hello")));
        assertTrue(thrown.getMessage().contains("void"));
    }
}
