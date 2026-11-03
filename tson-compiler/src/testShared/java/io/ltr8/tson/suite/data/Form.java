package io.ltr8.tson.suite.data;

import io.ltr8.tson.schema.meta.RecordField;

import java.util.SequencedMap;

/**
 * The host class for the corpus's {@code form} record ({@code schemas/fixtures/meta-forms.tn}): a record's fields, at
 * a slot typed by the kernel's {@code meta.field_map}, an ordered map keyed by text.
 */
public record Form(SequencedMap<String, RecordField> fields) {
}
