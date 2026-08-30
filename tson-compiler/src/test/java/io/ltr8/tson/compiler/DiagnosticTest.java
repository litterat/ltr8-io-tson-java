package io.ltr8.tson.compiler;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Diagnostic}'s two spellings of absence, and the accessors that let a renderer stop knowing which
 * component uses which. {@code ""} means "nothing to say" for {@code schemaId}/{@code expected}/{@code
 * actual}; for the two RFC 6901 pointers it means the <em>root</em>, so those stay {@link Optional} at the
 * source and are left alone here.
 */
class DiagnosticTest {

    private static Diagnostic with(String schemaId, String expected, String actual) {
        return new Diagnostic(Optional.of("/a"), Optional.of(""), schemaId, Diagnostic.Code.TYPE_MISMATCH,
                "message", expected, actual, Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Test
    void anEmptyComponentReadsAsAbsent() {
        Diagnostic diagnostic = with("", "", "");

        assertEquals(Optional.empty(), diagnostic.schemaIdIfKnown());
        assertEquals(Optional.empty(), diagnostic.expectedIfStated());
        assertEquals(Optional.empty(), diagnostic.actualIfStated());
    }

    @Test
    void aStatedComponentReadsAsItself() {
        Diagnostic diagnostic = with("https://example.test/s.tn", "<= 100", "101");

        assertEquals(Optional.of("https://example.test/s.tn"), diagnostic.schemaIdIfKnown());
        assertEquals(Optional.of("<= 100"), diagnostic.expectedIfStated());
        assertEquals(Optional.of("101"), diagnostic.actualIfStated());
    }

    /**
     * The distinction the accessors exist to preserve: a present {@code ""} pointer is the root, which a
     * document-level schema problem genuinely carries, so it must not be narrowed the way the three
     * components above are. Nothing on this type offers to do that, and this fixture is why.
     */
    @Test
    void anEmptyPointerIsTheRootAndStaysPresent() {
        assertEquals(Optional.of(""), with("", "", "").schemaPointer());
    }
}
