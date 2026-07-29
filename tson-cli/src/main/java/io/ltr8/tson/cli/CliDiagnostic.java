package io.ltr8.tson.cli;

/**
 * One problem found while validating or compiling -- {@code code} a coarse, stable bucket, not yet
 * the closed, precise vocabulary {@code STRUCTURED-OUTPUT.md}'s own full {@code Diagnostic} design
 * calls for; {@code message} the caught exception's own text. A v1-minimal stand-in: no {@code
 * path}/{@code expected}/{@code actual}/position fields yet,
 * since nothing upstream of this class produces them (a single caught exception from the existing
 * fail-fast reader/resolver stack is all there is to report from), but the field names deliberately
 * match the eventual full model so extending this later is additive, not a rename.
 *
 * <p><b>Public, not package-private</b> -- {@code tson-bind}'s own {@code DefaultRecordBinder}
 * finds a target's constructor via {@code Class#getConstructors()}, which only ever returns
 * *public* constructors; a package-private record's own implicit canonical constructor is
 * package-private too, so reflective binding from {@code io.ltr8.bind} (a different package)
 * fails outright ("Could not find constructor") unless this class itself is public.
 */
public record CliDiagnostic(String code, String message) {
}
