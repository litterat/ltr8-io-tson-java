package io.ltr8.tson.regex;

/**
 * Tests a code point's membership in an I-Regexp {@link RegexCategory}, leaning on the JDK's own Unicode
 * data ({@link Character#getType(int)}) rather than a table built from scratch -- the "own the rules, borrow
 * the JDK tables" split the lexer already makes for XID. A two-letter category (e.g. {@code Nd}) is an exact
 * general-category match; a one-letter category (e.g. {@code N}) matches any code point whose general
 * category falls under that top-level letter.
 */
final class UnicodeCategories {

    private UnicodeCategories() {
    }

    static boolean matches(RegexCategory category, int codePoint) {
        int type = Character.getType(codePoint);
        int exact = exactType(category);
        return exact >= 0 ? type == exact : groupLetter(type) == category.name().charAt(0);
    }

    /** The exact {@link Character} general-category constant for a two-letter category, or -1 for a one-letter group. */
    private static int exactType(RegexCategory category) {
        return switch (category) {
            case L, M, N, P, Z, S, C -> -1;
            case Lu -> Character.UPPERCASE_LETTER;
            case Ll -> Character.LOWERCASE_LETTER;
            case Lt -> Character.TITLECASE_LETTER;
            case Lm -> Character.MODIFIER_LETTER;
            case Lo -> Character.OTHER_LETTER;
            case Mn -> Character.NON_SPACING_MARK;
            case Mc -> Character.COMBINING_SPACING_MARK;
            case Me -> Character.ENCLOSING_MARK;
            case Nd -> Character.DECIMAL_DIGIT_NUMBER;
            case Nl -> Character.LETTER_NUMBER;
            case No -> Character.OTHER_NUMBER;
            case Pc -> Character.CONNECTOR_PUNCTUATION;
            case Pd -> Character.DASH_PUNCTUATION;
            case Ps -> Character.START_PUNCTUATION;
            case Pe -> Character.END_PUNCTUATION;
            case Pi -> Character.INITIAL_QUOTE_PUNCTUATION;
            case Pf -> Character.FINAL_QUOTE_PUNCTUATION;
            case Po -> Character.OTHER_PUNCTUATION;
            case Zs -> Character.SPACE_SEPARATOR;
            case Zl -> Character.LINE_SEPARATOR;
            case Zp -> Character.PARAGRAPH_SEPARATOR;
            case Sm -> Character.MATH_SYMBOL;
            case Sc -> Character.CURRENCY_SYMBOL;
            case Sk -> Character.MODIFIER_SYMBOL;
            case So -> Character.OTHER_SYMBOL;
            case Cc -> Character.CONTROL;
            case Cf -> Character.FORMAT;
            case Co -> Character.PRIVATE_USE;
            case Cn -> Character.UNASSIGNED;
        };
    }

    /** The top-level category letter for a {@link Character} general-category type. Surrogate/reserved fall under {@code C}. */
    private static char groupLetter(int type) {
        return switch (type) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                 Character.MODIFIER_LETTER, Character.OTHER_LETTER -> 'L';
            case Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> 'M';
            case Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> 'N';
            case Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> 'Z';
            case Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.CONNECTOR_PUNCTUATION, Character.OTHER_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION -> 'P';
            case Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
                 Character.OTHER_SYMBOL -> 'S';
            default -> 'C'; // CONTROL / FORMAT / PRIVATE_USE / SURROGATE / UNASSIGNED / reserved
        };
    }
}
