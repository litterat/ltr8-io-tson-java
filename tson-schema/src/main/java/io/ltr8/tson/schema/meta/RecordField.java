package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.tson.parser.ast.TokenValue;

import java.util.Optional;

/**
 * The meta-kernel's {@code record_field} record (Part 2 §5.2, §8.1): {@code name}/{@code type} are
 * REQUIRED; {@code state}, bound through plain generic binding, always appears in written output
 * even at its nominal {@link FieldState#REQUIRED} default. {@code value}/{@code valueParam} are
 * the record_field's own OPTIONAL group -- at most one present, never both. Neither is populated
 * yet: no resolved example so far has a default, fixed, or parameter-routed field.
 */
public record RecordField(String name, TypeRef type, FieldState state,
                           Optional<TokenValue> value, @Field("value_param") Optional<String> valueParam) {

    /** A plain {@code REQUIRED} field with no default/fixed value and no parameter routing. */
    public static RecordField required(String name, TypeRef type) {
        return new RecordField(name, type, FieldState.REQUIRED, Optional.empty(), Optional.empty());
    }
}
