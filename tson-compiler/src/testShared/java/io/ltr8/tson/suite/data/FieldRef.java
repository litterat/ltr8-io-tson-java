package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.schema.meta.RecordField;

/**
 * The host class for the corpus's {@code field_ref} record ({@code schemas/fixtures/meta-forms.tn}): a value of the
 * kernel's {@code record_field}, named at a slot typed {@code meta.ref<meta.record_field>}.
 */
public record FieldRef(ValueRef<RecordField> field) {
}
