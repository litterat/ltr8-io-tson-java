package io.ltr8.tson.base;

/**
 * A schema and a Java class bound to it cannot work together -- the two disagree about a type's fields in a
 * way that would lose data on every document of that type, or a class breaks a contract the schema layer
 * relies on.
 *
 * <p><b>Raised where the two meet, not where a document is read.</b> Both halves are fixed by the time a
 * reader is built -- the schema's field set and the class's components -- so a mismatch is knowable when the
 * schema is compiled in bind mode, which is startup for anything that compiles its schemas once. That is the
 * whole value of reporting it: a wiring mistake found there is fixed in minutes, where the same mistake
 * found by its symptom is a field that mysteriously holds its default, three layers into a service.
 *
 * <p><b>It names a misconfiguration, which is why it is neither of the other two.</b> The schema is not
 * wrong ({@code SchemaValidationException}) and no library invariant broke ({@code
 * IllegalStateException}); the schema is fine, the class is fine, and they have been pointed at each other
 * by mistake. It is deliberately not caught into an {@code ErrorReader} the way an ordinary build failure is
 * -- deferring it to first read is exactly the behaviour that makes it expensive.
 *
 * <p>The two field-set shapes, both meaning "this document's value cannot survive the round trip":
 * <ul>
 *   <li>a schema field the class has no component for, whatever its state. An <em>optional</em> field is
 *       refused here too, though nothing is lost until a document writes one: it is the field that works in
 *       development and fails the first time a caller sends it, so it is reported when the schema compiles,
 *       not at that read;</li>
 *   <li>a component no schema field fills, which reaches the constructor as {@code null} on every document.
 *       {@code @Unbound} is how a class says a component is its own and not the wire's.</li>
 * </ul>
 * A FIXED field is neither: its value is settled by the schema, so a component for it would hold a constant.
 */
public class BindMismatchException extends RuntimeException {

    public BindMismatchException(String message) {
        super(message);
    }

    /**
     * The same, keeping what the bind engine said. A mismatch reported without its cause names the type and
     * nothing about the class -- and the account of <em>which</em> component could not be bound is the half a
     * caller can act on.
     */
    public BindMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
