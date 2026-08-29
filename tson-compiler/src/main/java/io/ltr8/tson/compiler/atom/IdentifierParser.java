package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.lexer.IdentifierStatus;
import io.ltr8.tson.compiler.lexer.JoiningControls;
import io.ltr8.tson.compiler.lexer.Xid;

import java.text.Normalizer;

/**
 * Parses meta-kernel's {@code identifier} instance of the {@code unit} atom constructor (§4.2, §8.1) -- the
 * type of every naming position in the series: type names, field names and parameter names through the
 * {@code type_name}/{@code field_name}/{@code param_name} roles, and enum members through {@code enum_set}.
 * One contract, reaching all of them, which is the point of their sharing a type.
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
 * <p><b>Characters must also be {@code Identifier_Status=Allowed}</b> (UTS #39 §3.1) -- a per-character
 * narrowing that removes obsolete, technical and limited-use characters, with no cross-script judgement in
 * it, so a mixed-script name is untouched. {@code -} is exempt, being this profile's own extension rather
 * than an identifier character Unicode has an opinion about.
 *
 * <p><b>The XID profile admits ZWNJ and ZWJ</b>, both being {@code XID_Continue} -- though
 * {@code Identifier_Status} then refuses them, which is UTS #39 stating generally the rule §7.1 states for
 * those two characters by hand. {@link
 * io.ltr8.tson.compiler.lexer.Lexer} currently does not, so no unquoted token can carry one and the case is
 * unreachable through that spelling; this class is written to the property anyway, so it is already correct
 * when the lexer adopts UTS #39 §3.1.1.1's contextual rule ({@code SPEC-FEEDBACK.md} #14). Deciding it here
 * by the property rather than by what the lexer happens to permit is what keeps the two from drifting.
 *
 * <p>NFC is required as a <em>form</em>, not merely as a comparison. §2.5 and §2.6 define name identity by
 * NFC-normalised comparison, which is the harder rule and the easy one to get wrong; requiring the form
 * makes the stored name equal the compared name and reduces duplicate detection to string equality.
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- like {@link TextParser}/{@link EnumParser},
 * never registered in {@link BuiltinTypeVocabulary} and has no {@code TYPENAME} constant. {@code unit} is a
 * Part 2 schema constructor, not a schemaless annotation a Class 1 processor would ever resolve on its own.
 * §4.2 requires an implementation to dispatch {@code value}, {@code identifier} and {@code void} by their
 * declared names, all three resolving to the identical empty body: {@code value} routes through {@link
 * ValueParser}, and {@code void} accepts only the absent sentinel, in {@code reader.VoidReader}.
 */
public final class IdentifierParser implements AtomType<String> {

    public static final IdentifierParser INSTANCE = new IdentifierParser();

    private IdentifierParser() {
    }

    @Override
    public String read(TokenValue token) {
        return validate(token.text());
    }

    /**
     * The profile itself, over a name's decoded text. Separate from {@link #read(TokenValue)} because the form a
     * name was spelled in is no part of the contract -- an identifier is constrained however it was written -- so
     * a caller that already holds the text (the grammar, at each name position; the resolver, for a field name it
     * built) states the check directly rather than wrapping a {@link TokenValue} around the string to get at it.
     * Returns the text, so it composes where a value is wanted.
     */
    public static String validate(String text) {
        if (text.isEmpty()) {
            throw new AtomParseException("an identifier may not be empty", EXPECTED);
        }
        // Before anything else: UTS #39 §3.1.1.1's global conditions include NFC, and JoiningControls is
        // written to that assumption rather than re-checking it per joiner.
        if (!Normalizer.isNormalized(text, Normalizer.Form.NFC)) {
            throw new AtomParseException("'" + text + "' is not NFC-normalized", EXPECTED);
        }
        int first = text.codePointAt(0);
        if (!Xid.isStart(first)) {
            throw new AtomParseException(at(text, first, 0) + " cannot start an identifier"
                    + (Character.isDigit(first) || first == '-' || first == '+' || first == '.'
                            ? " -- an identifier never begins with a digit or a sign" : ""),
                    EXPECTED);
        }
        if (!IdentifierStatus.isAllowed(first)) {
            throw new AtomParseException(at(text, first, 0) + " is Identifier_Status=Restricted (UTS #39)",
                    EXPECTED);
        }
        for (int i = Character.charCount(first); i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!(Xid.isContinue(cp) || cp == '-')) {
                throw new AtomParseException(at(text, cp, i) + " cannot appear in an identifier", EXPECTED);
            }
            if (cp == Xid.ZWNJ || cp == Xid.ZWJ) {
                // Both joiners are Identifier_Status=Restricted, and UTS #39 §3.1.1.1 carves the exception:
                // they are admitted exactly where they have a shaping effect. See JoiningControls.
                if (!JoiningControls.permitted(text, i)) {
                    throw new AtomParseException(at(text, cp, i) + " is a join control outside the contexts "
                            + "UTS #39 §3.1.1.1 permits -- it has no shaping effect here, so it is invisible",
                            EXPECTED);
                }
            } else if (!IdentifierStatus.isAllowed(cp) && cp != '-') {
                throw new AtomParseException(at(text, cp, i) + " is Identifier_Status=Restricted (UTS #39)",
                        EXPECTED);
            }
            i += Character.charCount(cp);
        }
        return text;
    }

    /** The one {@code expected} fragment this parser reports -- a grammar, per {@link AtomTypeException}'s vocabulary. */
    private static final String EXPECTED = "an identifier";

    /** Names the offending code point rather than printing it -- much of what this rejects is invisible. */
    private static String at(String text, int cp, int index) {
        return "'%s': U+%04X at index %d".formatted(text, cp, index);
    }

    @Override
    public String write(String value) {
        return value;
    }
}
