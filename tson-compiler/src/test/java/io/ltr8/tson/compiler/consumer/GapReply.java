package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.TypeRef;

/** A record value inside {@link GapOperation}: its {@code body} is a kind-typed slot one level down. */
@Typename(name = "gap_reply")
public record GapReply(int status, TypeRef body) {
}
