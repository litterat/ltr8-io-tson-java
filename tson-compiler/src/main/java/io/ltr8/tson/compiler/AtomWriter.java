package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.compiler.atom.FloatParser;

/**
 * The write-side counterpart to {@link AtomBinder}: <b>framing a value that carries no type-ref of its
 * own</b>. Mostly that is §4's default resolution -- boolean/number/string/null -- where the framing follows
 * from the host type, since that is all such a value has to go on.
 * Formatting a *vocabulary* atom's value is each atom's own job now ({@code
 * io.ltr8.tson.compiler.atom.AtomType#write}), looked up through {@code
 * TsonObjectWriter}'s own registry rather than duplicated here; see {@code
 * TsonObjectWriter.toTson}'s Javadoc. {@code Double}/{@code Float} are the one default-resolvable
 * case that still delegates to a vocabulary type ({@link FloatParser}) purely to reuse its {@code
 * .nan}/{@code +.inf}/{@code -.inf} special-value formatting rather than duplicating it a second
 * time -- §4.3's default number resolution already recognises that spelling (§7.6's {@code
 * special-value} form isn't vocabulary-only the way {@code hex-float}/{@code rational}/{@code
 * complex} are), so no type-ref is emitted for it here, unlike every other use of {@code
 * FloatParser}.
 */
final class AtomWriter {

    private AtomWriter() {
    }

    static void writeDefaultAtom(Object value, TsonDataEmitter writer) throws DataBindException {
        switch (value) {
            case Boolean b -> writer.booleanValue(b);
            case Double d -> writer.unquotedToken(FloatParser.FLOAT64.write(d));
            case Float f -> writer.unquotedToken(FloatParser.FLOAT32.write(f));
            case Number n -> writer.unquotedToken(n.toString());
            case String s -> writer.quotedString(s);
            case Character c -> writer.quotedString(c.toString());
            // The one value that knows its own framing rather than having it inferred: a Token records the
            // form it was written in, and at a `value`-typed slot that form is part of the value -- `3` and
            // `"3"` are different values there ([TSON-DATA] §4 resolves them to a number and a string). Every
            // other case here reads its framing off the host type because it has nothing better.
            case io.ltr8.tson.schema.meta.Token t -> {
                switch (t.form()) {
                    case UNQUOTED -> writer.unquotedToken(t.text());
                    case SINGLE_LINE_QUOTED -> writer.quotedString(t.text());
                    case MULTI_LINE_QUOTED -> writer.multiLineString(t.text());
                }
            }
            default -> throw new DataBindException("don't know how to write a value of type " + value.getClass());
        }
    }
}
