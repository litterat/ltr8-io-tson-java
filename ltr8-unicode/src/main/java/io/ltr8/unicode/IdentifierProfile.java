package io.ltr8.unicode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * A UAX #31 identifier profile: which strings are identifiers under one choice of sets and normalization form.
 * A format names with one profile and may let its users declare others -- an API's operation names, a database's
 * column names.
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
 * <p>Everything else follows from the base rather than from a clause of its own -- whitespace, C0/C1 controls,
 * {@code Cf} format characters, emoji and unassigned code points are none of them {@code XID_Continue}.
 *
 * <p><b>Both bases admit ZWNJ and ZWJ</b>, both being {@code XID_Continue} and {@code ID_Continue}.
 * {@code Identifier_Status} refuses both, and every profile carves the exception back through UTS #39
 * §3.1.1.1's contexts ({@link JoiningControls}), which admit a joiner where it has a shaping effect and refuse it
 * where it is invisible: an invisible joiner is as much a spoofing surface in one system's names as in another's.
 *
 * <p><b>The profile judges text already in its {@link Normalization} form.</b> {@link #check} refuses text not
 * in the form rather than normalising it, so a caller that holds a value puts it into the form first, the stored
 * name equals the compared name, and duplicate detection is string equality.
 *
 * <p><b>Every check reports a violation rather than throwing one</b>, and returns {@link Optional#empty()}
 * when there is none. That matters twice over. It lets the same check serve a caller that owes a parse error
 * and one that owes a diagnostic, where a signature that threw would force one answer on every caller. And it
 * keeps the check off the allocation budget: {@code Optional.empty()} is a singleton, the sets are built once
 * with the profile, and only the failure path -- which is already building a message -- allocates at all.
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
     * The restricted-character rule over a name this profile admits -- the violation, or empty.
     *
     * <p>Every character of the name must be {@code Identifier_Status=Allowed} (UTS #39 §3.1), but some are the
     * grammar's rather than this rule's, though the table may restrict them. A character this profile adds --
     * {@code start_add}, {@code continue_add} or {@code medial} -- is the profile's own extension: a profile that
     * admits {@code $} has decided {@code $} belongs in its names. ZWNJ and ZWJ are
     * {@code Identifier_Status=Restricted}, and UTS #39 §3.1.1.1's contexts make their admission a question of
     * <em>form</em> and so {@link #check}'s: a joiner outside those contexts is not an identifier at all, where a
     * restricted character makes an identifier a caller may decline.
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
     * ({@link RestrictionLevel}) judges one at a time: {@code _}, and every character this profile adds that is
     * not {@code XID_Continue} -- a {@code -} added to Continue, a {@code $}, a medial {@code .}. Such a character
     * is the profile's own punctuation, so a script change across it sits between words, where a homograph
     * cannot.
     */
    public boolean separates(int cp) {
        return cp == '_' || (added(cp) && !Xid.isContinue(cp));
    }

    /** Names the offending code point rather than printing it -- much of what this rejects is invisible. */
    private static String at(String text, int cp, int index) {
        return "'%s': U+%04X at index %d".formatted(text, cp, index);
    }
}
