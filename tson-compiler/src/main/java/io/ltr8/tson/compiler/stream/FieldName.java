package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

/** One record field's name (§2.5, §7.4): announces the field; its scoped-value events follow immediately. */
public record FieldName(String name, Position position) implements TsonEvent {
}
