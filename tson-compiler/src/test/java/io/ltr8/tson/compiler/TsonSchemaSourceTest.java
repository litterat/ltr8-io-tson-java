package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonSchemaValidationException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonSchemaSource}'s two shipped forms, and the trap {@link TsonSchemaSource#ofMap} exists to take
 * out of a caller's way.
 *
 * <p><b>A map is the natural first source and spells a miss the wrong way.</b> {@code schemaSource(map::get)}
 * compiles, serves every identity in the map, and returns {@code null} for everything else -- and the
 * identity comes from the document, so in a server it comes from a request body and any caller can reach the
 * wrong branch. The contract permits one way to say "cannot supply this", and {@code null} is not it; these
 * pin the correct form doing what that call meant.
 */
class TsonSchemaSourceTest {

    private static final String ID = "https://schemas.example.test/order-1.tn";
    private static final String SCHEMA = "!!id:\"" + ID + "\"\n{ order => { sku: text } }\n";

    @Test
    void ofMapServesWhatItWasGiven() {
        assertEquals(SCHEMA, TsonSchemaSource.ofMap(Map.of(ID, SCHEMA)).fetch(ID));
    }

    /**
     * <b>The half a raw map lookup silently gets wrong.</b> A {@code ?sha256=} pin is verification metadata,
     * not identity (§2.2.1), so a document pinning its schema names the same entry -- and a plain {@code
     * map::get} misses it. That failure appears only for documents that pin, which are the ones written by a
     * deployment that cares about integrity, so the naive form works right up until it matters.
     */
    @Test
    void ofMapMatchesByIdentityRatherThanBySpelling() {
        TsonSchemaSource source = TsonSchemaSource.ofMap(Map.of(ID, SCHEMA));

        assertEquals(SCHEMA, source.fetch(ID + "?sha256=" + "a".repeat(64)), "a pinned reference");
        assertEquals(SCHEMA, source.fetch("http://schemas.example.test/order-1.tn"), "the other scheme");
    }

    /**
     * A miss is {@code NOT_FOUND}, not {@code NOT_PERMITTED}: this source had somewhere to look and looked.
     * {@link TsonSchemaSource#registeredOnly} is the other answer, for a loader with nowhere to look at all.
     */
    @Test
    void ofMapReportsAMissAsNotFound() {
        TsonSchemaFetchException thrown = assertThrows(TsonSchemaFetchException.class,
                () -> TsonSchemaSource.ofMap(Map.of(ID, SCHEMA)).fetch("https://elsewhere.test/other-1.tn"));

        assertEquals(TsonSchemaFetchException.Reason.NOT_FOUND, thrown.reason());
        assertEquals("https://elsewhere.test/other-1.tn", thrown.uri());
    }

    @Test
    void registeredOnlyRefusesEverythingAsNotPermitted() {
        assertEquals(TsonSchemaFetchException.Reason.NOT_PERMITTED,
                assertThrows(TsonSchemaFetchException.class,
                        () -> TsonSchemaSource.registeredOnly().fetch(ID)).reason());
    }

    /**
     * A reference that is no identity at all still leaves this source failing the one way the contract
     * permits -- the canonicalisation failure is wrapped, not allowed out as itself.
     */
    @Test
    void ofMapRefusesAnIllegalIdentityWithoutBreakingTheContract() {
        TsonSchemaFetchException thrown = assertThrows(TsonSchemaFetchException.class,
                () -> TsonSchemaSource.ofMap(Map.of(ID, SCHEMA)).fetch("not-a-uri"));

        assertEquals(TsonSchemaFetchException.Reason.NOT_PERMITTED, thrown.reason());
    }

    /** A key that is not a legal identity fails where the map is built, not at the read that needed it. */
    @Test
    void ofMapRefusesAnIllegalKeyAtConstruction() {
        assertThrows(TsonSchemaValidationException.class,
                () -> TsonSchemaSource.ofMap(Map.of("schemas.example.test/no-scheme.tn", SCHEMA)));
    }

    /**
     * Two keys naming one identity are refused rather than collapsed, since which of the two would have won
     * is not something a caller can read off their own map.
     */
    @Test
    void ofMapRefusesTwoSchemasForOneIdentity() {
        Map<String, String> clashing = new HashMap<>();
        clashing.put(ID, SCHEMA);
        clashing.put("http://schemas.example.test/order-1.tn", SCHEMA + "\n");

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> TsonSchemaSource.ofMap(clashing));
        assertTrue(thrown.getMessage().contains("schemas.example.test/order-1.tn"), thrown::getMessage);
    }

    /** The same identity written twice with the same document is not a clash -- there is one answer to give. */
    @Test
    void ofMapAcceptsARepeatedIdentityWithOneDocument() {
        Map<String, String> repeated = new HashMap<>();
        repeated.put(ID, SCHEMA);
        repeated.put("http://schemas.example.test/order-1.tn", SCHEMA);

        assertEquals(SCHEMA, TsonSchemaSource.ofMap(repeated).fetch(ID));
    }

    /** Copied at construction, so a later mutation cannot change what a registry has already read from. */
    @Test
    void ofMapCopiesTheMap() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put(ID, SCHEMA);
        TsonSchemaSource source = TsonSchemaSource.ofMap(mutable);

        mutable.clear();

        assertEquals(SCHEMA, source.fetch(ID));
    }
}
