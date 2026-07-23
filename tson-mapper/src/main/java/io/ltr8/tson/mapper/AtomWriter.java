package io.ltr8.tson.mapper;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.parser.TsonWriter;
import io.ltr8.tson.parser.resolver.vocab.FloatParser;

/**
 * The write-side counterpart to {@link AtomBinder}, for values that were never bound through the
 * built-in vocabulary at all (§4's default resolution, not §5) -- boolean/number/string/null.
 * Formatting a *vocabulary* atom's value is each atom's own job now ({@code
 * io.ltr8.tson.parser.resolver.vocab.AtomType#write}), looked up through {@code
 * TsonMapper}'s own registry rather than duplicated here; see {@code TsonMapper.toTson}'s Javadoc.
 * {@code Double}/{@code Float} are the one default-resolvable case that still delegates to a
 * vocabulary type ({@link FloatParser}) purely to reuse its {@code .nan}/{@code +.inf}/{@code -.inf}
 * special-value formatting rather than duplicating it a second time -- §4.3's default number
 * resolution already recognises that spelling (§7.6's {@code special-value} form isn't vocabulary-
 * only the way {@code hex-float}/{@code rational}/{@code complex} are), so no type-ref is emitted
 * for it here, unlike every other use of {@code FloatParser}.
 */
final class AtomWriter {

    private AtomWriter() {
    }

    static void writeDefaultAtom(Object value, TsonWriter writer) throws DataBindException {
        switch (value) {
            case Boolean b -> writer.booleanValue(b);
            case Double d -> writer.unquotedToken(FloatParser.FLOAT64.write(d));
            case Float f -> writer.unquotedToken(FloatParser.FLOAT32.write(f));
            case Number n -> writer.unquotedToken(n.toString());
            case String s -> writer.quotedString(s);
            case Character c -> writer.quotedString(c.toString());
            default -> throw new DataBindException("don't know how to write a value of type " + value.getClass());
        }
    }
}
