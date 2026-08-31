package io.ltr8.tson.cli;

import io.ltr8.annotation.Field;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonSchemaFetchException;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * This CLI's own on-the-wire shape for {@link Diagnostic} -- same fields, but {@code
 * dataPosition}/{@code schemaPosition} are pre-rendered to {@code "line:column:byteOffset"} strings
 * rather than kept as {@link SourcePosition} objects. Deliberate: {@code diagnostics.tn}'s own {@code
 * data_position}/{@code schema_position} fields are plain {@code text}, and a raw bound string can't
 * be narrowed back into an arbitrary atom-bridged type the way an enum or a number can ({@code
 * RecordBindReader}'s own narrowing only knows about a handful of specific cases) -- keeping this as
 * a separate, string-only DTO sidesteps that gap entirely rather than risking it. {@code code} stays
 * the real {@link Diagnostic.Code} enum, since enum narrowing *is* a proven, already-used binding
 * path elsewhere in this codebase.
 *
 * <p><b>An absent field is absent, not empty.</b> {@link Diagnostic} spells "nothing to say here" as
 * {@code ""} for {@code schemaId}/{@code expected}/{@code actual}, and those render as {@code null} here --
 * via {@link Diagnostic#schemaIdIfKnown()}/{@link Diagnostic#expectedIfStated()}/{@link
 * Diagnostic#actualIfStated()}, which is where the knowledge of <em>which</em> components use that
 * convention belongs: any renderer of a diagnostic needs it, and this one is not the only renderer. The two
 * RFC 6901 pointers need no narrowing: they are already {@code Optional} at the source, because for a
 * pointer {@code ""} is not absence but the root, and a document-level schema problem genuinely carries it.
 *
 * <p><b>{@code fetchReason} stays the real enum</b>, for {@code code}'s own reason: enum narrowing is the
 * proven binding path, and the value is one a consumer routes on -- {@code SCHEMA_UNAVAILABLE} says no
 * schema was obtained, and this says whether the document named something this deployment refuses or a host
 * simply did not answer. Rendering it as text would hand that consumer a string to match on, which is what
 * carrying it structurally exists to avoid.
 *
 * <p><b>{@code unicode_data_version} is the same argument for the other non-location component.</b> A
 * [TSON-DATA] §8.2 refusal is not one of §8.1's four error categories, and §8.2 requires it to name the
 * Unicode data version it was computed against: it is the one outcome two conforming processors may
 * legitimately disagree about, and the version is what explains the disagreement. <b>Which rule
 * refused is the {@code code}</b> -- {@code CONFUSABLE_NAMES}, {@code RESTRICTED_CHARACTER}, {@code
 * RESTRICTED_SCRIPT} -- so the wire carries no second discriminator that could contradict it.
 */
public record CliDiagnostic(Optional<String> path, @Field("schema_pointer") Optional<String> schemaPointer,
                             @Field("schema_id") Optional<String> schemaId,
                             Diagnostic.Code code, String message,
                             Optional<String> expected, Optional<String> actual,
                             @Field("data_position") Optional<String> dataPosition,
                             @Field("schema_position") Optional<String> schemaPosition,
                             @Field("fetch_reason")
                             Optional<TsonSchemaFetchException.Reason> fetchReason,
                             @Field("unicode_data_version") Optional<String> unicodeDataVersion) {

    static CliDiagnostic from(Diagnostic diagnostic) {
        return new CliDiagnostic(diagnostic.path(), diagnostic.schemaPointer(),
                diagnostic.schemaIdIfKnown(),
                diagnostic.code(), diagnostic.message(),
                diagnostic.expectedIfStated(), diagnostic.actualIfStated(),
                diagnostic.dataPosition().map(CliDiagnostic::render),
                diagnostic.schemaPosition().map(CliDiagnostic::render),
                diagnostic.fetchReason(), diagnostic.unicodeDataVersion());
    }

    /** A problem with no location at either end -- a usage failure, or a schema that never named itself. */
    static CliDiagnostic minimal(Diagnostic.Code code, String message) {
        return new CliDiagnostic(Optional.empty(), Optional.empty(), Optional.empty(), code, message,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    /** The position format every rendered diagnostic uses; stated in {@code diagnostics.tn} for consumers. */
    private static String render(SourcePosition position) {
        return position.line() + ":" + position.column() + ":" + position.byteOffset();
    }
}
