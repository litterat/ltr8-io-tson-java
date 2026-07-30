package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.ast.AbsentValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidReaderTest {

    private static final VoidReader READER = new VoidReader(Optional.empty());

    @Test
    void acceptsTheAbsentSentinelAndReadsAsNull() {
        DataValue value = new DataValue(List.of(), Optional.empty(), new AbsentValue());

        assertNull(READER.read(value));
    }

    @Test
    void rejectsAnOrdinaryToken() {
        DataValue value = new DataValue(List.of(), Optional.empty(), new TokenValue("hello", TokenForm.UNQUOTED));

        TsonReadException thrown = assertThrows(TsonReadException.class, () -> READER.read(value));
        assertTrue(thrown.getMessage().contains("void"));
    }

    @Test
    void rejectsAMissingValueOutright() {
        assertThrows(TsonReadException.class, () -> READER.read(null));
    }
}
