package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code diagnostics.tn} against the code it describes.
 *
 * <p><b>Its {@code diagnostic_code} enum is a hand-written copy of {@link Diagnostic.Code}</b>, and nothing
 * else checks that the copy is current. Add a member to the enum and forget the schema, and {@code --output
 * tson} emits a code its own schema rejects -- caught only if some other fixture happened to use that code,
 * which for a new one it never has. The schema's own {@code @doc} records this having been missed twice
 * already, each time noticed by a version bump rather than by a test.
 *
 * <p><b>The Java enum is the source of truth, and that is why this test asserts against it</b> rather than
 * against another schema. Any consumer rendering diagnostics -- this CLI, an HTTP error body, anything else
 * -- declares the vocabulary again in its own wire schema, and each copy has to be checked against the
 * enum. Two schemas checked against each other would only prove they drifted together.
 */
class DiagnosticsSchemaTest {

    /**
     * Read through the real pipeline rather than by matching text: the members this asserts on are the ones
     * a reader will actually enforce, which is the property that matters, and it is the schema's own
     * resolution that produces them.
     */
    private static List<String> declaredCodes() {
        TypeDefinition entry = DiagnosticsSchema.compiled().schema().entries().get("diagnostic_code");
        return assertInstanceOf(EnumBody.class, entry.body(), "diagnostic_code is an enum").members();
    }

    @Test
    void everyDiagnosticCodeIsDeclaredInTheSchema() {
        List<String> declared = declaredCodes();

        for (Diagnostic.Code code : Diagnostic.Code.values()) {
            assertTrue(declared.contains(code.name()),
                    () -> "Diagnostic.Code." + code + " is missing from diagnostics.tn's diagnostic_code: "
                            + declared + " -- add it there, under a new schema version (§10)");
        }
    }

    @Test
    void theSchemaDeclaresNoCodeTheEnumDoesNotHave() {
        List<String> known = Arrays.stream(Diagnostic.Code.values()).map(Enum::name).toList();

        for (String declared : declaredCodes()) {
            assertTrue(known.contains(declared),
                    () -> "diagnostics.tn declares '" + declared + "', which is not a Diagnostic.Code: "
                            + known + " -- a value no reader can ever produce");
        }
    }

    /**
     * Order is not semantically significant to an {@code !enum} ({@code EnumBody}'s own note), but keeping
     * the two lists in step makes a diff between them readable, which is what anyone adding a member will
     * be looking at.
     */
    @Test
    void theSchemaListsTheCodesInTheEnumsOwnOrder() {
        assertEquals(Arrays.stream(Diagnostic.Code.values()).map(Enum::name).toList(), declaredCodes());
    }
}
