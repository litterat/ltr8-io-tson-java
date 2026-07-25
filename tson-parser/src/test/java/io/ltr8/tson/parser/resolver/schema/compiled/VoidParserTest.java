package io.ltr8.tson.parser.resolver.schema.compiled;

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

class VoidParserTest {

    @Test
    void acceptsTheAbsentSentinelAndReadsAsNull() {
        DataValue value = new DataValue(List.of(), Optional.empty(), new AbsentValue());

        assertNull(VoidParser.INSTANCE.read(value));
    }

    @Test
    void rejectsAnOrdinaryToken() {
        DataValue value = new DataValue(List.of(), Optional.empty(), new TokenValue("hello", TokenForm.UNQUOTED));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> VoidParser.INSTANCE.read(value));
        assertTrue(thrown.getMessage().contains("void"));
    }

    @Test
    void rejectsAMissingValueOutright() {
        assertThrows(IllegalArgumentException.class, () -> VoidParser.INSTANCE.read(null));
    }
}
