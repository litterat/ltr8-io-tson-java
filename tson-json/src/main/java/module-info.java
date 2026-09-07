/**
 * The JSON encoding of the TSON schema system ([TSON-JSON], {@code spec/tson-part3-json.md}).
 *
 * <p>Two exported packages. {@code io.ltr8.tson.json} is the front door -- {@code Json},
 * {@code JsonValue} and its six subtypes, and the exceptions -- shaped after JEP 540 so a consumer
 * learns one API across this and the JDK's forthcoming {@code jdk.incubator.json}.
 * {@code io.ltr8.tson.json.stream} is the pull-based event layer under it, exported for the same
 * reason {@code tson-compiler} exports its own: it is a real contract a caller may want to consume
 * directly, and JEP 540 excludes streaming as a non-goal, so there is nowhere else for that need to go.
 *
 * <p>{@code lexer} stays internal, on the same terms as {@code tson-compiler}'s: a consumer names a
 * value or an event, never a token.
 */
module io.ltr8.tson.json {
    exports io.ltr8.tson.json;
    exports io.ltr8.tson.json.stream;
}
