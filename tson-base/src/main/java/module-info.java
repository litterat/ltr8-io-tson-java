/**
 * What every encoding and every phase of this library reports through, and what a deployment constrains it
 * with.
 *
 * <p>Five packages, and <b>the root names none of them</b> --
 * every dependency runs inward, so a subpackage may be read on its own and the vocabulary at the centre
 * stays free of the machinery around it.
 *
 * <ul>
 *   <li>{@code io.ltr8.tson.base} -- how a problem is stated. {@link io.ltr8.tson.base.Diagnostic}, one
 *       record for every problem the library states, with the closed {@code Code} enum a consumer routes on;
 *       the receivers that decide a diagnostic's fate; {@link io.ltr8.tson.base.SourcePosition}, the three
 *       coordinates a report points at; {@link io.ltr8.tson.base.CanonicalIdentity}, [TSON-DATA] §2.2.1's
 *       algorithm for how a schema is named; and the exceptions.
 *   <li>{@code io.ltr8.tson.base.policy} -- what this processor will admit and spend.
 *   <li>{@code io.ltr8.tson.base.source} -- where it will obtain a schema.
 *   <li>{@code io.ltr8.tson.base.atom} -- the host values the built-in atoms read to.
 *   <li>{@code io.ltr8.tson.base.bind} -- what a deployment binds with.
 * </ul>
 *
 * <p><b>The exceptions stay at the root rather than following their subject</b>, which is what keeps the
 * inward rule true: {@code Diagnostic.ofLimitExceeded} and {@code Code.of(SchemaFetchException.Reason)} are
 * same-package calls, where filing each exception with the package it is thrown by would have the centre
 * depend on two of its own subpackages. Sorting the eight by "is a Throwable" would be sorting by Java
 * mechanism in any case; this library files by subject, which is why {@code LexException} sits in
 * {@code lexer} and not in an exception bucket.
 *
 * <p><b>Why a module of its own.</b> [TSON-JSON] §9.4 makes the JSON encoding report in
 * [TSON-DATA] §8.1's four categories and adds none of its own, so the vocabulary is one vocabulary across
 * both encodings by specification rather than by convenience. Leaving it in {@code tson-compiler} would
 * make every other encoding depend on the TSON text engine to say "this field is required", or mint a
 * second vocabulary for one fact.
 *
 * <p><b>And what a deployment constrains.</b> The two Unicode policies and the limits say what this
 * processor will admit and spend; {@code SchemaSource} and its two implementations say what it will
 * <em>fetch</em>, which [TSON-JSON] §10.4 names as the restriction an application processing untrusted
 * input sets; {@code AtomContext} says what it <em>binds</em> with. They are the same kind of statement --
 * a constraint this deployment chose, which another may choose differently -- and none of them depends on
 * which encoding carried the document.
 *
 * <p><b>The two engines it rests on are system libraries, not layers above it.</b> {@code java.net.http}
 * and {@code io.ltr8.bind} are both general and both know nothing of TSON -- {@code ltr8-bind} binds a
 * {@code DataValue} to a Java object and has never heard of a schema. Depending on them is the same kind of
 * thing as depending on the JDK, which is why it does not make this module a layer in the stack: nothing
 * here knows what a TSON document or a JSON one looks like, and that is the property that matters.
 *
 * <p><b>What deliberately stayed behind.</b> {@code Diagnostic}'s classifying factories -- the ones that
 * turn a thrown exception into a diagnostic -- switch on an encoding's own exception types, so each
 * encoding owns its own ({@code TsonDiagnostics} in {@code tson-compiler}). What is shared is the shape of
 * an answer, never the reading of a document.
 */
module io.ltr8.tson.base {
    // The platform's HTTP client, for HttpSchemaSource. A consumer resolving this module resolves what the
    // JDK already ships.
    requires transitive java.net.http;

    // A dependency-free binding engine that knows nothing about TSON -- system-library standing, like
    // java.net.http above and ltr8-regex elsewhere. What this module adds is the TSON-side configuration:
    // which host types bind as atoms, and (in time) the rest of what a deployment states about binding.
    requires transitive io.ltr8.bind;

    // Network text formats, recognised natively -- system-library standing again. CanonicalIdentity and
    // SchemaReference split a reference with Iri, and the formats' values are host types AtomContext binds.
    requires transitive io.ltr8.net;

    // Unicode properties and algorithms -- system-library standing again. The policies are built on its profiles
    // and restriction levels, and the engines above read its normalization forms and identifier profiles directly.
    requires transitive io.ltr8.unicode;

    exports io.ltr8.tson.base;
    exports io.ltr8.tson.base.io;

    /**
     * What a rule says when a document breaks it -- stated once, for every encoding. [TSON-JSON] §9.4 gives
     * both encodings one diagnostic vocabulary and adds no category of its own, so a document wrong in one is
     * wrong in the other for the same stated reason; the {@code code} and the machine-readable {@code
     * expected} are what a consumer routes on, and two independently-written readers agreeing about them was
     * previously a matter of one having been copied from the other.
     *
     * <p>The prose is the <b>schema's</b> vernacular rather than any format's -- a record has fields whether
     * a member or a {@code name:} carried one -- because it is the schema that refused the document. What the
     * document literally held rides in {@code actual}, which is data and stays the encoding's.
     */
    exports io.ltr8.tson.base.diagnostics;

    /**
     * What this processor will admit as a name and spend on a document -- {@code ProcessorPolicy} and what
     * it composes: {@code IdentifierPolicy} and {@code ScriptPolicy} (§8.2's two surfaces) and {@code
     * LimitsPolicy} (§9.1's bounds).
     * One package because a deployment states one policy, and §8.2 requires a relaxation be code rather
     * than ambient: this is where that code points.
     *
     * <p>The mechanisms the policies configure -- UTS #39's restriction levels, skeleton distinctness, the
     * identifier profiles -- are {@code io.ltr8.unicode}'s; what is here is the choice a deployment makes with
     * them. A consumer names this, with {@code RestrictionLevel} for a level, to configure a processor.
     */
    exports io.ltr8.tson.base.policy;

    /**
     * Where a schema comes from: {@code SchemaSource}, the seam, and the two implementations that ship --
     * a directory and an HTTPS host allow-list, both denying by default. [TSON-JSON] §10.4 names this as
     * the restriction an application processing untrusted input sets, which makes it configuration like
     * the policies rather than machinery like an encoding's reader.
     *
     * <p>{@code SchemaReference} -- [TSON-DATA] §2.2.1's rules on what an identity may be, applied to a
     * reference that in a server arrives in a request body -- is package-private here, visible to the
     * three classes that share it and to nothing else in the module.
     */
    exports io.ltr8.tson.base.source;

    /**
     * The host values the built-in atoms read to that are TSON's own -- {@code Rational}, {@code Complex}; the
     * network ones are {@code io.ltr8.net}'s. The question a consumer arrives with (<em>what do I get back from
     * {@code !rational}?</em>) rather than part of the resolved-schema model, and pure values depending on
     * nothing, which is why they sit at the centre rather than in whichever module first needed one.
     */
    exports io.ltr8.tson.base.atom;

    /**
     * What a deployment binds with. {@code AtomContext} registers {@code base.atom}'s host values, and the
     * JDK ones beside them, with a {@code DataBindContext}, so a class binds the same under every encoding.
     */
    exports io.ltr8.tson.base.bind;
}
