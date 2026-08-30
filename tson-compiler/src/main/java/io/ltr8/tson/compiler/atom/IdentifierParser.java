package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.lexer.IdentifierStatus;
import io.ltr8.tson.compiler.lexer.JoiningControls;
import io.ltr8.tson.compiler.lexer.Xid;

import java.text.Normalizer;
import java.util.Optional;

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

    /**
     * The atom-parser path, reached wherever the compiled meta reader reads an {@code identifier}-typed
     * position -- an enum's members, a constructor application's naming slots. {@link #validateName} rather
     * than {@link #validate}, so those positions get [TSON-DATA] §8.2's mechanism 2 like the naming
     * positions the parser and resolver check explicitly: this is a schema-layer caller with nowhere to
     * report a refusal separately, and the alternative is not checking at all.
     */
    @Override
    public String read(TokenValue token) {
        return validateName(token.text());
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
        for (int i = Character.charCount(first); i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!(Xid.isContinue(cp) || cp == '-')) {
                throw new AtomParseException(at(text, cp, i) + " cannot appear in an identifier", EXPECTED);
            }
            if ((cp == Xid.ZWNJ || cp == Xid.ZWJ) && !JoiningControls.permitted(text, i)) {
                throw new AtomParseException(at(text, cp, i) + " is a join control outside the contexts "
                        + "UTS #39 §3.1.1.1 permits -- it has no shaping effect here, so it is invisible",
                        EXPECTED);
            }
            i += Character.charCount(cp);
        }
        return text;
    }

    /**
     * The grammar <b>and</b> [TSON-DATA] §8.2's mechanism 2, for a caller that wants one answer and has
     * nowhere to report a refusal separately -- the schema pipeline, whose naming positions are checked as
     * a declaration is read rather than as a document is.
     *
     * <p><b>It is the wrong shape and it is deliberate here</b>: §8.2 says a mechanism-2 failure is a policy
     * refusal, which MUST NOT be reported in any of §8.1's four categories, and this throws the same
     * exception the grammar does. The read path does it properly -- {@link #hygiene} against a receiver --
     * and doing the same for the schema layer means giving {@code validateSchema} a refusal channel it does
     * not have. Tracked in {@code BACKLOG.md}; this keeps the check running meanwhile rather than dropping
     * it, which is the one outcome worse than misclassifying it.
     */
    public static String validateName(String text) {
        validate(text);
        hygiene(text).ifPresent(violation -> {
            throw new AtomParseException(violation, EXPECTED);
        });
        return text;
    }

    /**
     * [TSON-DATA] §8.2's <b>mechanism 2</b>, alone: every {@code XID_Continue} character of a name must be
     * {@code Identifier_Status=Allowed} (UTS #39 §3.1). Returns the violation rather than throwing, because
     * it is not one -- §8.2 makes this a policy refusal, a fifth outcome that MUST NOT be reported as a
     * validity error, and a caller holding a diagnostics receiver reports it as one.
     *
     * <p>Two characters are the grammar's rather than this mechanism's, though the table restricts both.
     * {@code -} is this profile's own extension, which §8.2 says carries no {@code Identifier_Status} and
     * participates in no mechanism. ZWNJ and ZWJ are {@code Identifier_Status=Restricted} and §7.7 rule 2
     * carves the exception UTS #39 §3.1.1.1 defines, which makes their admission a question of <em>form</em>
     * and so {@link #validate}'s: a joiner outside those contexts is not an identifier at all, where a
     * restricted character is an identifier this processor declines to accept.
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
