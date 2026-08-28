package io.ltr8.tson.compiler.lexer;

import java.util.Arrays;

/**
 * UAX #31's {@code XID_Start} and {@code XID_Continue}, exactly, over the running JDK's character data.
 * Shared by the two layers that need them: {@link Lexer}'s unquoted-token profile ([TSON-DATA] §7.1) and the
 * identifier profile the meta-kernel's {@code identifier} type carries. Neither is XID alone -- each adds
 * and removes its own characters -- so what is shared is the property, not either profile.
 *
 * <p><b>The JDK's own identifier predicates are not these properties</b>, which is the reason this class
 * exists rather than a pair of one-line calls. {@link Character#isUnicodeIdentifierStart}/{@code Part} are
 * {@code ID_Start}/{@code ID_Continue}, and the {@code Part} half is additionally unioned with everything
 * {@link Character#isIdentifierIgnorable} covers -- all of {@code Cf} plus the non-whitespace C0/C1
 * controls, none of which is in {@code XID_Continue}. Standing them in unmodified put a byte-order mark, a
 * soft hyphen, a raw control and every bidi override inside identifiers, and every ASCII test still passed.
 *
 * <p>Subtracting the ignorable set and the two NFKC-exclusion tables leaves the properties exactly: verified
 * against {@code DerivedCoreProperties.txt} for {@link #UNICODE_VERSION} over all 1,112,064 non-surrogate
 * code points, zero over- and zero under-acceptance on both.
 */
public final class Xid {

    private Xid() {
    }

    /**
     * The Unicode version whose properties this implements -- [TSON-DATA] §7.1 asks an implementation to
     * document it. It is the version the running JDK's character data carries, since {@link #NOT_XID_START}
     * and {@link #NOT_XID_CONTINUE} are derived against that data; a JDK whose Unicode version moves needs
     * both tables re-derived (see their Javadoc for how).
     */
    public static final String UNICODE_VERSION = "16.0";

    /**
     * {@code XID_Start}, exactly. {@link Character#isUnicodeIdentifierStart} is {@code ID_Start}, which
     * differs only by the characters XID drops for not being closed under NFKC -- {@link #NOT_XID_START},
     * 24 of them for Unicode 16.0, and nothing in the other direction.
     */
    public static boolean isStart(int cp) {
        return Character.isUnicodeIdentifierStart(cp) && !excluded(NOT_XID_START, cp);
    }

    /**
     * {@code XID_Continue}, exactly -- <b>including U+200C and U+200D</b>, which are members of the property
     * (Unicode 16.0 {@code DerivedCoreProperties.txt}) because UAX #31 folded them into the default when it
     * removed requirement R1a. Whether a given profile then admits them is that profile's decision, not this
     * one's: [TSON-DATA] §7.1's prose excludes them where its own set algebra does not, which
     * {@code SPEC-FEEDBACK.md} #14 carries, and {@link Lexer} subtracts them for that reason.
     */
    public static boolean isContinue(int cp) {
        return (Character.isUnicodeIdentifierPart(cp) && !Character.isIdentifierIgnorable(cp)
                && !excluded(NOT_XID_CONTINUE, cp))
                || cp == ZWNJ || cp == ZWJ;
    }

    /** U+200C ZERO WIDTH NON-JOINER -- {@code XID_Continue}, and re-added by {@link #isContinue} because the ignorable subtraction removes it. */
    public static final int ZWNJ = 0x200C;

    /** U+200D ZERO WIDTH JOINER -- see {@link #ZWNJ}. */
    public static final int ZWJ = 0x200D;

    /** Sorted, so a lookup is a binary search -- guarded by the lowest member, since every exclusion is above U+0379 and the code points that matter in practice are not. */
    private static boolean excluded(int[] table, int cp) {
        return cp >= table[0] && Arrays.binarySearch(table, cp) >= 0;
    }

    /**
     * {@code ID_Start \ XID_Start} for Unicode 16.0: characters XID drops because their NFKC form is not
     * itself an identifier. Re-derive from {@code DerivedCoreProperties.txt} against
     * {@link Character#isUnicodeIdentifierStart} if the JDK's Unicode version moves.
     */
    private static final int[] NOT_XID_START = {
        0x037A, 0x0E33, 0x0EB3, 0x2E2F, 0x309B, 0x309C,
        0xFC5E, 0xFC5F, 0xFC60, 0xFC61, 0xFC62, 0xFC63, 0xFDFA, 0xFDFB,
        0xFE70, 0xFE72, 0xFE74, 0xFE76, 0xFE78, 0xFE7A, 0xFE7C, 0xFE7E,
        0xFF9E, 0xFF9F,
    };

    /** {@code ID_Continue \ XID_Continue} for Unicode 16.0 -- {@link #NOT_XID_START} without the four that are continue-only. */
    private static final int[] NOT_XID_CONTINUE = {
        0x037A, 0x2E2F, 0x309B, 0x309C,
        0xFC5E, 0xFC5F, 0xFC60, 0xFC61, 0xFC62, 0xFC63, 0xFDFA, 0xFDFB,
        0xFE70, 0xFE72, 0xFE74, 0xFE76, 0xFE78, 0xFE7A, 0xFE7C, 0xFE7E,
    };
}
