package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;

import java.util.Map;

/** A record holding the family's values in a plain map field. */
@Typename(name = "sb_resource")
public record SbResource(Map<String, SbEndpoint> endpoints) {
}
