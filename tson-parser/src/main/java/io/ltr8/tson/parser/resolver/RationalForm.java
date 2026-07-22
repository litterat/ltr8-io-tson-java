package io.ltr8.tson.parser.resolver;

import java.util.Optional;

/**
 * {@code rational = [sign] decimal-natural "/" denominator} (§7.6) -- an extended form, like {@link
 * NumberGrammar#isHexFloat}, recognised only through the built-in vocabulary's {@code rational}
 * atom, never part of {@link NumberGrammar#tryParse}'s base {@code number} production. Unlike
 * hex-float, this one *is* decomposed into a structural record (mirroring {@link NumberForm}):
 * there's no JDK parser for "numerator/denominator" text to delegate to the way {@code
 * Double.parseDouble} covers hex-float, so the numerator and denominator need to survive as
 * separate raw digit strings for a caller to convert to an exact {@link java.math.BigInteger} pair.
 * {@code denominator}'s grammar ({@code nonzero-digit *( ["_"] DIGIT )}) never permits a leading
 * zero, unlike the numerator's {@code decimal-natural} (which allows the single digit {@code "0"}) --
 * denominators are always positive by construction, matching core.tn1's own description ("positive
 * nonzero denominator by grammar").
 */
public record RationalForm(Optional<NumberForm.Sign> sign, String numerator, String denominator) {
}
