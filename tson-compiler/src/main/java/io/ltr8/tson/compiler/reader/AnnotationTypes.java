package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonValueReader;

import java.util.Optional;

/**
 * The annotation vocabulary in scope for a read -- what turns an annotation's name into the compiled reader
 * for the type it names. [TSON-SCHEMA] §6: "an annotation {@code @T} (or {@code @T:value}) names a type
 * {@code T} and attaches it as metadata to the surrounding value", resolved one hop against the governing
 * target's namespace (§3.3.3) and with its value validated against {@code T}'s contract.
 *
 * <p>For a data document that governing target is the {@code !!schema} target -- which is exactly the schema
 * the surrounding compiled readers were built from, so the reader is reachable by name with no second
 * compilation. {@link #of} is the schema-driven case; {@link #UNVALIDATED} is the schemaless one, where there
 * is no governing schema and an annotation is kept as authored without any claim about its type.
 *
 * <p>The distinction between "no schema in scope" and "schema in scope but no such type" matters and is why
 * {@link #validating()} exists separately from an empty {@link #readerFor}: the first is not a defect (a
 * Class 1 read preserves annotations without validating them, [TSON-DATA] §3.1), the second is an unresolved
 * reference worth a diagnostic.
 */
record AnnotationTypes(Optional<ValueReaderContext> scope) {

    /** No governing schema: every annotation is preserved as authored, none is resolved or checked. */
    static final AnnotationTypes UNVALIDATED = new AnnotationTypes(Optional.empty());

    /** The vocabulary a compiled schema offers -- its own entries, which already include everything it imports. */
    static AnnotationTypes of(ValueReaderContext context) {
        return new AnnotationTypes(Optional.of(context));
    }

    /** Whether a governing schema is in scope at all, and so whether an unresolvable name is a defect. */
    boolean validating() {
        return scope.isPresent();
    }

    /**
     * The compiled reader for the type {@code name} names, or empty when nothing declares it (or when
     * nothing is in scope to declare it). Gated on the schema's own entries because the compiler's resolver
     * throws for a name it has no entry for, and an author's typo is a diagnostic, not an exception.
     */
    Optional<TsonValueReader<?>> readerFor(String name) {
        return scope.filter(context -> context.schema().entries().containsKey(name))
                .map(context -> context.readers().resolve(name));
    }
}
