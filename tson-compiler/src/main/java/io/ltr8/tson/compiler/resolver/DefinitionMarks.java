package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.schema.meta.RecordExtensionType;

import java.util.List;
import java.util.Optional;

/**
 * The four marks a resolver reads and consumes: {@code @abstract}, {@code @sealed} and {@code @final} at a
 * declaration, {@code @discriminator} on a field. Each lowers into the body -- the first three into {@code
 * record.extension}, the last into {@code record_field.discriminator} ([TSON-SCHEMA] §5.2, §8.1) -- and none
 * survives into §8.1's author-annotation channel, so one carrier holds each fact and §6's no-hoisting question
 * does not arise.
 *
 * <p><b>They are recognised by name, unconditionally, and that is what "reserved" means.</b> An ordinary
 * annotation resolves one hop against the governing meta (§3.3.3) and means whatever that meta says; these are
 * consumed before the meta is consulted, so a meta-schema cannot give them another meaning and a schema cannot
 * mean something else by them. The meta declares all four anyway ({@code @annotation void}), which is what
 * documents them in the vocabulary an author reads and reserves the names against a meta-schema that would
 * otherwise declare its own. The annotation shape is the interim: §12.1 should spell the four as syntax, and
 * recognising them here rather than through the meta is the arrangement closest to that.
 *
 * <p><b>A mark takes no value.</b> Each is declared {@code void}, so {@code @sealed:"..."} states a value for a
 * type that admits none; refusing it here rather than at the annotation's own type is what keeps the reading
 * uniform whether or not the governing meta declares the name.
 */
final class DefinitionMarks {

    /** No instances at a record's own field position -- the record has direct instances of its own. */
    private static final String ABSTRACT = "abstract";

    /** Abstract and dispatched on its own members: at least one field carries {@link #DISCRIMINATOR}. */
    private static final String SEALED = "sealed";

    /** Direct instances and no subtypes: nothing may compose or refine onto it. */
    private static final String FINAL = "final";

    /** On a field: the member a sealed family dispatches on. */
    static final String DISCRIMINATOR = "discriminator";

    private DefinitionMarks() {
    }

    /**
     * The extension a declaration's marks state, from both annotation positions -- §6 honours a checked
     * annotation before the name or after {@code =>}, and the two must lower identically, so a mark is looked
     * for in both and it is immaterial which carried it. Empty where none is written, which is {@link
     * RecordExtensionType#OPEN} and is left to the body's own default rather than restated here.
     */
    static Optional<RecordExtensionType> extension(String declaration, List<Annotation> nameAnnotations,
                                                    List<Annotation> typeDefAnnotations) {
        RecordExtensionType found = null;
        String foundName = null;
        for (Annotation annotation : concat(nameAnnotations, typeDefAnnotations)) {
            RecordExtensionType member = memberOf(annotation.name());
            if (member == null) {
                continue;
            }
            requireBare(declaration, annotation);
            if (found != null) {
                throw new SchemaValidationException("'" + declaration + "': '@" + foundName + "' and '@"
                        + annotation.name() + "' on one declaration -- a record states how it may be realised"
                        + " once, and the three marks are alternatives rather than companions ([TSON-SCHEMA]"
                        + " §5.2)");
            }
            found = member;
            foundName = annotation.name();
        }
        return Optional.ofNullable(found);
    }

    /** Whether a field's written annotations mark it a discriminator, refusing a value on the mark. */
    static boolean discriminates(String field, List<Annotation> written) {
        boolean marked = false;
        for (Annotation annotation : written) {
            if (DISCRIMINATOR.equals(annotation.name())) {
                requireBare(field, annotation);
                marked = true;
            }
        }
        return marked;
    }

    /**
     * {@code written} with every mark removed -- what reaches the annotation channel. Returns the list itself
     * where nothing is marked, which is every declaration and field but the few that carry one.
     */
    static List<Annotation> consumed(List<Annotation> written) {
        for (Annotation annotation : written) {
            if (isMark(annotation.name())) {
                return written.stream().filter(a -> !isMark(a.name())).toList();
            }
        }
        return written;
    }

    private static boolean isMark(String name) {
        return memberOf(name) != null || DISCRIMINATOR.equals(name);
    }

    private static RecordExtensionType memberOf(String name) {
        return switch (name) {
            case ABSTRACT -> RecordExtensionType.ABSTRACT;
            case SEALED -> RecordExtensionType.SEALED;
            case FINAL -> RecordExtensionType.FINAL;
            default -> null;
        };
    }

    private static void requireBare(String where, Annotation annotation) {
        if (annotation.value().isPresent()) {
            throw new SchemaValidationException("'" + where + "': '@" + annotation.name()
                    + "' takes no value -- it is a bare presence marker, and the fact it states is the mark's"
                    + " own ([TSON-SCHEMA] §5.2)");
        }
    }

    private static List<Annotation> concat(List<Annotation> first, List<Annotation> second) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toList();
    }
}
