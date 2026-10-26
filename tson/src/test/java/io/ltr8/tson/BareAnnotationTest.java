package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bare annotation is shorthand for {@code @T:_} ([TSON-SCHEMA] §6), so it is read against its type like a
 * written value: a void-typed marker takes the bare form, and any other type refuses it as it refuses {@code _}.
 * Both positions are checked -- the declaration's name, and its definition after {@code =>}.
 */
class BareAnnotationTest {

    private static List<Diagnostic> validate(String declaration) {
        return Tson.standard().validateSchema("""
                !!id:"https://example.test/bare-1.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                  %s
                }
                """.formatted(declaration));
    }

    @Test
    void aVoidTypedMarkerIsWrittenBare() {
        assertEquals(List.of(), validate("@deprecated old => @numeric int32"));
        assertEquals(List.of(), validate("@deprecated:_ old => int32"));
    }

    @Test
    void aBareMarkWhoseTypeIsNotVoidIsRefused() {
        for (String declaration : List.of("@doc old => int32", "old => @title int32")) {
            List<Diagnostic> refused = validate(declaration);
            assertEquals(1, refused.size(), declaration + ": " + refused);
            assertTrue(refused.getFirst().message().contains("is not valid data for the type"),
                    refused.getFirst().toString());
        }
    }
}
