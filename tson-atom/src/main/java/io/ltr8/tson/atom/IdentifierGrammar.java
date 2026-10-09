package io.ltr8.tson.atom;

import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.unicode.IdentifierProfile;

import java.util.Optional;

/**
 * [TSON-DATA] §7.7's identifier grammar -- the {@link IdentifierProfile} every naming position of the series uses
 * ({@link #PROFILE}) -- and §8.2's restricted-character rule over it. Type names, field names, parameter names and
 * enum members are all judged here; other profiles are what the meta-kernel's {@code identifier_type} constructs,
 * for naming positions of an outside system.
 *
 * <p><b>The profile is</b>
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
 * identifier separator rather than spent as an identifier character. Every part of it lies inside §7.1's
 * unquoted-token profile, so a name never needs quoting; another profile carries no such guarantee.
 *
 * <p><b>Joiners.</b> §7.1's unquoted-token profile admits ZWNJ and ZWJ, both being {@code XID_Continue}: a joiner
 * continues a token, and whether it may stand in a <em>name</em> is this layer's question. §7.7 rule 2 answers it
 * with UTS #39 §3.1.1.1's contexts, which every profile applies.
 *
 * <p><b>A name arrives NFC</b> -- an unquoted token by the lexer's rule, a quoted one normalised before it is
 * matched ([TSON-DATA] §7.2.1) -- and an {@code identifier_type} atom puts the text it reads into its form first
 * ([TSON-SCHEMA] §5.5). So the profile's refusal of text not in the form is never a refusal of a value.
 *
 * <p><b>It is not a parser.</b> Nothing here turns a token into a host value: it answers <em>is this a legal
 * name</em> over text a caller already holds. The callers of {@link #validate} are both encodings' readers, the
 * schema parser, the resolver and the linker -- none of them reading a value. Reading a value of the kernel's
 * {@code identifier} is {@link io.ltr8.tson.atom.parser.IdentifierParser#IDENTIFIER}'s, which puts the text into
 * NFC first where this refuses text not in it.
 *
 * <p><b>§8.2 and the profile's own characters.</b> A character a profile adds -- this one's {@code -}, another's
 * {@code $} -- carries no {@code Identifier_Status} and participates in no name-hygiene rule, as §8.2 says, which
 * is {@link IdentifierProfile#restrictedCharacter}'s exception. And a character a profile adds that is not
 * {@code XID_Continue} divides a name into §8.2's segments ({@link IdentifierProfile#separates}), so §8.2's
 * {@code _}/{@code -} is exactly this profile's answer.
 */
public final class IdentifierGrammar {

    /**
     * [TSON-DATA] §7.7's profile: {@code XID_Start}, {@code XID_Continue ∪ { - }}, NFC. Built from the meta-kernel's
     * {@code identifier} ({@link IdentifierType#IDENTIFIER}), which declares it, so the series names with one
     * definition.
     */
    public static final IdentifierProfile PROFILE = IdentifierType.IDENTIFIER.profile();

    private IdentifierGrammar() {
    }

    /**
     * [TSON-DATA] §7.7's grammar over {@code text}: the violation, or empty if it is a legal identifier.
     *
     * <p>Stable across Unicode versions in the way §8.2's rules are not, which is why a failure here is a
     * <em>validity</em> error where {@link #hygiene}'s is a policy refusal.
     */
    public static Optional<String> validate(String text) {
        return PROFILE.check(text);
    }

    /**
     * [TSON-DATA] §8.2's <b>restricted-character rule</b>, alone: every {@code XID_Continue} character of a
     * name must be {@code Identifier_Status=Allowed} (UTS #39 §3.1). The violation, or empty.
     *
     * <p>A violation here is a policy refusal rather than a validity error, because the table it reads is data
     * the Unicode Consortium declines to freeze.
     *
     * <p>This is {@link #PROFILE}'s rule, the one every naming position of the series meets; {@link
     * IdentifierProfile#restrictedCharacter} states the exceptions.
     */
    public static Optional<String> hygiene(String text) {
        return PROFILE.restrictedCharacter(text);
    }
}
