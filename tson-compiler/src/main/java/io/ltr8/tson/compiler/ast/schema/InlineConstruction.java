package io.ltr8.tson.compiler.ast.schema;

import io.ltr8.tson.compiler.ast.DataValue;

import java.util.Objects;

/**
 * A type written where it is used, after desugaring: the construction a sugar form denotes, {@code !meta.array {
 * element_type: text }}, held at the field or group member that wrote it ([TSON-SCHEMA] §5.3). No production
 * parses one; {@code SchemaDesugarer} writes it in place of {@code [text]}, {@code {K => V}}, {@code [A, B]} or
 * {@code ( A | B )} at a record field's type, and a nested form inside it is the binding's own {@code { of: … }}
 * wrapper ({@code meta.type_inline}). {@code DefinitionResolver} reads {@code value} through its constructor's reader
 * into the type the slot holds.
 *
 * @param value the binding record, tagged with its constructor
 */
public record InlineConstruction(DataValue value) implements TypeExpr {

    public InlineConstruction {
        Objects.requireNonNull(value, "value");
        if (value.typeRef().isEmpty()) {
            throw new IllegalArgumentException("a construction written in place is tagged with its constructor");
        }
    }

    /** The constructor the construction applies, the tag on {@link #value}. */
    public String constructor() {
        return value.typeRef().orElseThrow();
    }
}
