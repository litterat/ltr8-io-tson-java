package io.ltr8.tson.schema.meta;

/**
 * A raw, unresolved scalar literal (§5.2, §5.10: a field modifier's or type-argument's literal
 * value is "a bare token... never annotated, never typed, never a container") -- {@code text} plus
 * which of the three token forms produced it, mirroring {@code tson-parser}'s own {@code
 * TokenValue}/{@code TokenForm} shape exactly (same field name, same enum members) but declared
 * locally here rather than imported: {@code schema.meta} has no dependency on {@code tson-parser}
 * -- {@code tson-parser} depends on {@code tson-schema}, not the reverse (see this repo's own
 * CLAUDE.md) -- so it needs its own, structurally-identical stand-in rather than reusing that type
 * directly. Callers on the {@code tson-parser} side (e.g. its schema resolver) convert their own
 * {@code TokenValue} into this shape field-by-field; there is no shared supertype or conversion
 * method here, since that would reintroduce exactly the dependency this type exists to avoid.
 */
public record Token(String text, Form form) {

    public enum Form {
        UNQUOTED, SINGLE_LINE_QUOTED, MULTI_LINE_QUOTED
    }
}
