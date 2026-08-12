package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.schema.TsonSchema;

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
record AnnotationTypes(boolean capture, Optional<TsonSchema> schema, TsonValueReaderResolver readers) {

    /** No governing schema: every annotation is preserved as authored, none is resolved or checked. */
    static final AnnotationTypes UNVALIDATED = new AnnotationTypes(true, Optional.empty(), name -> null);

    /**
     * Annotations are consumed and dropped -- the position they were written at has nowhere to put them.
     * That is every position in object-binding mode except a record whose bound class declares an {@code
     * Annotations} component: a bound scalar, array, map or tuple is a plain Java value with no slot for
     * metadata, and a record without a carrier opted out by not declaring one.
     *
     * <p>Distinct from {@link #UNVALIDATED}, which keeps them without making any claim about their type:
     * this one keeps nothing, so it also does no checking. That matters -- validating at just the handful of
     * positions that happen to route through a capturing reader would report a document's annotation errors
     * arbitrarily, depending on where in the shape they were written.
     */
    static final AnnotationTypes DISCARDED = new AnnotationTypes(false, Optional.empty(), name -> null);

    /**
     * The vocabulary a compiled schema offers -- its own entries, which already include everything it
     * imports. Takes the {@link ValueReaderContext} apart rather than storing it: that record is the
     * <em>compile</em>-time environment, and what is wanted here outlives compilation. The resolver it
     * carries is a {@link CompiledReaders}, so after the walk this reaches the finished schema rather than
     * the compilation that built it.
     */
    static AnnotationTypes of(ValueReaderContext context) {
        return new AnnotationTypes(true, Optional.of(context.schema()), context.readers());
    }

    /** Whether a governing schema is in scope at all, and so whether an unresolvable name is a defect. */
    boolean validating() {
        return schema.isPresent();
    }

    /**
     * The compiled reader for the type {@code name} names, or empty when nothing declares it (or when
     * nothing is in scope to declare it). Gated on the schema's own entries because resolution throws for a
     * name it has no entry for, and an author's typo is a diagnostic, not an exception.
     */
    Optional<TsonValueReader<?>> readerFor(String name) {
        return schema.filter(s -> s.entries().containsKey(name)).map(s -> readers.resolve(name));
    }
}
