package io.ltr8.tson.compiler.consumer;

import io.ltr8.annotation.Typename;

/** The same {@code ref<rh_method>} slot bound to a {@code String}. */
@Typename(name = "rh_ref_text")
public record RhRefText(String method) {
}
