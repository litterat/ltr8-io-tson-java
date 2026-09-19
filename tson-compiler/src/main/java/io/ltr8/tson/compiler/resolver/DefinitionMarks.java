package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.schema.meta.RecordExtensionType;

import java.util.List;
import java.util.Optional;

/**
 * The two marks a resolver reads and consumes: {@code @abstract} and {@code @final} at a declaration. Each
 * lowers into {@code record.extension} ([TSON-SCHEMA] §5.2, §8.1) and neither survives into §8.1's
 * author-annotation channel, so one carrier holds the fact and §6's no-hoisting question does not arise.
 *
 * <p><b>There is no mark for member dispatch and none on a field.</b> A record dispatched on its own members
 * is ABSTRACT with a non-empty {@code record.discriminators}, derived from the body in the manner of {@code
 * choice.disjoint}, and the fields it dispatches on are written {@code =?} ({@link FieldModifiers}) -- field
 * syntax rather than an annotation. {@code @sealed} and {@code @discriminator} were the earlier spelling of
 * both facts and are refused wherever they are written: a meta-schema declaring either name would otherwise
 * make it an ordinary annotation that lands in output and dispatches nothing.
 *
 * <p><b>They are recognised by name, unconditionally, and that is what "reserved" means.</b> An ordinary
 * annotation resolves one hop against the governing meta (§3.3.3) and means whatever that meta says; these are
 * consumed before the meta is consulted, so a meta-schema cannot give them another meaning and a schema cannot
 * mean something else by them. The meta declares both anyway ({@code @annotation void}), which documents them
 * in the vocabulary an author reads and reserves the names. The annotation shape is the interim: §12.1 should
 * spell both as syntax, and recognising them here rather than through the meta is the arrangement closest to
 * that -- the move {@code =?} has already made for the field half.
 *
 * <p><b>A mark takes no value.</b> Each is declared {@code void}, so {@code @abstract:"..."} states a value
 * for a type that admits none; refusing it here rather than at the annotation's own type is what keeps the
 * reading uniform whether or not the governing meta declares the name.
 */
final class DefinitionMarks {

    /** No instances of its own: a value at a position typed by it is a value of some subtype. */
    private static final String ABSTRACT = "abstract";

    /** Direct instances and no subtypes: nothing may compose or refine onto it. */
    private static final String FINAL = "final";

    /** Retired: the derivation ABSTRACT-plus-discriminators states it. */
    private static final String SEALED = "sealed";

    /** Retired: the field spelling {@code =?} states it. */
    private static final String DISCRIMINATOR = "discriminator";

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
                        + " once, and the two marks are alternatives rather than companions ([TSON-SCHEMA]"
                        + " §5.2)");
            }
            found = member;
            foundName = annotation.name();
        }
        return Optional.ofNullable(found);
    }

    /**
     * {@code written} with every mark removed -- what reaches the annotation channel. Returns the list itself
     * where nothing is marked, which is every declaration and field but the few that carry one. Every
     * annotation position passes through here, which is what makes it the place a retired mark is refused.
     */
    static List<Annotation> consumed(String where, List<Annotation> written) {
        requireNoRetiredMark(where, written);
        for (Annotation annotation : written) {
            if (isMark(annotation.name())) {
                return written.stream().filter(a -> !isMark(a.name())).toList();
            }
        }
        return written;
    }

    /**
     * Refuses a retired mark, naming what states the fact now. Refusing beats ignoring: a meta declaring
     * either name makes it an ordinary annotation, which lands in output and dispatches nothing.
     */
    private static void requireNoRetiredMark(String where, List<Annotation> written) {
        for (Annotation annotation : written) {
            if (SEALED.equals(annotation.name())) {
                throw new SchemaValidationException("'" + where + "': '@sealed' is not a mark -- a record"
                        + " dispatched on its own members is '@abstract' with at least one field written"
                        + " '=?', and that the family is member-dispatched is read from the body"
                        + " ([TSON-SCHEMA] §5.2)");
            }
            if (DISCRIMINATOR.equals(annotation.name())) {
                throw new SchemaValidationException("'" + where + "': '@discriminator' is not a mark -- write"
                        + " the field as '" + where + ": <type> =?' to say that the members pin it"
                        + " ([TSON-SCHEMA] §5.2)");
            }
        }
    }

    private static boolean isMark(String name) {
        return memberOf(name) != null;
    }

    private static RecordExtensionType memberOf(String name) {
        return switch (name) {
            case ABSTRACT -> RecordExtensionType.ABSTRACT;
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
