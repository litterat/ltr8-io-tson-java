package io.ltr8.tson.base.policy;

import io.ltr8.tson.base.Diagnostic;

import java.util.Objects;

/**
 * What this processor will admit, and what it will spend -- everything about a read that is neither in the
 * document nor in the schema. The two {@link UnicodePolicy} surfaces [TSON-DATA] §8.2 defines, the
 * Unicode data version they were computed against, and the {@link LimitsPolicy} bounds of §9.1.
 *
 * <p><b>Three settings, one value, because a deployment states one policy.</b> The limits used to sit
 * beside this record rather than inside it, on the argument that "what this processor will spend" and "what
 * it will admit as a name" answer two questions and a deployment changing one has said nothing about the
 * other. Both halves of that are still true -- and neither was ever an argument for two values. It was an
 * argument against putting a nesting bound inside something called a <em>Unicode</em> processor policy,
 * which was correct: the container was the problem, not the grouping. Under this name the objection
 * dissolves, and the components stay independent exactly as they were. The wire shape had already settled
 * it: the CLI envelope nests {@code limits} inside its {@code policy} field, because that is what a
 * consumer asks for.
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
 * {@code TsonTreeReader.processorPolicy()}, {@code TsonObjectReader.processorPolicy()}, {@code tson
 * policy}).
 *
 * <p><b>The two policies are the two surfaces, and they are not interchangeable.</b> The identifier policy
 * governs declared names, field names, type-refs and annotation names, where all three of §8.2's rules
 * apply; the token policy governs values, where only the restricted-script rule can -- a token has no
 * identifier profile and no scope to be distinct within. A deployment that has relaxed one has said nothing
 * about the other, which is exactly why both are stated.
 *
 * <p>The components are named for the {@code TsonConfig} settings they report, so a configuration and the
 * report it produces are one vocabulary and one grep.
 *
 * <p><b>A record, and not yet an interface.</b> [TSON-JSON] adds policies of its own -- §4.3's annotation
 * stripping, §9.2's encoder latitude -- and a {@code JsonProcessorPolicy} carrying them will need something
 * to be a variant of. Records are final, so that will be composition or a sealed interface over this; which
 * one depends on what JSON actually adds, and every candidate today is encode-side. Deciding it now would
 * be designing against a guess.
 *
 * @param identifierPolicy    the policy applied to names -- {@code TsonConfig.identifierPolicy}
 * @param tokenPolicy         the policy applied to token values -- {@code TsonConfig.tokenPolicy}
 * @param limits              what this processor will spend reading a document -- {@code TsonConfig.limits}
 * @param unicodeDataVersion  {@link UnicodePolicy#dataVersion()}, the UCD release whose tables the
 *                            rules were computed against ([TSON-DATA] §8.2 on why that is the
 *                            version §8.2's "UTS #39 data version" means)
 */
public record ProcessorPolicy(UnicodePolicy identifierPolicy, UnicodePolicy tokenPolicy,
                              LimitsPolicy limits, String unicodeDataVersion) {

    public ProcessorPolicy {
        Objects.requireNonNull(identifierPolicy, "identifierPolicy");
        Objects.requireNonNull(tokenPolicy, "tokenPolicy");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(unicodeDataVersion, "unicodeDataVersion");
    }

    /**
     * The two policies in force, stamped with the data version this build carries.
     *
     * <p>The version is not a parameter because it is not a choice: it is a property of the tables compiled
     * into this library, and a caller stating a different one would be describing a processor that does not
     * exist.
     */
    public static ProcessorPolicy of(UnicodePolicy identifierPolicy, UnicodePolicy tokenPolicy,
                                     LimitsPolicy limits) {
        return new ProcessorPolicy(identifierPolicy, tokenPolicy, limits, UnicodePolicy.dataVersion());
    }

    /** {@link #defaults()} is what a processor applies before a deployment says anything. */
    public static ProcessorPolicy defaults() {
        return of(UnicodePolicy.highlyRestrictive(), UnicodePolicy.unrestricted(), LimitsPolicy.defaults());
    }

    /**
     * This policy with one component replaced -- what a derived reader is built from.
     *
     * <p>Three methods rather than a builder because there are three components and a policy is small: a
     * reader deriving one names the component it is changing, at the call site, in a form a reader of that
     * call site can see is a change of exactly one thing.
     */
    public ProcessorPolicy withIdentifierPolicy(UnicodePolicy policy) {
        return of(policy, tokenPolicy, limits);
    }

    public ProcessorPolicy withTokenPolicy(UnicodePolicy policy) {
        return of(identifierPolicy, policy, limits);
    }

    public ProcessorPolicy withLimits(LimitsPolicy policy) {
        return of(identifierPolicy, tokenPolicy, policy);
    }

    @Override
    public String toString() {
        return "identifier policy " + identifierPolicy + ", token policy " + tokenPolicy
                + ", " + limits + ", Unicode " + unicodeDataVersion;
    }
}
