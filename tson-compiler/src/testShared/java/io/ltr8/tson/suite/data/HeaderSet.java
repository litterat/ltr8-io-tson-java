package io.ltr8.tson.suite.data;

import io.ltr8.annotation.Typename;
import io.ltr8.tson.schema.meta.Data;

import java.util.List;
import java.util.Map;

/**
 * The host class for the corpus's {@code header_set} constructor: methods keyed by {@code text}, whose keys are not
 * names, so a qualified name cannot enter the map.
 */
@Typename(name = "header_set")
public record HeaderSet(Map<String, Method> headers) implements Data {

    @Override
    public List<Named> names() {
        return Method.namesOf("headers", headers);
    }
}
