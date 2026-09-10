/**
 * The JSON encoding of the TSON schema system ([TSON-JSON], {@code spec/tson-part3-json.md}).
 *
 * <p>Three exported packages, layered the way the module reads a document.
 * {@code io.ltr8.tson.json} is the front door -- {@code Json}, {@code JsonPosition} and the exceptions
 * every layer raises. {@code io.ltr8.tson.json.tree} is the value model, shaped after JEP 540 so a
 * consumer learns one value model across this and the JDK's forthcoming {@code jdk.incubator.json}; it is
 * the JSON counterpart of {@code io.ltr8.tson.tree} and stands in the same relation to its front door.
 *
 * <p><b>That alignment is the tree package and nothing else.</b> Reading, writing and the exceptions they
 * raise follow the TSON side of this library rather than the JDK's API: a fail-fast read throws {@code
 * ReadException} carrying a {@code Diagnostic}, a collecting one throws nothing at all, and problems come
 * from one vocabulary across both encodings ([TSON-JSON] §9.4). Every JEP 540 reference in this module
 * names a value model or a spelling; none of them settles a behaviour.
 * {@code io.ltr8.tson.json.stream} is the pull-based event layer under both, exported for the same
 * reason {@code tson-compiler} exports its own: it is a real contract a caller may consume directly,
 * and JEP 540 excludes streaming as a non-goal, so there is nowhere else for that need to go.
 * {@code JsonObjectReader} sits in the front door beside {@code Json}, the way {@code TsonObjectReader}
 * sits beside {@code Tson}: it reads a document straight into a Java object, driven by the target class's
 * own {@code tson-bind} descriptor. JEP 540 excludes data binding as well as streaming, and for a library
 * whose point is validated typed data that is the one non-goal worth not inheriting.
 *
 * <p><b>It sits on {@code tson-base}</b>, and on nothing else of this library's engines. That is the whole
 * of what two encodings share by specification: a diagnostic vocabulary ([TSON-JSON] §9.4 reports in
 * [TSON-DATA] §8.1's four categories and adds none), a source position, and one limits policy (§10.1, "the
 * same policy applies with the same defaults"). Reading a document is still entirely this module's own.
 *
 * <p><b>Writing is the same two doors from the other side.</b> {@code JsonTreeWriter} is the inverse of
 * {@code JsonTreeReader} and {@code JsonObjectWriter} of {@code JsonObjectReader}, over {@code
 * JsonDataEmitter} -- the push-based peer of {@code stream}'s pull, which owns the separators so no walk
 * places its own. The tree round trip is total, the object one is through the class that wrote it.
 *
 * <p>{@code lexer}, {@code atom}, {@code reader} and {@code writer} stay internal, on the same terms as
 * {@code tson-compiler}'s packages of those names: a consumer names a value, an event, a reader or a
 * writer -- never a token, a parser or an engine.
 */
module io.ltr8.tson.json {
    exports io.ltr8.tson.json;
    exports io.ltr8.tson.json.tree;
    exports io.ltr8.tson.json.stream;

    requires transitive io.ltr8.bind;
    requires transitive io.ltr8.tson.base;
    requires transitive io.ltr8.tson.atom;
}
