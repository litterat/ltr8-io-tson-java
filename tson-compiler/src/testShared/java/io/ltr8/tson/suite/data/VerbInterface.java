package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.Map;

/**
 * The host class for the corpus's {@code verb_interface} constructor: methods keyed by an enum's labels
 * ({@code http_verb}), so a qualified name enters the map by a label ({@code api.methods.GET}).
 */
@Typename(name = "verb_interface")
public record VerbInterface(Map<String, Method> methods) implements Data {

}
