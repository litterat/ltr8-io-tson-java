package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.schema.meta.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a value written by name is (SPEC-FEEDBACK.md #13): an unquoted token whose {@code .}-separated segments are
 * each a [TSON-DATA] §7.7 identifier, and nothing else.
 */
class QualifiedNameReaderTest {

    private static Optional<QualifiedName> unquoted(String text) {
        return QualifiedNameReader.nameIn(new TokenEvent(text, TokenForm.UNQUOTED, new Position(1, 1, 0)));
    }

    @Test
    void identifiersJoinedByDotsAreAName() {
        assertEquals(Optional.of(QualifiedName.of("orders", "methods", "place_order")),
                unquoted("orders.methods.place_order"));
        assertEquals(Optional.of(QualifiedName.of("kebab-name")), unquoted("kebab-name"));
    }

    @Test
    void aSegmentThatIsNoIdentifierIsNoName() {
        assertEquals(Optional.empty(), unquoted("42"));
        assertEquals(Optional.empty(), unquoted("orders.1x"));
        assertEquals(Optional.empty(), unquoted("a..b"));
        assertEquals(Optional.empty(), unquoted("-a"));
    }

    @Test
    void aQuotedTokenIsText() {
        assertEquals(Optional.empty(), QualifiedNameReader.nameIn(
                new TokenEvent("orders", TokenForm.SINGLE_LINE_QUOTED, new Position(1, 1, 0))));
    }

    @Test
    void theReaderKeepsTheNameAsWritten() {
        assertEquals(QualifiedName.of("a", "b"),
                new QualifiedNameReader("a 'method' value").read(TestDocuments.document("a.b")));
    }

    @Test
    void aBracedValueIsNoName() {
        List<Diagnostic> reported = new ArrayList<>();
        assertNull(new QualifiedNameReader("a 'method' value")
                .read(TestDocuments.document("{ a: 1 }", reported::add)));
        assertEquals(1, reported.size(), reported.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, reported.getFirst().code());
        assertTrue(reported.getFirst().message().contains("expected the name of a 'method' value, found a record"),
                reported.getFirst().message());
    }
}
