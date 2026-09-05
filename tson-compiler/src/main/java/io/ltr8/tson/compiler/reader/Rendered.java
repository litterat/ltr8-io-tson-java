package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.tree.TsonAtom;

import java.util.HexFormat;

/**
 * A decoded value as a diagnostic states it.
 *
 * <p>Only one host type needs the help. {@code byte[]} inherits {@code Object.toString}, so a diagnostic
 * interpolating one says {@code [B@6d06d69c} -- an identity hash, which names nothing an author can act on
 * and differs between two runs over the same document. Every other host type this library produces already
 * prints as its value, {@link TsonAtom} included, whose own {@code toString} renders the value alone for
 * exactly this reason -- and which is unwrapped here because that is the one value it cannot improve.
 *
 * <p>Hex, and not the alphabet the position was written in: the value is octets and the alphabet is a
 * spelling the position's type selects ({@code bytes_type.encoding}), so the one rendering that is a
 * function of the value alone is the one that does not have to be looked up. It is deliberately not a TSON literal -- a diagnostic quotes what it
 * compared, and the document already says how the author spelled it.
 */
final class Rendered {

    private Rendered() {
    }

    private static final HexFormat HEX = HexFormat.of();

    /** {@code value} as a diagnostic states it. */
    static String value(Object value) {
        return switch (value) {
            case byte[] octets -> HEX.formatHex(octets) + " (hex)";
            case TsonAtom atom -> value(atom.value());
            case null, default -> String.valueOf(value);
        };
    }
}
