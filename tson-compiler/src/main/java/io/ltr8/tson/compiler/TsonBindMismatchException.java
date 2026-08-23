package io.ltr8.tson.compiler;

/**
 * A governing schema and the Java class bound to it disagree about a type's fields, in a way that would
 * lose data on every document of that type.
 *
 * <p><b>Raised where the two meet, not where a document is read.</b> Both halves are fixed by the time a
 * reader is built -- the schema's field set and the class's components -- so a mismatch is knowable when the
 * schema is compiled in bind mode, which is startup for anything that compiles its schemas once. That is the
 * whole value of reporting it: a wiring mistake found there is fixed in minutes, where the same mistake
 * found by its symptom is a field that mysteriously holds its default, three layers into a service.
 *
 * <p><b>It names a misconfiguration, which is why it is neither of the other two.</b> The schema is not
 * wrong ({@code TsonSchemaValidationException}) and no library invariant broke ({@code
 * IllegalStateException}); the schema is fine, the class is fine, and they have been pointed at each other
 * by mistake. It is deliberately not caught into an {@code ErrorReader} the way an ordinary build failure is
 * -- deferring it to first read is exactly the behaviour that makes it expensive.
 *
 * <p>Two shapes reach here, both meaning "this document's value cannot survive the round trip":
 * <ul>
 *   <li>a schema field the class has no component for, where the field is always present (REQUIRED or
 *       REQUIRED_DEFAULT) -- so every document of the type loses it. An <em>optional</em> field is not this:
 *       it is lost only when written, so it is reported at the read that writes it;</li>
 *   <li>a component no schema field fills, which reaches the constructor as {@code null} on every document.
 *       {@code @Unbound} is how a class says a component is its own and not the wire's.</li>
 * </ul>
 * A FIXED field is neither: its value is settled by the schema, so a component for it would hold a constant.
 */
public class TsonBindMismatchException extends RuntimeException {

    public TsonBindMismatchException(String message) {
        super(message);
    }
}
