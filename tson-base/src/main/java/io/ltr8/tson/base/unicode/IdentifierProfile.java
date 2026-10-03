package io.ltr8.tson.base.unicode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A UAX #31 identifier profile, and the one the series names with -- [TSON-DATA] §7.7's, as {@link #NAME}, with
 * §8.2's restricted-character rule over it. {@link #NAME} is the type of every naming position in the series:
 * type names, field names, parameter names and enum members. Other profiles are what the meta-kernel's
 * {@code identifier_type} constructs, for naming positions of an outside system -- an API's operation names, a
 * database's column names.
 *
 * <p><b>The shape is UAX #31's R1 default identifier syntax</b>, each set drawn from a Unicode property and
 * adjusted:
 *
 * <pre>
 *   identifier := Start Continue* (Medial Continue+)*
 *   Start      := (start base    ∪ start_add)    − exclude
 *   Continue   := (continue base ∪ continue_add) − exclude
 *   Medial     := medial
 * </pre>
 *
 * with a base of {@code XID_Start}/{@code XID_Continue} ({@link Base#XID}), {@code ID_Start}/{@code ID_Continue}
 * ({@link Base#ID}) or the empty set ({@link Base#NONE}), and the whole text in the {@link Normalization} form
 * the profile requires. A medial character stands only between two others, and never beside another medial;
 * the set is disjoint from Start and Continue, or the rule could not tell which one a character is using.
 *
 * <p><b>{@link #NAME} is §7.7's profile:</b>
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
 * <p>Everything else follows from XID membership rather than from a clause of its own -- whitespace, C0/C1
 * controls, {@code Cf} format characters, emoji and unassigned code points are none of them
 * {@code XID_Continue}.
 *
 * <p><b>Both bases admit ZWNJ and ZWJ</b>, both being {@code XID_Continue} and {@code ID_Continue}, and so
 * does §7.1's unquoted-token profile: a joiner continues a token, and whether it may stand in a <em>name</em>
 * is this layer's question. {@code Identifier_Status} refuses both, and §7.7 rule 2 carves the exception back
 * -- UTS #39 §3.1.1.1's contexts, which admit a joiner where it has a shaping effect and refuse it where it is
 * invisible. Every profile applies it, an invisible joiner being as much a spoofing surface in an outside
 * system's names as in the series' own.
 *
 * <p><b>The profile judges a value, which is already in its {@link Normalization} form.</b> An
 * {@code identifier_type} atom puts the text it reads into the form first (SPEC-FEEDBACK.md #19), and a name the
 * series reads arrives NFC -- an unquoted token by the lexer's rule, a quoted one normalised before it is matched
 * ([TSON-DATA] §7.2.1). So {@link #check} refuses text not in the form rather than normalising it: the form holds
 * for every caller with a value, the stored name equals the compared name, and duplicate detection is string
 * equality.
 *
 * <p><b>It lives here, beside the tables it reads, because it is not a parser.</b> Nothing here turns a
 * token into a host value: it answers <em>is this a legal name</em> over text a caller already holds. The
 * callers of {@link #validate} are the lexer's grammar, the schema parser, the resolver and the linker --
 * none of them reading a value -- and the {@code identifier_type} atom is a thin wrapper over {@link #check}
 * that lives with the rest of the vocabulary.
 *
 * <p><b>Every check reports a violation rather than throwing one</b>, and returns {@link Optional#empty()}
 * when there is none. That matters twice over. It is what lets the same check serve a caller that owes a
 * parse error and one that owes a diagnostic -- the identical violation is a {@code ParseException} from the
 * lexer and a refusal from the linker, and a signature that threw would force the lexer's answer on everyone.
 * And it keeps the check off the allocation budget: every name in every document runs through here,
 * {@code Optional.empty()} is a singleton, the sets are built once with the profile, and only the failure
 * path -- which is already building a message -- allocates at all.
 */
public final class IdentifierProfile {

    /** The Unicode property a Start or Continue set is drawn from, before its additions and exclusions. */
    public enum Base {
        /** {@code XID_Start} / {@code XID_Continue}: closed under NFKC, UAX #31's recommendation. */
        XID,
        /** {@code ID_Start} / {@code ID_Continue}: XID plus the few characters whose NFKC form is not one. */
        ID,
        /** The empty set: the additions are the whole set. */
        NONE
    }

    /** [TSON-DATA] §7.7's profile: {@code XID_Start}, {@code XID_Continue ∪ { - }}, NFC. */
    public static final IdentifierProfile NAME =
            of(Base.XID, Base.XID, "", "-", "", "", Normalization.NFC);

    private final Base start;
    private final Base continueBase;
    private final int[] startAdd;
    private final int[] continueAdd;
    private final int[] medial;
    private final int[] exclude;
    private final Normalization normalization;

    private IdentifierProfile(Base start, Base continueBase, int[] startAdd, int[] continueAdd, int[] medial,
                              int[] exclude, Normalization normalization) {
        this.start = start;
        this.continueBase = continueBase;
        this.startAdd = startAdd;
        this.continueAdd = continueAdd;
        this.medial = medial;
        this.exclude = exclude;
        this.normalization = normalization;
    }

    /**
     * The profile these sets make. Each of {@code startAdd}, {@code continueAdd}, {@code medial} and
     * {@code exclude} is read as the set of code points its text holds -- order and repetition mean nothing.
     */
    public static IdentifierProfile of(Base start, Base continueBase, String startAdd, String continueAdd,
                                       String medial, String exclude, Normalization normalization) {
        return new IdentifierProfile(start, continueBase, codePoints(startAdd), codePoints(continueAdd),
                codePoints(medial), codePoints(exclude), normalization);
    }

    /**
     * [TSON-DATA] §7.7's grammar over {@code text}: the violation, or empty if it is a legal identifier.
     *
     * <p>Stable across Unicode versions in the way §8.2's rules are not, which is why a failure here is a
     * <em>validity</em> error where {@link #hygiene}'s is a policy refusal.
     */
    public static Optional<String> validate(String text) {
        return NAME.check(text);
    }

    /** This profile over {@code text}: the violation, or empty if {@code text} is an identifier under it. */
    public Optional<String> check(String text) {
        if (text.isEmpty()) {
            return Optional.of("an identifier may not be empty");
        }
        // Before anything else: UTS #39 §3.1.1.1's global conditions include NFC, and JoiningControls is
        // written to that assumption rather than re-checking it per joiner.
        if (!normalization.holds(text)) {
            return Optional.of("'" + text + "' is not in " + normalization + " form");
        }
        int first = text.codePointAt(0);
        if (!isStart(first)) {
            return Optional.of(at(text, first, 0) + " cannot start an identifier"
                    + (Character.isDigit(first) || first == '-' || first == '+' || first == '.'
                            ? " -- an identifier never begins with a digit or a sign" : ""));
        }
        boolean afterMedial = false;
        for (int i = Character.charCount(first); i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (isContinue(cp)) {
                if ((cp == Xid.ZWNJ || cp == Xid.ZWJ) && !JoiningControls.permitted(text, i)) {
                    return Optional.of(at(text, cp, i) + " is a join control outside the contexts "
                            + "UTS #39 §3.1.1.1 permits -- it has no shaping effect here, so it is invisible");
                }
                afterMedial = false;
            } else if (contains(medial, cp)) {
                if (afterMedial) {
                    return Optional.of(at(text, cp, i) + " follows another medial character");
                }
                afterMedial = true;
            } else {
                return Optional.of(at(text, cp, i) + " cannot appear in an identifier");
            }
            i += Character.charCount(cp);
        }
        if (afterMedial) {
            int last = text.codePointBefore(text.length());
            return Optional.of(at(text, last, text.length() - Character.charCount(last))
                    + " is a medial character and cannot end an identifier");
        }
        return Optional.empty();
    }

    /**
     * What makes this profile admit no identifier, or admit one ambiguously: empty when there is nothing. A
     * Start set left empty admits nothing at all; a medial character that is also Start or Continue could be
     * read as either, which the grammar's placement rule cannot decide.
     */
    public List<String> incoherence() {
        List<String> problems = new ArrayList<>();
        if (start == Base.NONE && Arrays.stream(startAdd).allMatch(cp -> contains(exclude, cp))) {
            problems.add("the Start set is empty, so no text is an identifier");
        }
        for (int cp : medial) {
            if (isStart(cp) || isContinue(cp)) {
                problems.add("U+%04X is medial and also Start or Continue".formatted(cp));
            }
        }
        return List.copyOf(problems);
    }

    private boolean isStart(int cp) {
        return (inBase(start, cp, true) || contains(startAdd, cp)) && !contains(exclude, cp);
    }

    private boolean isContinue(int cp) {
        return (inBase(continueBase, cp, false) || contains(continueAdd, cp)) && !contains(exclude, cp);
    }

    private static boolean inBase(Base base, int cp, boolean start) {
        return switch (base) {
            case XID -> start ? Xid.isStart(cp) : Xid.isContinue(cp);
            case ID -> start ? Xid.isIdStart(cp) : Xid.isIdContinue(cp);
            case NONE -> false;
        };
    }

    private static boolean contains(int[] set, int cp) {
        return set.length != 0 && Arrays.binarySearch(set, cp) >= 0;
    }

    private static int[] codePoints(String text) {
        return text.codePoints().sorted().distinct().toArray();
    }

    /** Two profiles are equal when they admit by the same sets and form, however the sets were spelled. */
    @Override
    public boolean equals(Object other) {
        return other instanceof IdentifierProfile that && start == that.start && continueBase == that.continueBase
                && Arrays.equals(startAdd, that.startAdd) && Arrays.equals(continueAdd, that.continueAdd)
                && Arrays.equals(medial, that.medial) && Arrays.equals(exclude, that.exclude)
                && normalization == that.normalization;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * start.hashCode() + continueBase.hashCode()) + Arrays.hashCode(continueAdd)
                + normalization.hashCode();
    }

    /**
     * [TSON-DATA] §8.2's <b>restricted-character rule</b>, alone: every {@code XID_Continue} character of a
     * name must be {@code Identifier_Status=Allowed} (UTS #39 §3.1). The violation, or empty.
     *
     * <p>A violation here is a policy refusal rather than a validity error -- §8.2's fifth outcome, which
     * MUST NOT be reported in any of the four categories -- because the table it reads is data the Unicode
     * Consortium declines to freeze.
     *
     * <p>This is {@link #NAME}'s rule, the one every naming position of the series meets; {@link
     * #restrictedCharacter} states the exceptions.
     */
    public static Optional<String> hygiene(String text) {
        return NAME.restrictedCharacter(text);
    }

    /**
     * The restricted-character rule over a name this profile admits -- the violation, or empty.
     *
     * <p>Some characters are the grammar's rather than this rule's, though the table may restrict them. A
     * character this profile adds -- {@code start_add}, {@code continue_add} or {@code medial}, as {@link
     * #NAME} adds {@code -} -- is the profile's own extension, which §8.2 says carries no {@code
     * Identifier_Status} and participates in no name-hygiene rule: a profile that admits {@code $} has decided
     * {@code $} belongs in its names. ZWNJ and ZWJ are {@code Identifier_Status=Restricted} and §7.7 rule 2
     * carves the exception UTS #39 §3.1.1.1 defines, which makes their admission a question of <em>form</em>
     * and so {@link #check}'s: a joiner outside those contexts is not an identifier at all, where a restricted
     * character is an identifier this processor declines to accept.
     */
    public Optional<String> restrictedCharacter(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp != Xid.ZWNJ && cp != Xid.ZWJ && !IdentifierStatus.isAllowed(cp) && !added(cp)) {
                return Optional.of(at(text, cp, i) + " is Identifier_Status=Restricted (UTS #39)");
            }
            i += Character.charCount(cp);
        }
        return Optional.empty();
    }

    private boolean added(int cp) {
        return contains(startAdd, cp) || contains(continueAdd, cp) || contains(medial, cp);
    }

    /**
     * Whether {@code cp} divides a name under this profile into the segments a per-segment restriction level
     * judges one at a time ([TSON-DATA] §8.2's unit): {@code _}, and every character this profile adds that is
     * not {@code XID_Continue} -- {@link #NAME}'s {@code -}, which makes §8.2's {@code _}/{@code -} exactly this
     * profile's answer, and another profile's {@code $} or medial {@code .}. Such a character is the profile's
     * own punctuation, so a script change across it sits between words, where a homograph cannot.
     */
    public boolean separates(int cp) {
        return cp == '_' || (added(cp) && !Xid.isContinue(cp));
    }

    /** Names the offending code point rather than printing it -- much of what this rejects is invisible. */
    private static String at(String text, int cp, int index) {
        return "'%s': U+%04X at index %d".formatted(text, cp, index);
    }
}
