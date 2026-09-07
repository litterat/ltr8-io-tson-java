/**
 * What every encoding and every phase of this library reports through.
 *
 * <p>A pure leaf, depending on nothing. It holds {@link io.ltr8.tson.base.Diagnostic} -- one record for
 * every problem the library states, with the closed {@code Code} enum a consumer routes on -- the
 * receivers that decide a diagnostic's fate, {@link io.ltr8.tson.base.SourcePosition} -- the three
 * coordinates a report points at -- and {@link io.ltr8.tson.base.TsonLimitsPolicy} with the refusal it
 * raises, which [TSON-JSON] §10.1 makes one policy across every encoding "with the same defaults".
 *
 * <p><b>Why a module of its own.</b> [TSON-JSON] §9.4 makes the JSON encoding report in
 * [TSON-DATA] §8.1's four categories and adds none of its own, so the vocabulary is one vocabulary across
 * both encodings by specification rather than by convenience. Leaving it in {@code tson-compiler} would
 * make every other encoding depend on the TSON text engine to say "this field is required", or mint a
 * second vocabulary for one fact.
 *
 * <p><b>What deliberately stayed behind.</b> {@code Diagnostic}'s classifying factories -- the ones that
 * turn a thrown exception into a diagnostic -- switch on an encoding's own exception types, so each
 * encoding owns its own ({@code TsonDiagnostics} in {@code tson-compiler}). What is shared is the shape of
 * an answer, never the reading of a document.
 */
module io.ltr8.tson.base {
    exports io.ltr8.tson.base;
}
