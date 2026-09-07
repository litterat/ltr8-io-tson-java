/**
 * The JSON encoding of the TSON schema system ([TSON-JSON], {@code spec/tson-part3-json.md}).
 *
 * <p>Exports the consumer-facing root package only. {@code lexer} is internal machinery, on the same
 * terms as {@code tson-compiler}'s own: a consumer names {@code Json}, {@code JsonValue} and {@code
 * JsonParseException}, never a token.
 */
module io.ltr8.tson.json {
    exports io.ltr8.tson.json;
}
