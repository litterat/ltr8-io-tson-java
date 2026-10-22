package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;

import java.util.Map;

/** A DATA body holding a map of endpoints, each value a tagged subtype of the map's declared value type. */
@Typename(name = "gap_api")
public record GapApi(Map<String, GapEndpoint> endpoints) implements Data {
}
