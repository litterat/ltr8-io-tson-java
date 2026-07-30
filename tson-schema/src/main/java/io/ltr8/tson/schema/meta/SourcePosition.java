package io.ltr8.tson.schema.meta;

/**
 * Where in some original source text a {@link TypeDefinition} (or other resolved value) was
 * declared -- line, column, and byte offset, mirroring {@code tson-compiler}'s own {@code Position}
 * shape exactly. Declared here, as an interface rather than a value class, specifically so {@code
 * tson-compiler}'s own {@code Position} can implement it directly: {@code schema.meta} has no
 * dependency on {@code tson-compiler} (see this repo's own CLAUDE.md) -- {@code tson-compiler}
 * depends on {@code tson-schema}, not the reverse -- so this type can't simply reuse {@code Position}
 * itself, but the depended-on module is free to declare an interface its own dependent later
 * satisfies. Unlike {@link Token}, no field-by-field conversion is needed anywhere: once {@code
 * Position} implements this interface, an existing {@code Position} instance already *is* a {@code
 * SourcePosition}.
 */
public interface SourcePosition {
    int line();

    int column();

    int byteOffset();
}
