package io.ltr8.tson.cli;

import io.ltr8.annotation.Field;
import io.ltr8.tson.compiler.Diagnostic;
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
 * <p><b>Why a schema was not obtained is the {@code code}</b>, not a field beside it. The five
 * {@code SCHEMA_*} members say whether the document named something this deployment refuses, or a host
 * simply did not answer -- a value a consumer routes on, so it lives where routing values live. A second
 * discriminator could contradict the first.
 *
 * <p><b>A [TSON-DATA] §8.2 name-hygiene refusal is one of these like any other problem</b>, and carries
 * nothing extra. Which rule refused is the {@code code} -- {@code CONFUSABLE_NAMES}, {@code
 * RESTRICTED_CHARACTER}, {@code RESTRICTED_SCRIPT}, one each -- so the wire needs no second discriminator
 * that could contradict it; and the Unicode data version §8.2 requires a refusal to name is a fact about
 * the processor rather than about the problem, so the envelope states it once ({@link CliPolicy}) instead
 * of every refusal restating one constant.
 */
public record CliDiagnostic(Optional<String> path, @Field("schema_pointer") Optional<String> schemaPointer,
                             @Field("schema_id") Optional<String> schemaId,
                             Diagnostic.Code code, String message,
                             Optional<String> expected, Optional<String> actual,
                             @Field("data_position") Optional<String> dataPosition,
                             @Field("schema_position") Optional<String> schemaPosition) {

    static CliDiagnostic from(Diagnostic diagnostic) {
        return new CliDiagnostic(diagnostic.path(), diagnostic.schemaPointer(),
                diagnostic.schemaIdIfKnown(),
                diagnostic.code(), diagnostic.message(),
                diagnostic.expectedIfStated(), diagnostic.actualIfStated(),
                diagnostic.dataPosition().map(CliDiagnostic::render),
                diagnostic.schemaPosition().map(CliDiagnostic::render));
    }

    /** A problem with no location at either end -- a usage failure, or a schema that never named itself. */
    static CliDiagnostic minimal(Diagnostic.Code code, String message) {
        return new CliDiagnostic(Optional.empty(), Optional.empty(), Optional.empty(), code, message,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /** The position format every rendered diagnostic uses; stated in {@code diagnostics.tn} for consumers. */
    private static String render(SourcePosition position) {
        return position.line() + ":" + position.column() + ":" + position.byteOffset();
    }
}
