package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;

/** The same {@code meta.ref<record>} slot bound to a {@code String}. */
@Typename(name = "rh_type_name_text")
public record RhTypeNameText(String request) {
}
