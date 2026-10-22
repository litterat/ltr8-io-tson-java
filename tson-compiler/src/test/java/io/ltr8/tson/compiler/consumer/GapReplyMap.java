package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;

import java.util.Map;

/** A DATA body holding a plain map of records, so a key's annotations can sit in front of a judged name. */
@Typename(name = "gap_reply_map")
public record GapReplyMap(Map<String, GapReply> replies) implements Data {
}
