/**
 * The JSON encoding of the TSON schema system ([TSON-JSON], {@code spec/tson-part3-json.md}).
 *
 * <p>Three exported packages, layered the way the module reads a document.
 * {@code io.ltr8.tson.json} is the front door -- {@code Json}, {@code JsonPosition} and the exceptions
 * every layer raises. {@code io.ltr8.tson.json.tree} is the value model, shaped after JEP 540 so a
 * consumer learns one API across this and the JDK's forthcoming {@code jdk.incubator.json}; it is the
 * JSON counterpart of {@code io.ltr8.tson.tree} and stands in the same relation to its front door.
 * {@code io.ltr8.tson.json.stream} is the pull-based event layer under both, exported for the same
 * reason {@code tson-compiler} exports its own: it is a real contract a caller may consume directly,
 * and JEP 540 excludes streaming as a non-goal, so there is nowhere else for that need to go.
 *
 * <p>{@code lexer} stays internal, on the same terms as {@code tson-compiler}'s: a consumer names a
 * value or an event, never a token.
 */
module io.ltr8.tson.json {
    exports io.ltr8.tson.json;
    exports io.ltr8.tson.json.tree;
    exports io.ltr8.tson.json.stream;
}
