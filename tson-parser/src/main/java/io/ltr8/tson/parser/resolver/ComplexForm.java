package io.ltr8.tson.parser.resolver;

import java.util.Optional;

/**
 * {@code complex = [sign] magnitude sign magnitude imag-unit / [sign] magnitude imag-unit} (§7.6)
 * -- an extended form, recognised only through the built-in vocabulary's {@code complex} atom.
 * {@code realMagnitude} is absent for the second (imaginary-only) alternative, e.g. {@code 4i} or
 * {@code -2.5j}, where the real part is implicitly zero; when present, it's always paired with the
 * first alternative's mandatory (never optional, unlike every other sign in this grammar) separator
 * sign before the imaginary part -- {@code 3 4i} (space, no explicit {@code +}/{@code -} between the
 * parts) does not match the grammar at all. {@code magnitude} substrings are raw and unsigned (§7.6
 * gives {@code magnitude} no sign of its own): each one happens to be exactly what {@link
 * NumberGrammar#tryParse} already recognizes as an {@code integer} or {@code float} shape (a
 * bare natural number, or anything a decimal float allows), so a caller decomposes them by
 * re-parsing rather than this record extracting further structure itself.
 */
public record ComplexForm(
        Optional<NumberForm.Sign> realSign,
        Optional<String> realMagnitude,
        Optional<NumberForm.Sign> imaginarySign,
        String imaginaryMagnitude) {
}
