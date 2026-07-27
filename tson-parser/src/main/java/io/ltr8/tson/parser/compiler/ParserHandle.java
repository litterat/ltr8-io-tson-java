package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.ast.DataValue;

import java.util.Map;

/**
 * One compiled parser's pointer to a child parser it depends on -- named {@code ParserHandle}
 * rather than {@code Reference} specifically to avoid colliding with {@link
 * io.ltr8.tson.schema.meta.Reference} (the unrelated, pre-existing "{@code name => other_name}"
 * resolved-schema concept). {@link Direct} is the common case: the child was already fully built
 * by the time this handle was created, so this just holds it, no indirection at all. {@link
 * Indirect} exists purely for the edges {@code TsonSchemaCompiler}'s own build-stack finds still
 * mid-construction (a cycle back to an entry currently being compiled, directly or transitively) --
 * it defers to a name lookup in the compiler's own registry, taken once the specific {@code
 * TsonSchemaCompiler#compile} call that triggered this whole construction chain has returned, instead of
 * the object it can't hold yet.
 *
 * <p>Both variants are themselves a {@link TsonSchemaTypeParser}, so a composite parser holding a {@code
 * ParserHandle<X>} field never needs to branch on which case it got -- it just calls {@code
 * read(value)} on the handle directly, same as if it held the child parser itself.
 *
 * @param <T> the child parser's own host value type.
 */
public sealed interface ParserHandle<T> extends TsonSchemaTypeParser<T> {

    /** The non-cyclic case -- {@code parser} was already finished when this handle was created. */
    record Direct<T>(TsonSchemaTypeParser<T> parser) implements ParserHandle<T> {

        @Override
        public T read(DataValue value) {
            return parser.read(value);
        }
    }

    /**
     * The cyclic case -- {@code typeName} was still on the compiler's own build stack when this
     * handle was created, so the real parser can't be referenced directly yet. {@code registry} is
     * the compiler's own private, per-compilation {@code Map<String, TsonSchemaTypeParser<?>>}
     * (the {@code finished} map {@code TsonSchemaCompiler}'s own {@code Compilation} helper builds
     * up and later copies into a {@link TsonCompiledSchema}), captured by reference, not copied --
     * by the time {@link #read} is ever actually called, the whole {@code TsonSchemaCompiler
     * #compile} call that created this handle has already returned (nothing outside the compiler
     * can reach a handle embedded inside a parser before that call returns), and every {@code
     * resolve} invocation on that call's own build -- including the one for {@code typeName} -- has
     * by then populated {@code registry}. Now that compilation is eager (every entry in the schema
     * is resolved before {@code compile} returns, not just whichever one a caller first asked for),
     * this is simpler than it used to be under lazy compilation: it genuinely is "the whole schema
     * finishes, then reads happen," not merely "this one recursive chain finishes."
     */
    record Indirect<T>(String typeName, Map<String, TsonSchemaTypeParser<?>> registry) implements ParserHandle<T> {

        @Override
        @SuppressWarnings("unchecked")
        public T read(DataValue value) {
            TsonSchemaTypeParser<?> resolved = registry.get(typeName);
            if (resolved == null) {
                throw new IllegalStateException("'" + typeName + "' has no compiled parser -- an Indirect handle "
                        + "is only ever created for a name that IS in the schema (a cycle back to an "
                        + "in-progress entry), so a missing entry here means the resolve() call that started "
                        + "building '" + typeName + "' returned without ever finishing it, which is a compiler "
                        + "bug, not a caller error");
            }
            return (T) resolved.read(value);
        }
    }
}
