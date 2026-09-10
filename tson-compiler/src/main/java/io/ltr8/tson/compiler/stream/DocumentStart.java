package io.ltr8.tson.compiler.stream;

import io.ltr8.tson.compiler.Position;

import java.util.Optional;

/**
 * Opens the event stream: the document's header directives ([TSON-DATA] §2.2), the raw URI arguments,
 * uninterpreted. Always the first event.
 *
 * <p><b>All three of §2.2's directives, including {@code meta}.</b> Carrying only {@code id}/{@code schema}
 * would leave this event unable to say what a document <em>is</em>, and §7.1 makes classifying a schema
 * document the point rather than a failure -- so a consumer wanting that answer would need a second scan of
 * the same header, producing a second carrier for one fact. Exactly one of {@code schema} and {@code meta}
 * is ever present: §2.2 admits one governing directive, and [TSON-SCHEMA] §12.1 requires a schema document
 * carry exactly one {@code !!meta}.
 *
 * <p><b>This event judges nothing.</b> A {@code !!meta} document opens a stream like any other, because this
 * tier is below the conformance class that decides whether such a document may be read: {@code
 * TsonDataParser} is a Class 1 processor and refuses one, {@code TsonSchemaParser} requires one, and both
 * sit on this stream. Refusing here would settle for both.
 */
public record DocumentStart(Optional<String> id, Optional<String> schema, Optional<String> meta,
                            Position position) implements TsonEvent {

    /** Whether the document is a <em>schema</em> document -- [TSON-SCHEMA] §12.1's {@code !!meta} decides. */
    public boolean isSchemaDocument() {
        return meta.isPresent();
    }
}
