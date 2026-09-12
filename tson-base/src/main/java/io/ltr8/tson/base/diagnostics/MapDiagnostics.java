package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;

import java.math.BigInteger;

/**
 * What a map's rules say when a document breaks one -- stated once, for every encoding. See
 * {@link RecordDiagnostics} for why one place, and why the prose is the schema's vernacular.
 *
 * <p><b>A map has keys and values, and the vernacular keeps them apart from a record's fields</b>: a key is
 * data ([TSON-DATA] §2.6) where a field name is a name, which is why the two families state their rules
 * separately even where the shapes rhyme. The wire form a key takes is each encoding's own -- a member name,
 * or an element of a pair -- and none of that reaches the prose.
 *
 * @param typeName the map type being read, as the author wrote it
 */
public record MapDiagnostics(String typeName) {

    /** The value is not a map at all -- {@code found} is the encoding's description of what was there. */
    public Refusal notAMap(String found) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "expected a map for '%s', found %s".formatted(typeName, found), "a map", found);
    }

    /**
     * [TSON-DATA] §2.9: a key must not be absent. The one absence rule that holds at every state, there being
     * no facet that admits an absent key and no reading under which one would mean anything.
     *
     * <p>"The absent sentinel" is §2.9's own noun for the concept and so belongs in the prose; what stays out
     * is the <em>spelling</em>, which is each encoding's and rides in {@code actual}.
     */
    public Refusal absentKey(String spelling) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "'%s': the absent sentinel must not appear as a map key (§2.9)".formatted(typeName),
                "a real map key, never the absent sentinel", spelling);
    }

    /** [TSON-DATA] §2.6: a map states each key at most once, and the repeat states an entry for nothing. */
    public Refusal duplicateKey(String key) {
        return new Refusal(Diagnostic.Code.DUPLICATE_MAP_KEY,
                "duplicate key '%s' in '%s' -- a map states each key at most once (§2.6), and the repeat states "
                        .formatted(key, typeName) + "an entry for nothing",
                "each key stated once", "'" + key + "' stated again");
    }

    /**
     * Absence at an entry value the schema does not admit one at -- {@code {K => V}} rather than
     * {@code {K => V?}} ([TSON-SCHEMA] §7.6). The entry is present either way and counts toward the bounds.
     */
    public Refusal absentEntryValue(String key, String spelling) {
        return new Refusal(Diagnostic.Code.FIELD_REQUIRED,
                "'%s' entry '%s' is absent, but values are required".formatted(typeName, key),
                "a value", spelling);
    }

    /** §5.3's {@code min_items}, counted over entries -- an entry with an absent value is an entry. */
    public Refusal tooFewEntries(BigInteger min, int size) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "'%s' has %d entries, fewer than the minimum %s".formatted(typeName, size, min),
                ">= " + min, String.valueOf(size));
    }

    /** §5.3's {@code max_items}, on the same terms. */
    public Refusal tooManyEntries(BigInteger max, int size) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "'%s' has %d entries, more than the maximum %s".formatted(typeName, size, max),
                "<= " + max, String.valueOf(size));
    }
}
