package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

import java.util.List;
import java.util.Optional;

/**
 * Opens the event stream: the document's header directives ([TSON-DATA] §2.2) -- its {@code !!id} and its {@code
 * !!import}s in header order -- the raw URI arguments, uninterpreted. Always the first event.
 *
 * <p><b>The header does not classify a document.</b> A schema document is one whose root is tagged {@code !schema}
 * (§2.2), which the events after this one say; {@code TsonDataStream.rootTag()} looks for it without consuming
 * anything. This event judges nothing either way: {@code TsonDataParser} is a Class 1 processor and refuses a schema
 * document, {@code TsonSchemaParser} requires one, and both sit on this stream.
 */
public record DocumentStart(Optional<String> id, List<String> imports, Position position) implements TsonEvent {

    /**
     * The root tag that makes a document a schema ([TSON-DATA] §2.2). It always names the pre-loaded kernel's
     * {@code schema}, whatever the document imports: the tag says what the document is before any import is read.
     */
    public static final String SCHEMA_TAG = "schema";

    /** Whether {@code rootTag}, a root's tag or its absence, makes the document a schema document. */
    public static boolean isSchemaTag(Optional<String> rootTag) {
        return rootTag.isPresent() && SCHEMA_TAG.equals(rootTag.get());
    }

    public DocumentStart {
        imports = List.copyOf(imports);
    }
}
