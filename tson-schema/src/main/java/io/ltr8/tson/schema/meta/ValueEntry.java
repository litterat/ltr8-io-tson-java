package io.ltr8.tson.schema.meta;

import java.util.Objects;

/**
 * An entry whose value is an instance of an ordinary type ([TSON-SCHEMA] §4.1): {@code orders => !api { ... }}, a
 * value of the namespace type {@code api}, or {@code create_order => !operation { ... }}, a value of the record type
 * {@code operation}. An entry is a type exactly when its value is built by a constructor IS-A {@code type}; this one
 * is no type, nothing is typed by it, and no kind holds it. A reference names it ({@code ref<api>}), and a qualified
 * name walks into it, by key through a namespace value and by field through a record value. The instance of a
 * constructor whose instances are no types, one composing {@code top} and not {@code type} ({@code s => !step { ...
 * }} over {@code step => top & { ... }}), is held the same way, the schema model having no body for it.
 *
 * <p>{@link #type} is the type the entry's tag names, which resolved output writes as that tag. {@link #value} is
 * what reading the payload against it produced: a {@link Namespace} for a namespace type, an object of the class the
 * bind context binds the type to, or, where no class binds it, the value as a tree ({@code io.ltr8.tson.tree}), so a
 * schema holding it loads and validates without host classes.
 *
 * @param type  the type the entry's value is an instance of, as its tag names it
 * @param value the value, bound or held as a tree
 */
public record ValueEntry(String type, Object value) implements Top {

    public ValueEntry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
    }
}
