package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.base.unicode.Normalization;
import io.ltr8.tson.schema.meta.IriType;
import org.junit.jupiter.api.Test;
import io.ltr8.net.Iri;
import java.net.URI;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IriParserTest {

    @Test
    void acceptsAnIriBeyondUsAscii() {
        String text = "https://\u4F8B\u3048.jp/\u30D1\u30B9?q=\u00E9#\u00E7";
        Iri value = IriParser.UNCONSTRAINED.read(text);
        assertEquals(text, IriParser.UNCONSTRAINED.write(value));
        assertEquals("\u4F8B\u3048.jp", value.authority().orElseThrow().host().text());
    }

    @Test
    void acceptsAUri() {
        assertEquals(Iri.parse("urn:isbn:0451450523", Iri.Grammar.URI),
                IriParser.UNCONSTRAINED.read("urn:isbn:0451450523"));
    }

    @Test
    void iriReferenceAcceptsARelativeReference() {
        assertEquals("caf\u00E9/men\u00FC", IriParser.REFERENCE.write(IriParser.REFERENCE.read("caf\u00E9/men\u00FC")));
    }

    /** RFC 3987 §2.2: an IRI has a scheme, so a relative reference is outside {@code iri}'s value space. */
    @Test
    void iriRefusesARelativeReference() {
        AtomValidationException refused = assertThrows(AtomValidationException.class,
                () -> IriParser.UNCONSTRAINED.read("caf\u00E9/men\u00FC"));
        assertEquals("an RFC 3987 IRI", refused.expected());
    }

    /** {@code iprivate} is admitted in the query and nowhere else. */
    @Test
    void aPrivateUseCharacterIsAdmittedOnlyInTheQuery() {
        IriParser.UNCONSTRAINED.read("https://example.com/a?k=\uE000");
        AtomParseException path = assertThrows(AtomParseException.class,
                () -> IriParser.UNCONSTRAINED.read("https://example.com/\uE000"));
        assertEquals("an IRI", path.expected());
        assertThrows(AtomParseException.class, () -> IriParser.UNCONSTRAINED.read("https://example.com/a#\uE000"));
    }

    /** A noncharacter is neither {@code ucschar} nor {@code iprivate}. */
    @Test
    void aNoncharacterIsRefused() {
        assertThrows(AtomParseException.class, () -> IriParser.UNCONSTRAINED.read("https://example.com/\uFDD0"));
    }

    @Test
    void theAsciiGrammarStillApplies() {
        assertThrows(AtomParseException.class, () -> IriParser.UNCONSTRAINED.read("https://example.com/a b"));
    }

    /**
     * A {@code ucschar} that {@code java.net.URI} reads as a space is still an IRI. The value is its text as
     * written, so the percent-encoded spelling is another value; a {@code URI} component holds it percent-encoded,
     * which is the one spelling {@code java.net.URI} can hold.
     */
    @Test
    void aSpaceCharacterInUcscharIsAValueAndBindsPercentEncoded() {
        Iri value = IriParser.UNCONSTRAINED.read("https://example.com/a\u3000b");
        assertEquals("https://example.com/a\u3000b", value.text());
        assertNotEquals(value, IriParser.UNCONSTRAINED.read("https://example.com/a%E3%80%80b"));
        assertEquals(URI.create("https://example.com/a%E3%80%80b"),
                IriParser.UNCONSTRAINED.boundTo(URI.class).orElseThrow().read("https://example.com/a\u3000b"));
    }

    /** An IRI's lengths count code points too, as the {@code text_type} facets it composes say. */
    @Test
    void anIrisLengthCountsCodePoints() {
        String iri = "https://a.example/\uD83D\uDE00";
        IriParser upTo19 = new IriParser(new IriType(IriType.SPEC, Optional.empty(), Optional.of(19),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), false, true,
                Normalization.NONE));
        assertEquals(19, iri.codePointCount(0, iri.length()));
        upTo19.read(iri);
        assertThrows(AtomValidationException.class, () -> upTo19.read(iri + "x"));
    }
}
