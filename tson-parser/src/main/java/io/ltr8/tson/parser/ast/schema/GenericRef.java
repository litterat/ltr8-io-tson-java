package io.ltr8.tson.parser.ast.schema;

import java.util.List;

/**
 * {@code type-name "<" type-args ">"} (Part 2 §12.1, §5.3) -- a generic application such as
 * {@code map<text, integer>} or a template application such as {@code vector<pixel, 1920>}.
 * Whether {@code name} resolves to a constructor, a non-constructor template, or is a resolver
 * error is a later, semantic-layer question (§3.3.1, §5.10) -- not decided here.
 */
public record GenericRef(String name, List<TypeArg> args) implements TypeRef {

    public GenericRef {
        args = List.copyOf(args);
    }
}
