package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.schema.DefinitionMark;
import io.ltr8.tson.schema.meta.RecordExtensionType;

import java.util.Optional;

/**
 * The bridge from the grammar's {@link DefinitionMark} to the kernel's {@code record.extension}
 * ([TSON-SCHEMA] §5.2, §8.1, §12.1).
 *
 * <p><b>Why the mark is syntax and not an annotation.</b> §6 fixes the home of a fact: "an annotation is the
 * right home exactly when the mark changes no value's validity". Both marks change it -- erase {@code
 * abstract} and a direct instance becomes readable at that position, erase {@code final} and an importing
 * schema may compose a subtype whose values that position then admits. So the fact belongs in the grammar,
 * where the resolved form has always kept it.
 *
 * <p><b>Nothing here knows the names as annotations.</b> The meta declares neither, so {@code @abstract}
 * written in a schema is the ordinary unresolved-name error of §3.3.3, on the same footing as {@code @sealed}
 * and {@code @discriminator}. There is no name to reserve once the spelling is a word the grammar reads.
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
}
