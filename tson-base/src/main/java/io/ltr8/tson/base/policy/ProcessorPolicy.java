package io.ltr8.tson.base.policy;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.unicode.Xid;

import java.util.Objects;

/**
 * What this processor will admit, and what it will spend -- everything about a read that is neither in the
 * document nor in the schema. The two surfaces [TSON-DATA] §8.2 defines, the
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
 * <p><b>The two policies are the two surfaces, and they are not interchangeable.</b> The identifier policy governs
 * names -- declared names, field names, type-refs, annotation names, and every value whose type is an identifier family
 * -- where all three of §8.2's rules apply; the token policy governs values, where only the restricted-script rule can
 * -- a token has no identifier profile and no scope to be distinct within. A deployment that has relaxed one has said
 * nothing about the other, which is exactly why both are stated.
 *
 * <p><b>The two have different types, because they have different shapes.</b> An {@link IdentifierPolicy}
 * has a unit, a restricted-character rule and a scope relation; a token policy is a {@link ScriptPolicy}, a
 * level alone. A per-segment token policy -- which would admit UTS #39's own {@code Toys-Я-Us}, {@code -} being
 * an ordinary character in a value -- is not refused here but unwritable.
 *
 * <p>The components are named for the {@code ProcessorConfig} settings they report, so a configuration and the
 * report it produces are one vocabulary and one grep.
 *
 * <p><b>A record, and not yet an interface.</b> [TSON-JSON] adds policies of its own -- §4.3's annotation
 * stripping, §9.2's encoder latitude -- and a {@code JsonProcessorPolicy} carrying them will need something
 * to be a variant of. Records are final, so that will be composition or a sealed interface over this; which
 * one depends on what JSON actually adds, and every candidate today is encode-side. Deciding it now would
 * be designing against a guess.
 *
 * @param identifierPolicy    the policy applied to names -- {@code ProcessorConfig.identifierPolicy}
 * @param tokenPolicy         the policy applied to token values -- {@code ProcessorConfig.tokenPolicy}
 * @param limits              what this processor will spend reading a document -- {@code ProcessorConfig.limits}
 * @param unicodeDataVersion  {@link #dataVersion()}, the UCD release whose tables the
 *                            rules were computed against ([TSON-DATA] §8.2 on why that is the
 *                            version §8.2's "UTS #39 data version" means)
 */
public record ProcessorPolicy(IdentifierPolicy identifierPolicy, ScriptPolicy tokenPolicy,
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
    public static ProcessorPolicy of(IdentifierPolicy identifierPolicy, ScriptPolicy tokenPolicy,
                                     LimitsPolicy limits) {
        return new ProcessorPolicy(identifierPolicy, tokenPolicy, limits, dataVersion());
    }

    /**
     * The UTS #39 data version every [TSON-DATA] §8.2 name-hygiene rule is computed against, as this build
     * carries it.
     *
     * <p><b>§8.2 requires a refusal to name it</b>, and the reason is that the three rules read {@code
     * confusables.txt}, {@code IdentifierStatus.txt} and the script data, none of which the Unicode
     * Consortium freezes: two conforming processors may legitimately disagree about one name, and the
     * version is the only thing that explains the disagreement.
     *
     * <p><b>It is stated once, not once per refusal</b> (as {@link #unicodeDataVersion()}, which a run or a
     * response carries beside its diagnostics). It is constant for the life of a process, so a copy on each
     * problem is N copies of a string that cannot differ; and what a sender needs in order not to be refused
     * is this fact <em>before</em> it writes a document, which a channel that only opens on failure cannot
     * give it. What the refusal itself carries is the remedy -- which name, which rule, and what the policy
     * would admit.
     *
     * <p>Lives here rather than beside the tables it describes because the {@code unicode} package is the
     * engines' and not a consumer's, and because it is a fact about all three mechanisms rather than about any
     * one policy: this is the type that reports it.
     *
     * <p><b>It is the UCD version.</b> §8.2 asks for "the UTS #39 data version" and its detection note for
     * "the UTS #39 version they were computed against"; UTS #39's data files are versioned with the UCD
     * release that publishes them, so the two track and this states the one that exists --
     * [TSON-DATA] §8.2.
     */
    public static String dataVersion() {
        return Xid.UNICODE_VERSION;
    }

    /** {@link #defaults()} is what a processor applies before a deployment says anything. */
    public static ProcessorPolicy defaults() {
        return of(IdentifierPolicy.defaults(), ScriptPolicy.unrestricted(), LimitsPolicy.defaults());
    }

    /**
     * This policy with one component replaced -- what a derived reader is built from.
     *
     * <p>Three methods rather than a builder because there are three components and a policy is small: a
     * reader deriving one names the component it is changing, at the call site, in a form a reader of that
     * call site can see is a change of exactly one thing.
     */
    public ProcessorPolicy withIdentifierPolicy(IdentifierPolicy policy) {
        return of(policy, tokenPolicy, limits);
    }

    public ProcessorPolicy withTokenPolicy(ScriptPolicy policy) {
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
