package io.ltr8.tson.compiler.ast.schema;

/**
 * {@code record-entry = field-def / group-def} (Part 2 §12.1) -- one entry inside a {@link
 * RecordDef}'s braces.
 */
public sealed interface RecordEntry permits FieldDef, GroupDef {
}
