package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;
import io.ltr8.tson.schema.meta.Reference;

/**
 * A {@code reference} core-value ([TSON-DATA] §7.4) that is no bare name, assembled whole
 * by the stream and delivered as one value: a qualified name with an adjacent argument list, {@code geo.address<T>}
 * or {@code map<text list<int32>>}, or a sibling reference, {@code kind!}. A qualified name without arguments is a
 * {@link TokenEvent}, and a reader at a reference-typed position rolls either into one {@link Reference}; at any
 * other typed position this is a type mismatch, which a reader admitting only tokens reports as it reports any
 * other non-token.
 *
 * <p><b>One event, not a pair.</b> An argument is a reference or a token and never a structure, so a reference is
 * small, and assembling it before it is emitted keeps every reader, skip and replay treating it as the one value it
 * is. {@code reference} carries each argument as the grammar classified it: a qualified name a {@code Ref}, any other
 * token a {@code Value} with its token form. A sibling reference is never an argument.
 */
public record ReferenceEvent(Reference reference, Position position) implements TsonEvent {
}
