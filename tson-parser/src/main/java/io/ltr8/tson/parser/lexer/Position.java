package io.ltr8.tson.parser.lexer;

/**
 * A source position: 1-based line, 1-based column (counted in Unicode code
 * points, per TSON's Unicode-foundation grammar, §7.1), and a 0-based UTF-8
 * byte offset from the start of the document (after any leading BOM has been
 * stripped), as required for error reports by §8.1.
 */
public record Position(int line, int column, int byteOffset) {
}
