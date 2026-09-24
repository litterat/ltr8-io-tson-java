package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code field_role} enum (Part 2 §5.2, §8.1): what a record field's schema value is for.
 * {@link #FREE} is the default, omitted from resolver output, and carries no value; {@link #DEFAULT}'s value
 * is what omission yields and a written value overrides; {@link #FIXED}'s is the only value a written one may
 * be. The role is consulted once by a reader, at a written value, to say whether it may differ from the
 * schema's; what omission yields is {@link RecordField}'s other facts read together, not this.
 */
public enum FieldRole {
    FREE, DEFAULT, FIXED
}
