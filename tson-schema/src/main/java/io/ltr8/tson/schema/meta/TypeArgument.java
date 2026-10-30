package io.ltr8.tson.schema.meta;

/**
 * One positional argument of a {@link TypeRef}, as the reference grammar parses it ([TSON-SCHEMA] §12.1,
 * SPEC-FEEDBACK.md #12): {@code argument = reference / token}, a reference first.
 *
 * <p><b>Modeled as a sealed interface ({@code Ref}/{@code Value}), not a record with two {@code Optional}s.</b> An
 * argument is one of two things and never both, and the type says so.
 *
 * <p><b>The grammar classifies; the head's parameters judge.</b> A token that parses as a reference is a {@link Ref},
 * and a {@link Value} is a token that does not: a number or a quoted token. Whether an argument binds a type or a
 * value is the parameter's, reached only through the head (§5.10), so a resolver that has reached the head may file
 * an unquoted {@code red} at a value parameter as a {@code Value}. That filing is the resolver's and not part of the
 * reference, so <b>equality is by {@link #text}</b>: {@code Ref(red)} and an unquoted {@code Value(red)} are one
 * argument, as {@code names<red>} is one reference whoever read it.
 */
public sealed interface TypeArgument {

    /** The argument's canonical text, as it stands inside a reference's argument list. */
    String text();

    /** An argument that parses as a reference, itself possibly an application. */
    record Ref(TypeRef ref) implements TypeArgument {

        @Override
        public String text() {
            return ref.text();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TypeArgument other && text().equals(other.text());
        }

        @Override
        public int hashCode() {
            return text().hashCode();
        }
    }

    /** A token: a number or a quoted token, or an unquoted one a resolver has judged a value at its parameter. */
    record Value(Token value) implements TypeArgument {

        @Override
        public String text() {
            return value.form() == Token.Form.UNQUOTED ? value.text() : TypeRef.quoted(value.text());
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TypeArgument other && text().equals(other.text());
        }

        @Override
        public int hashCode() {
            return text().hashCode();
        }
    }
}
