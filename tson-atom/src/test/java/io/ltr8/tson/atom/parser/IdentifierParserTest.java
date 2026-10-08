package io.ltr8.tson.atom.parser;

import io.ltr8.tson.atom.AtomParseException;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.base.unicode.IdentifierProfile.Base;
import io.ltr8.tson.base.unicode.Normalization;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.TextType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code identifier} atom -- what §7.7's profile becomes at a <em>typed position</em>, and the text facets
 * {@code identifier_type} composes, applied inside it.
 *
 * <p>The profile itself is {@code IdentifierProfile}'s and is tested there. What is this atom's own is the
 * verdict: at a position typed {@code identifier}, a name the profile refuses is a parse failure. Every
 * other caller of the profile owes a different answer, which is why the profile reports rather than throws.
 */
class IdentifierParserTest {

    @Test
    void readReturnsANameTheProfileAdmits() {
        assertEquals("order_id", IdentifierParser.IDENTIFIER.read("order_id"));
    }

    @Test
    void readRaisesAParseFailureForOneItDoesNot() {
        AtomParseException refused =
                assertThrows(AtomParseException.class, () -> IdentifierParser.IDENTIFIER.read("2fast"));

        assertEquals("an identifier", refused.expected());
        assertTrue(refused.getMessage().contains("never begins with a digit"), refused.getMessage());
    }

    /** The profile's own message travels, so the position adds a verdict and not a second explanation. */
    @Test
    void theRefusalCarriesTheProfilesOwnMessage() {
        assertEquals(io.ltr8.tson.base.unicode.IdentifierProfile.validate("2fast").orElseThrow(),
                assertThrows(AtomParseException.class, () -> IdentifierParser.IDENTIFIER.read("2fast")).getMessage());
    }

    @Test
    void writeIsTheIdentity() {
        assertEquals("anything", IdentifierParser.IDENTIFIER.write("anything"));
    }

    /** A naming convention is a {@code pattern} facet, asked of a name the grammar already admits. */
    @Test
    void aPatternFacetNarrowsTheNamesAdmitted() {
        IdentifierParser snakeCase = new IdentifierParser(IdentifierType.IDENTIFIER.withTextConstraints(
                new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("[a-z][a-z0-9_]*"),
                        Optional.empty())));

        assertEquals("order_id", snakeCase.read("order_id"));
        assertThrows(AtomTypeException.class, () -> snakeCase.read("orderId"));
    }

    /** The grammar is refused as a grammar even where a facet would also refuse it. */
    @Test
    void theGrammarIsCheckedBeforeTheFacets() {
        IdentifierParser closed = new IdentifierParser(IdentifierType.IDENTIFIER.withTextConstraints(
                new TextType(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(List.of("north", "south")))));

        assertEquals("north", closed.read("north"));
        assertThrows(AtomTypeException.class, () -> closed.read("east"));
        assertEquals("an identifier",
                assertThrows(AtomParseException.class, () -> closed.read("2fast")).expected());
    }

    /** The profile judges the value: a lowercase profile under NFKC_CASEFOLD admits a name cased any way. */
    @Test
    void theProfileJudgesTheNormalizedValue() {
        IdentifierParser header = new IdentifierParser(new IdentifierType(IdentifierType.SPEC, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Base.NONE, Base.NONE,
                Optional.of("abcdefghijklmnopqrstuvwxyz"), Optional.of("abcdefghijklmnopqrstuvwxyz-"),
                Optional.empty(), Optional.empty(), Normalization.NFKC_CASEFOLD));
        assertEquals("content-type", header.read("Content-Type"));
        assertThrows(AtomParseException.class, () -> header.read("content_type"));
    }

    @Test
    void theKernelsIdentifierIsNfcNormalisedRatherThanRefused() {
        assertEquals("caf\u00e9", IdentifierParser.IDENTIFIER.read("cafe\u0301"));
    }
}
