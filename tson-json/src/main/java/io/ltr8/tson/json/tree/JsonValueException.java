package io.ltr8.tson.json.tree;

/**
 * A navigation or conversion this value cannot answer: a member of a value that is not an object, an
 * index of one that is not an array, a member or index that is not there, or a host conversion the
 * value does not support or cannot represent exactly.
 *
 * <p><b>A navigation failure, never a read failure.</b> It belongs to the tree the way {@code
 * TsonMissing} belongs to {@code TsonValue} -- raised by a consumer asking this value a question it cannot
 * answer, long after any document was read. What a <em>read</em> raises is the TSON side's answer and not
 * this: {@code ReadException} carrying a {@code Diagnostic}. That is why naming this one after JEP 540 is
 * consistent with the alignment stopping at the tree package rather than an exception to it.
 *
 * <p>Unchecked, and named for JEP 540's exception of the same name and role. The throwing accessors
 * ({@link JsonValue#get}, {@link JsonValue#asInt} and the rest) are the ergonomic path; every one has a
 * non-throwing peer ({@link JsonValue#tryGet}, {@link JsonValue#tryValue}) for a caller reading a
 * document whose shape is not yet established.
 *
 * <p><b>Deliberately opposite to {@code TsonValue}</b>, whose {@code get}/{@code at} never throw and
 * return a {@code TsonMissing} carrying the pointer of the step that failed. Two models for two
 * audiences, and the reason to differ is the reason to align at all: a consumer moving between this
 * {@code JsonValue} and the JDK's must not find one method name with opposite semantics on each side.
 */
public final class JsonValueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonValueException(String message) {
        super(message);
    }
}
