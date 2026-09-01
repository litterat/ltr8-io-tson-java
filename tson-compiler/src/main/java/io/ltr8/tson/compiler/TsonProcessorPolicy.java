package io.ltr8.tson.compiler;

import java.util.Objects;

/**
 * What this processor's own configuration does to a document's fate -- the two {@link TsonUnicodePolicy}
 * surfaces [TSON-DATA] §8.2 defines, and the Unicode data version they were computed against.
 *
 * <p><b>Why this exists as a value at all.</b> §8.2's three name-hygiene rules read data the Unicode
 * Consortium declines to freeze, and the level they are applied at is the reading deployment's own choice,
 * so the same bytes may be accepted by one server and refused by another. That divergence is legitimate and
 * is not going away; what is not acceptable is its being unexplainable. This is the explanation, and it is
 * the <em>only</em> place the fact lives: it is in neither the document nor the schema, and a diagnostic is
 * the wrong carrier for it in three ways.
 *
 * <ul>
 *   <li><b>Cardinality.</b> It is constant for the life of a process. Twenty refusals in one document would
 *   carry twenty copies of a string that cannot differ.</li>
 *   <li><b>Time.</b> A per-diagnostic copy arrives only on failure. What a sender needs in order not to
 *   fail is the same fact <em>before</em> it writes the document -- which is what a processor stating this
 *   up front, out of band, gives it, and is where a one-shot repair actually comes from.</li>
 *   <li><b>Direction.</b> A version says what refused you; it does not say what would be accepted. {@code
 *   16.0} is not something a caller can act on, where {@code ASCII_ONLY} is.</li>
 * </ul>
 *
 * <p>So a refusal carries the remedy -- which name, which rule ({@link Diagnostic.Code}, one per rule), and
 * what the policy would admit -- and this carries the configuration, stated once per run or per response
 * beside the diagnostics, and available with no document in hand at all ({@code Tson.processorPolicy()},
 * {@link TsonTreeReader#processorPolicy()}, {@link TsonObjectReader#processorPolicy()}, {@code tson
 * policy}).
 *
 * <p><b>The two policies are the two surfaces, and they are not interchangeable.</b> {@code names} governs
 * declared names, field names, type-refs and annotation names, where all three of §8.2's rules apply;
 * {@code tokens} governs values, where only the restricted-script rule can -- a token has no identifier
 * profile and no scope to be distinct within. A deployment that has relaxed one has said nothing about the
 * other, which is exactly why both are stated.
 *
 * @param names               the policy applied to names -- {@code TsonConfig.identifierPolicy}
 * @param tokens              the policy applied to token values -- {@code TsonConfig.tokenPolicy}
 * @param unicodeDataVersion  {@link TsonUnicodePolicy#dataVersion()}, the UCD release whose tables the
 *                            rules were computed against ({@code SPEC-FEEDBACK.md} #6 on why that is the
 *                            version §8.2's "UTS #39 data version" means)
 */
public record TsonProcessorPolicy(TsonUnicodePolicy names, TsonUnicodePolicy tokens,
                                  String unicodeDataVersion) {

    public TsonProcessorPolicy {
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(unicodeDataVersion, "unicodeDataVersion");
    }

    /**
     * The two policies in force, stamped with the data version this build carries.
     *
     * <p>The version is not a parameter because it is not a choice: it is a property of the tables compiled
     * into this library, and a caller stating a different one would be describing a processor that does not
     * exist.
     */
    public static TsonProcessorPolicy of(TsonUnicodePolicy names, TsonUnicodePolicy tokens) {
        return new TsonProcessorPolicy(names, tokens, TsonUnicodePolicy.dataVersion());
    }

    @Override
    public String toString() {
        return "names " + names + ", tokens " + tokens + ", Unicode " + unicodeDataVersion;
    }
}
