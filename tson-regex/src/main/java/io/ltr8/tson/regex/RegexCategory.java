package io.ltr8.tson.regex;

/**
 * The Unicode general categories permitted in an I-Regexp {@code \p{...}}/{@code \P{...}} escape -- exactly
 * the RFC 9485 {@code IsCategory} production: the seven top-level categories and their subcategories, no more.
 * I-Regexp admits no Unicode blocks ({@code \p{IsBasicLatin}}) and no multi-character escapes ({@code
 * \d}/{@code \w}/{@code \s}), so this closed set is the whole vocabulary; each constant's {@link #name()} is
 * the exact, case-sensitive category token. A matcher maps each to the JDK's own Unicode data ({@link
 * Character#getType}); this enum only fixes which tokens are valid.
 */
public enum RegexCategory {
    L, Lu, Ll, Lt, Lm, Lo,
    M, Mn, Mc, Me,
    N, Nd, Nl, No,
    P, Pc, Pd, Ps, Pe, Pi, Pf, Po,
    Z, Zs, Zl, Zp,
    S, Sm, Sc, Sk, So,
    C, Cc, Cf, Cn, Co
}
