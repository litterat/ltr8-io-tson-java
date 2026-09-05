package io.ltr8.tson.cli;

import io.ltr8.annotation.Field;
import io.ltr8.tson.compiler.TsonLimitsPolicy;
import io.ltr8.tson.compiler.TsonUnicodeProcessorPolicy;
import io.ltr8.tson.compiler.TsonUnicodePolicy;

import java.lang.Character.UnicodeScript;
import java.util.List;
import java.util.Set;

/**
 * This CLI's on-the-wire shape for {@link TsonUnicodeProcessorPolicy} -- what this run's [TSON-DATA] §8.2
 * name hygiene was judged by, stated once per {@link ValidationRun}/{@link ValidationReport} and printable
 * on its own by {@code tson policy}.
 *
 * <p><b>Why the envelope carries it and a diagnostic does not.</b> §8.2's rules read data the Unicode
 * Consortium does not freeze, at a level this deployment chose, so one processor refuses a name another
 * accepts. That fact is constant for the whole run -- a copy on each refusal would be N copies of one
 * value -- and it is what a sender needs <em>before</em> it writes a document, which is why {@code tson
 * policy} prints this with no document in hand. The refusal itself carries the remedy: which name, and
 * which rule, in its own {@code code}.
 *
 * <p>A separate DTO from {@link TsonUnicodeProcessorPolicy} for {@link CliDiagnostic}'s own reason --
 * {@code diagnostics.tn} declares these fields as {@code text}, and {@link UnicodeScript} is a JDK enum of
 * some 170 members that no wire schema should be restating. The scripts render by name; {@link
 * TsonUnicodePolicy.Level} stays the real enum, since enum narrowing is the proven binding path here.
 *
 * <p>The two surfaces keep {@code TsonConfig}'s own names, here and on the wire, so a deployment's
 * configuration and the report it produces are one vocabulary.
 */
public record CliPolicy(@Field("identifier_policy") CliUnicodePolicy identifierPolicy,
                        @Field("token_policy") CliUnicodePolicy tokenPolicy,
                        @Field("unicode_data_version") String unicodeDataVersion,
                        CliLimits limits) {

    /**
     * The policy a run that passed no flag is judged under -- what {@link OutputFormat#TEXT} compares against
     * to decide whether a person needs to be told the policy at all.
     */
    private static final CliPolicy DEFAULTS = from(TsonUnicodeProcessorPolicy.of(
            PolicyOptions.DEFAULTS.identifierPolicy(), PolicyOptions.DEFAULTS.tokenPolicy()),
            PolicyOptions.DEFAULTS.limits());

    /**
     * Whether this is what a run configures by saying nothing.
     *
     * <p>A non-default policy is worth stating even on a clean run: [TSON-DATA] §8.2 requires that a
     * relaxation not be silent, and a run that passed {@code --identifier-policy unrestricted} and printed
     * {@code OK} would be exactly that. The machine formats carry {@code policy} either way.
     */
    boolean isDefault() {
        return equals(DEFAULTS);
    }

    static CliPolicy from(TsonUnicodeProcessorPolicy policy, TsonLimitsPolicy limits) {
        return new CliPolicy(CliUnicodePolicy.from(policy.identifierPolicy()),
                CliUnicodePolicy.from(policy.tokenPolicy()), policy.unicodeDataVersion(),
                new CliLimits(limits.maxDepth()));
    }

    /**
     * This run's [TSON-DATA] §9.1 resource limits -- what it will spend reading one document, where the two
     * {@code *_policy} fields say what it will admit as a name.
     *
     * <p>Inside {@code policy} rather than beside it in the envelope because the envelope's one question is
     * "what judged this run", and a limit refusal answers it as much as a name refusal does. It also gets
     * {@link CliPolicy#isDefault()} for free, so a run that raised the depth states it even when nothing was
     * refused -- the same rule §8.2 imposes on a relaxation, and true here for the same reason.
     */
    public record CliLimits(@Field("max_depth") int maxDepth) {
    }

    /**
     * One §8.2 surface: the UTS #39 §5.2 restriction level, whether it is applied per {@code _}/{@code -}
     * delimited segment rather than to the whole text, and the script combinations admitted over and above
     * the level. The three together are the whole of a policy, which is what lets a reader of a refusal work
     * out which of them another deployment set differently.
     */
    public record CliUnicodePolicy(TsonUnicodePolicy.Level level, @Field("per_segment") boolean perSegment,
                                   List<List<String>> permitting) {

        static CliUnicodePolicy from(TsonUnicodePolicy policy) {
            return new CliUnicodePolicy(policy.level(), policy.isPerSegment(),
                    policy.permittedScripts().stream().map(CliUnicodePolicy::names).toList());
        }

        /** One admitted combination, script names sorted so two deployments' reports compare as text. */
        private static List<String> names(Set<UnicodeScript> scripts) {
            return scripts.stream().map(Enum::name).sorted().toList();
        }
    }
}
