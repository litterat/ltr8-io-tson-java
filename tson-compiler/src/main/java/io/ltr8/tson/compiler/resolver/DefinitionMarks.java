package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.schema.DefinitionMark;
import io.ltr8.tson.schema.meta.RecordExtensionType;

import java.util.List;
import java.util.Optional;

/**
 * The bridge from the grammar's {@link DefinitionMark} to the kernel's {@code record.extension}
 * ([TSON-SCHEMA] §5.2, §8.1), and the refusal of the two names as annotations.
 *
 * <p><b>Why the mark is syntax and not an annotation.</b> §6 fixes the home of a fact: "an annotation is the
 * right home exactly when the mark changes no value's validity". Both marks change it -- erase {@code
 * abstract} and a direct instance becomes readable at that position, erase {@code final} and an importing
 * schema may compose a subtype whose values that position then admits. So the fact belongs in the grammar,
 * where the resolved form has always kept it.
 *
 * <p><b>The names are refused rather than ignored.</b> An {@code @abstract} left over from the annotation
 * spelling would otherwise resolve against the governing meta and sit in the author-annotation channel saying
 * nothing, silently widening what the schema admits. Refusing by name is unconditional, before the meta is
 * consulted, so no meta-schema can give either name a second meaning at a declaration.
 *
 * <p><b>There is no mark for member dispatch and none on a field.</b> A record dispatched on its own members
 * is ABSTRACT with a non-empty {@code record.discriminators}, derived from the body in the manner of {@code
 * choice.disjoint}, and the fields it dispatches on are written {@code =?} ({@link FieldModifiers}).
 */
final class DefinitionMarks {

    private DefinitionMarks() {
    }

    /** The extension a declaration's mark states, empty where it writes none -- which is OPEN, the default. */
    static Optional<RecordExtensionType> extension(Optional<DefinitionMark> mark) {
        return mark.map(m -> switch (m) {
            case ABSTRACT -> RecordExtensionType.ABSTRACT;
            case FINAL -> RecordExtensionType.FINAL;
        });
    }

    /**
     * Refuses {@code @abstract} and {@code @final} wherever annotations are read. {@code where} names the
     * declaration or field carrying it, and the diagnostic points at the spelling that replaced it.
     */
    static void requireNoMarkAnnotation(String where, List<Annotation> annotations) {
        for (Annotation annotation : annotations) {
            Optional<DefinitionMark> mark = DefinitionMark.of(annotation.name());
            if (mark.isPresent()) {
                throw new SchemaValidationException("'" + where + "': '@" + annotation.name() + "' is not an"
                        + " annotation -- how a record may be realised changes which values conform, so it is"
                        + " written as the word '" + mark.get().word() + "' after '=>' on the declaration"
                        + " it marks ([TSON-SCHEMA] §5.2, §12.1)");
            }
        }
    }
}
