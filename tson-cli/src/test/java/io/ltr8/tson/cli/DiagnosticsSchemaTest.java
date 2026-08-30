package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
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
 * <p><b>It declares two hand-written copies of Java enums</b> -- {@code diagnostic_code} of {@link
 * Diagnostic.Code}, {@code fetch_reason} of {@link TsonSchemaFetchException.Reason} -- and nothing else
 * checks that either copy is current. Add a member to one of the enums and forget the schema, and {@code
 * --output tson} emits a value its own schema rejects, caught only if some other fixture happened to use
 * that value, which for a new one it never has. The schema's own {@code @doc} records this having been
 * missed twice already, each time noticed by a version bump rather than by a test.
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
    private static List<String> declared(String entryName) {
        TypeDefinition entry = DiagnosticsSchema.compiled().schema().entries().get(entryName);
        return assertInstanceOf(EnumBody.class, entry.body(), entryName + " is an enum").members();
    }

    /** Both copies, against the enum each one copies -- the same three checks, so neither drifts alone. */
    private static void assertMirrors(String entryName, Class<? extends Enum<?>> source) {
        List<String> members = Arrays.stream(source.getEnumConstants()).map(Enum::name).toList();
        List<String> declared = declared(entryName);

        for (String member : members) {
            assertTrue(declared.contains(member),
                    () -> source.getSimpleName() + "." + member + " is missing from diagnostics.tn's "
                            + entryName + ": " + declared + " -- add it there");
        }
        for (String member : declared) {
            assertTrue(members.contains(member),
                    () -> "diagnostics.tn's " + entryName + " declares '" + member + "', which is not a "
                            + source.getSimpleName() + ": " + members + " -- a value no reader can ever produce");
        }
        // Order is not semantically significant to an !enum (EnumBody's own note), but keeping the two lists
        // in step makes a diff between them readable, which is what anyone adding a member will be looking at.
        assertEquals(members, declared, entryName + " lists its members in a different order from "
                + source.getSimpleName());
    }

    @Test
    void diagnosticCodeMirrorsTheJavaEnum() {
        assertMirrors("diagnostic_code", Diagnostic.Code.class);
    }

    /**
     * The second copy, and the one with no other check behind it: {@code fetch_reason} exists so a consumer
     * of {@code --output json|tson} can tell a reference this deployment refuses from a host that did not
     * answer, both of which arrive under the one {@code SCHEMA_UNAVAILABLE} code.
     */
    @Test
    void fetchReasonMirrorsTheJavaEnum() {
        assertMirrors("fetch_reason", TsonSchemaFetchException.Reason.class);
    }
}
