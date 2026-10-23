package io.ltr8.tson.consumer;

import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.RecordField;

/**
 * A consumer's {@code shape} record, holding a record's fields in a field typed by the kernel's {@code field_map}: each
 * value there is a {@link RecordField}, the library's own class, which the consumer supplies nothing for.
 */
public record Shape(Namespace<RecordField> fields) {
}
