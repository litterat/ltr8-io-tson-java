import io.ltr8.tson.base.LimitsPolicy;

/**
 * What every encoding and every phase of this library reports through.
 *
 * <p>A leaf: it requires nothing of this project. It holds {@link io.ltr8.tson.base.Diagnostic} -- one record for
 * every problem the library states, with the closed {@code Code} enum a consumer routes on -- the
 * receivers that decide a diagnostic's fate, {@link io.ltr8.tson.base.SourcePosition} -- the three
 * coordinates a report points at -- and {@link LimitsPolicy} with the refusal it
 * raises, which [TSON-JSON] §10.1 makes one policy across every encoding "with the same defaults".
 *
 * <p><b>Why a module of its own.</b> [TSON-JSON] §9.4 makes the JSON encoding report in
 * [TSON-DATA] §8.1's four categories and adds none of its own, so the vocabulary is one vocabulary across
 * both encodings by specification rather than by convenience. Leaving it in {@code tson-compiler} would
 * make every other encoding depend on the TSON text engine to say "this field is required", or mint a
 * second vocabulary for one fact.
 *
 * <p><b>And what a deployment constrains.</b> The two Unicode policies and the limits say what this
 * processor will admit and spend; {@link io.ltr8.tson.base.SchemaSource} and its two implementations say
 * what it will <em>fetch</em>, which [TSON-JSON] §10.4 names as the restriction an application processing
 * untrusted input sets. They are the same kind of statement -- a constraint this deployment chose, which
 * another may choose differently -- and a schema is identified and obtained the same way whichever
 * encoding named it ({@link io.ltr8.tson.base.CanonicalIdentity} is [TSON-DATA] §2.2.1's algorithm).
 *
 * <p><b>What deliberately stayed behind.</b> {@code Diagnostic}'s classifying factories -- the ones that
 * turn a thrown exception into a diagnostic -- switch on an encoding's own exception types, so each
 * encoding owns its own ({@code TsonDiagnostics} in {@code tson-compiler}). What is shared is the shape of
 * an answer, never the reading of a document.
 */
module io.ltr8.tson.base {
    // The platform's HTTP client, for HttpSchemaSource. A JDK module rather than a dependency: base still
    // requires nothing of this project, and a consumer resolving it resolves what the JDK already ships.
    requires transitive java.net.http;

    exports io.ltr8.tson.base;

    /**
     * The UCD-16.0 tables and the UTS #39 rules over them, exported because two engines read them:
     * {@code tson-compiler}'s lexer and identifier parser, and whatever a second encoding needs to know
     * what an identifier is. Pure Unicode -- nothing here knows what a TSON document looks like.
     */
    exports io.ltr8.tson.base.unicode;
}
