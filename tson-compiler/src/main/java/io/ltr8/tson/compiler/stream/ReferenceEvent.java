package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.atom.ReferenceGrammar;

/**
 * A {@code reference} core-value ([TSON-DATA] §7.4) that is no bare name, assembled whole
 * by the stream and delivered as one value: a qualified name with an adjacent argument list, {@code geo.address<T>}
 * or {@code map<text list<int32>>}. A qualified name without arguments is a
 * {@link TokenEvent}. A reference is a name ([TSON-SCHEMA] §4.2), so a reader at a reference-typed position refuses
 * this, as a reader admitting only tokens refuses any other non-token.
 *
 * <p><b>One event, not a pair.</b> An argument is a reference or a token and never a structure, so a reference is
 * small, and assembling it before it is emitted keeps every reader, skip and replay treating it as the one value it
 * is. {@code reference} carries the head and each argument as the grammar spelled them.
 */
public record ReferenceEvent(ReferenceGrammar.Spelled reference, Position position) implements TsonEvent {
}
