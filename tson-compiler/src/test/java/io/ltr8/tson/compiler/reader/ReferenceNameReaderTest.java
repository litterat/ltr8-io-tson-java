package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A reference written by name at a record-typed slot (SPEC-FEEDBACK.md #13): an unquoted token, read as written into
 * a {@link TypeRef}, the one host shape every reference has. The segment grammar is the linker's.
 */
class ReferenceNameReaderTest {

    private static Optional<String> in(String text, TokenForm form) {
        return ReferenceNameReader.nameIn(new TokenEvent(text, form, new Position(1, 1, 0)));
    }

    @Test
    void anUnquotedTokenIsAName() {
        assertEquals(Optional.of("orders.methods.place_order"), in("orders.methods.place_order", TokenForm.UNQUOTED));
        assertEquals(Optional.of("orders.1x"), in("orders.1x", TokenForm.UNQUOTED));
    }

    @Test
    void aQuotedTokenIsText() {
        assertEquals(Optional.empty(), in("orders", TokenForm.SINGLE_LINE_QUOTED));
    }

    @Test
    void theReaderKeepsTheNameAsWrittenInATypeRef() {
        assertEquals(TypeRef.of("orders.methods.place_order"), new ReferenceNameReader("a 'method' value")
                .read(TestDocuments.document("orders.methods.place_order")));
    }

    @Test
    void aBracedValueIsNoName() {
        List<Diagnostic> reported = new ArrayList<>();
        assertNull(new ReferenceNameReader("a 'method' value")
                .read(TestDocuments.document("{ a: 1 }", reported::add)));
        assertEquals(1, reported.size(), reported.toString());
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, reported.getFirst().code());
        assertTrue(reported.getFirst().message().contains("expected the name of a 'method' value, found a record"),
                reported.getFirst().message());
    }
}
