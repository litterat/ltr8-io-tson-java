package io.ltr8.tson.compiler.ast;

import java.util.List;

/**
 * {@code array = "[" ws [ data-value *( separator data-value ) ] ws "]"} (§2.7, §7.4).
 *
 * <p>Unlike {@code {}}, {@code []} is unambiguously an empty array directly from the grammar
 * (the whole element sequence is optional) — no brace-disambiguation step is needed (§2.8
 * applies only to {@code {}}).
 */
public record ArrayValue(List<DataValue> elements) implements CoreValue {

    public ArrayValue {
        elements = List.copyOf(elements);
    }
}
