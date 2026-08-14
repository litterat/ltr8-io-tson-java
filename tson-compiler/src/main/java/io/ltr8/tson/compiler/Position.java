package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.meta.SourcePosition;

/**
 * A source position: 1-based line, 1-based column (counted in Unicode code
 * points, per TSON's Unicode-foundation grammar, §7.1), and a 0-based UTF-8
 * byte offset from the start of the document (after any leading BOM has been
 * stripped), as required for error reports by §8.1.
 *
 * <p>Lives in the root package, not {@code lexer} where it's actually produced -- {@link
 * TsonParseException}/{@link TsonUnsupportedDocumentException} carry one in their own public
 * {@code position()} accessor, and the root package is this module's real, exported front door
 * ({@link TsonDataParser}/{@link TsonSchemaParser}/{@link TsonTypeReader}); {@code lexer} itself
 * (the scanner, its tokens) is internal machinery a consumer never names directly.
 *
 * <p>Implements {@link SourcePosition} directly (no adapter class) so a resolved {@code
 * schema.meta.TypeDefinition} can carry a real position without {@code tson-schema} ever depending
 * on this module -- {@code tson-compiler} already depends on {@code tson-schema}, so implementing
 * the dependency's own interface here is the standard direction for this kind of thing.
 */
public record Position(int line, int column, int byteOffset) implements SourcePosition {
}
