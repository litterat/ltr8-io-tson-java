package io.ltr8.tson.consumer;

import io.ltr8.tson.schema.meta.Ref;
import io.ltr8.tson.schema.meta.RecordField;

/** A consumer's {@code pick} record: a record field named at a slot typed {@code ref<record_field>}. */
public record Pick(Ref<RecordField> field) {
}
