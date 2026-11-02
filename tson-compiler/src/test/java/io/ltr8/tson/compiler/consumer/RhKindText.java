package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;

/** The same {@code meta.kind<record>} slot bound to a {@code String}. */
@Typename(name = "rh_kind_text")
public record RhKindText(String request) {
}
