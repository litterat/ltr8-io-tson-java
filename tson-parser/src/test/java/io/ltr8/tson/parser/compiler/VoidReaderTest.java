package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoidReaderTest {

    @Test
    void acceptsTheAbsentSentinelAndReadsAsNull() {
        DataValue value = new DataValue(List.of(), Optional.empty(), new AbsentValue());

        assertNull(VoidReader.INSTANCE.read(value));
    }

    @Test
    void rejectsAnOrdinaryToken() {
        DataValue value = new DataValue(List.of(), Optional.empty(), new TokenValue("hello", TokenForm.UNQUOTED));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> VoidReader.INSTANCE.read(value));
        assertTrue(thrown.getMessage().contains("void"));
    }

    @Test
    void rejectsAMissingValueOutright() {
        assertThrows(IllegalArgumentException.class, () -> VoidReader.INSTANCE.read(null));
    }
}
