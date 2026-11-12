package io.ltr8.tson.consumer;

import io.ltr8.tson.schema.meta.ValueRef;
import io.ltr8.tson.schema.meta.RecordField;

/** A consumer's {@code pick} record: a record field named at a slot typed {@code meta.ref<meta.record_field>}. */
public record Pick(ValueRef<RecordField> field) {
}
