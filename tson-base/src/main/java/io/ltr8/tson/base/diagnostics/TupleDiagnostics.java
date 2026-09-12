package io.ltr8.tson.base.diagnostics;

import io.ltr8.tson.base.Diagnostic;

/**
 * What a tuple's rules say when a document breaks one -- stated once, for every encoding. See
 * {@link RecordDiagnostics} for why one place, and why the prose is the schema's vernacular.
 *
 * <p><b>A tuple's arity is part of its type</b> ([TSON-SCHEMA] §5.3), which is what separates these rules from
 * an array's: a short or long tuple is wrong regardless of trailing-optional positions, because an optional
 * position means the slot may hold no value and never that the slot may be missing.
 *
 * @param typeName  the tuple type being read, as the author wrote it
 * @param positions how many positions it declares
 */
public record TupleDiagnostics(String typeName, int positions) {

    /** The value is not array-shaped at all -- {@code found} is the encoding's description of what was there. */
    public Refusal notATuple(String found) {
        return new Refusal(Diagnostic.Code.TYPE_MISMATCH,
                "expected a tuple (array-shaped) for '%s', found %s".formatted(typeName, found),
                "a tuple (array-shaped)", found);
    }

    /** Fewer elements than positions. */
    public Refusal tooFewElements(int found) {
        return new Refusal(Diagnostic.Code.WRONG_ARITY,
                "'%s' has %d positions, found only %d elements".formatted(typeName, positions, found),
                positions + " elements", String.valueOf(found));
    }

    /**
     * More elements than positions. The count is stated as a bound rather than exactly, because a reader that
     * stops at the overflow has not counted the rest and should not imply it has.
     */
    public Refusal tooManyElements() {
        return new Refusal(Diagnostic.Code.WRONG_ARITY,
                "'%s' has %d positions, found more than %d elements".formatted(typeName, positions, positions),
                positions + " elements", "more than " + positions);
    }

    /**
     * Absence at a position the schema does not admit one at ([TSON-SCHEMA] §7.6). {@code spelling} is how the
     * document said it and rides in {@code actual}, the rule being about the position's state.
     */
    public Refusal absentPosition(int index, String spelling) {
        return new Refusal(Diagnostic.Code.FIELD_REQUIRED,
                "'%s' position [%d] is absent, but this position is required".formatted(typeName, index),
                "a value", spelling);
    }
}
