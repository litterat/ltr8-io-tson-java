package io.ltr8.tson.base.unicode;

import java.text.Normalizer;
import java.util.Optional;

/**
 * What may stand as a <b>name</b> -- [TSON-DATA] §7.7's UAX #31 profile, and §8.2's restricted-character
 * rule over it. The type of every naming position in the series: type names, field names, parameter names
 * and enum members.
 *
 * <p><b>An identifier is a name, not a lexeme.</b> It is the decoded text of a token -- after unquoting,
 * escape processing and normalisation -- and it is constrained however it was spelled, where §7.1's
 * unquoted-token profile constrains only one of the spellings. The profile:
 *
 * <pre>
 *   Start    = XID_Start
 *   Continue = XID_Continue ∪ { - }
 * </pre>
 *
 * plus NFC. It is §7.1's token profile minus the extensions the number grammar requires: {@code Nd},
 * {@code -}, {@code +} and {@code .} sit in token-Start so a <em>number</em> can be an unquoted token, and
 * reach names only because names and values share one lexical class. Dropping them from Start is what makes
 * an identifier never begin with a digit or a sign, and it subsumes [TSON-SCHEMA] §12.1's separate rule that
 * numbers are not declarable names. {@code +} is exponent-only; {@code .} is <b>reserved</b> as a future
 * identifier separator rather than spent as an identifier character.
 *
 * <p>Everything else follows from XID membership rather than from a clause of its own -- whitespace, C0/C1
 * controls, {@code Cf} format characters, emoji and unassigned code points are none of them
 * {@code XID_Continue}.
 *
 * <p><b>The XID profile admits ZWNJ and ZWJ</b>, both being {@code XID_Continue}, and so does §7.1's
 * unquoted-token profile: a joiner continues a token, and whether it may stand in a <em>name</em> is this
 * layer's question. {@code Identifier_Status} refuses both, and §7.7 rule 2 carves the exception back --
 * UTS #39 §3.1.1.1's contexts, which admit a joiner where it has a shaping effect and refuse it where it is
 * invisible. Deciding it here by the property rather than by what the lexer happens to permit is what keeps
 * the two layers from drifting.
 *
 * <p>NFC is required as a <em>form</em>, not merely as a comparison. §2.5 and §2.6 define name identity by
 * NFC-normalised comparison, which is the harder rule and the easy one to get wrong; requiring the form
 * makes the stored name equal the compared name and reduces duplicate detection to string equality.
 *
 * <p><b>It lives here, beside the tables it reads, because it is not a parser.</b> Nothing here turns a
 * token into a host value: both methods answer <em>is this a legal name</em> over text a caller already
 * holds. Its callers are the lexer's grammar, the schema parser, the resolver and the linker -- none of them
 * reading a value -- and the {@code identifier} atom is a two-line wrapper over {@link #validate} that lives
 * with the rest of the vocabulary.
 *
 * <p><b>Both methods report a violation rather than throwing one</b>, and both return
 * {@link Optional#empty()} when there is none. That matters twice over. It is what lets the same check serve
 * a caller that owes a parse error and one that owes a diagnostic -- the identical violation is a
 * {@code ParseException} from the lexer and a refusal from the linker, and a signature that threw would
 * force the lexer's answer on everyone. And it keeps the check off the allocation budget: every name in
 * every document runs through here, {@code Optional.empty()} is a singleton, and only the failure path --
 * which is already building a message -- allocates at all.
 */
public final class IdentifierProfile {

    private IdentifierProfile() {
    }

    /**
     * [TSON-DATA] §7.7's grammar over {@code text}: the violation, or empty if it is a legal identifier.
     *
     * <p>Stable across Unicode versions in the way §8.2's rules are not, which is why a failure here is a
     * <em>validity</em> error where {@link #hygiene}'s is a policy refusal.
     */
    public static Optional<String> validate(String text) {
        if (text.isEmpty()) {
            return Optional.of("an identifier may not be empty");
        }
        // Before anything else: UTS #39 §3.1.1.1's global conditions include NFC, and JoiningControls is
        // written to that assumption rather than re-checking it per joiner.
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            return Optional.of("'" + text + "' is not NFC-normalized");
        }
        int first = text.codePointAt(0);
        if (!Xid.isStart(first)) {
            return Optional.of(at(text, first, 0) + " cannot start an identifier"
                    + (Character.isDigit(first) || first == '-' || first == '+' || first == '.'
                            ? " -- an identifier never begins with a digit or a sign" : ""));
        }
        for (int i = Character.charCount(first); i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!(Xid.isContinue(cp) || cp == '-')) {
                return Optional.of(at(text, cp, i) + " cannot appear in an identifier");
            }
            if ((cp == Xid.ZWNJ || cp == Xid.ZWJ) && !JoiningControls.permitted(text, i)) {
                return Optional.of(at(text, cp, i) + " is a join control outside the contexts "
                        + "UTS #39 §3.1.1.1 permits -- it has no shaping effect here, so it is invisible");
            }
            i += Character.charCount(cp);
        }
        return Optional.empty();
    }

    /**
     * [TSON-DATA] §8.2's <b>restricted-character rule</b>, alone: every {@code XID_Continue} character of a
     * name must be {@code Identifier_Status=Allowed} (UTS #39 §3.1). The violation, or empty.
     *
     * <p>A violation here is a policy refusal rather than a validity error -- §8.2's fifth outcome, which
     * MUST NOT be reported in any of the four categories -- because the table it reads is data the Unicode
     * Consortium declines to freeze.
     *
     * <p>Two characters are the grammar's rather than this rule's, though the table restricts both.
     * {@code -} is this profile's own extension, which §8.2 says carries no {@code Identifier_Status} and
     * participates in no name-hygiene rule. ZWNJ and ZWJ are {@code Identifier_Status=Restricted} and §7.7
     * rule 2 carves the exception UTS #39 §3.1.1.1 defines, which makes their admission a question of
     * <em>form</em> and so {@link #validate}'s: a joiner outside those contexts is not an identifier at all,
     * where a restricted character is an identifier this processor declines to accept.
     */
    public static Optional<String> hygiene(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp != '-' && cp != Xid.ZWNJ && cp != Xid.ZWJ && !IdentifierStatus.isAllowed(cp)) {
                return Optional.of(at(text, cp, i) + " is Identifier_Status=Restricted (UTS #39)");
            }
            i += Character.charCount(cp);
        }
        return Optional.empty();
    }

    /** Names the offending code point rather than printing it -- much of what this rejects is invisible. */
    private static String at(String text, int cp, int index) {
        return "'%s': U+%04X at index %d".formatted(text, cp, index);
    }
}
