package io.ltr8.tson.cli;

import io.ltr8.annotation.Field;
import io.ltr8.tson.compiler.TsonProcessorPolicy;
import io.ltr8.tson.compiler.TsonUnicodePolicy;

import java.lang.Character.UnicodeScript;
import java.util.List;
import java.util.Set;

/**
 * This CLI's on-the-wire shape for {@link TsonProcessorPolicy} -- what this run's [TSON-DATA] §8.2 name
 * hygiene was judged by, stated once per {@link ValidationRun}/{@link ValidationReport} and printable on
 * its own by {@code tson policy}.
 *
 * <p><b>Why the envelope carries it and a diagnostic does not.</b> §8.2's rules read data the Unicode
 * Consortium does not freeze, at a level this deployment chose, so one processor refuses a name another
 * accepts. That fact is constant for the whole run -- a copy on each refusal would be N copies of one
 * value -- and it is what a sender needs <em>before</em> it writes a document, which is why {@code tson
 * policy} prints this with no document in hand. The refusal itself carries the remedy: which name, and
 * which rule, in its own {@code code}.
 *
 * <p>A separate DTO from {@link TsonProcessorPolicy} for {@link CliDiagnostic}'s own reason -- {@code
 * diagnostics.tn} declares these fields as {@code text}, and {@link UnicodeScript} is a JDK enum of some
 * 170 members that no wire schema should be restating. The scripts render by name; {@link
 * TsonUnicodePolicy.Level} stays the real enum, since enum narrowing is the proven binding path here.
 */
public record CliPolicy(CliUnicodePolicy names, CliUnicodePolicy tokens,
                        @Field("unicode_data_version") String unicodeDataVersion) {

    static CliPolicy from(TsonProcessorPolicy policy) {
        return new CliPolicy(CliUnicodePolicy.from(policy.names()), CliUnicodePolicy.from(policy.tokens()),
                policy.unicodeDataVersion());
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
