package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
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
record AnnotationTypes(boolean capture, Optional<TsonSchema> schema, TsonTypeReaderResolver readers) {

    /** No governing schema: every annotation is preserved as authored, none is resolved or checked. */
    static final AnnotationTypes UNVALIDATED = new AnnotationTypes(true, Optional.empty(), name -> null);

    /**
     * No governing schema <em>and</em> nowhere to put an annotation: consumed and dropped, unexamined. The
     * schemaless counterpart of {@link #discarding()}, and the only case in which dropping and not checking
     * are the same decision.
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

    /**
     * This vocabulary with the annotations dropped rather than kept, and <b>still checked</b> -- what a
     * position with a governing schema but nowhere to put the result uses.
     *
     * <p><b>Keeping and checking are two questions, and only the first is about the reader.</b>
     * [TSON-SCHEMA] §6 makes an annotation a typed thing: {@code @T} names a type and its value is
     * validated against that type's contract. Whether the reader has somewhere to <em>put</em> it afterwards
     * is a fact about the bound Java class -- a record that declares no {@code Annotations} component, a
     * bound scalar or array with no slot for metadata -- and a document does not become conformant because
     * the application reading it happens to throw the annotation away. Conflating the two made a document's
     * validity depend on the shape of a class it has never heard of.
     */
    AnnotationTypes discarding() {
        return capture ? new AnnotationTypes(false, schema, readers) : this;
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
    Optional<TsonTypeReader<?>> readerFor(String name) {
        return schema.filter(s -> s.entries().containsKey(name)).map(s -> readers.resolve(name));
    }
}
