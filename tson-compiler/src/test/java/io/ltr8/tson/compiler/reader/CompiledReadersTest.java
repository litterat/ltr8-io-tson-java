package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompiledReaders}: the two-phase name lookup that lets a reader resolve a sibling both while it is
 * being built and while it is reading, without the second use pinning the compilation that served the first.
 *
 * <p>The behaviour under test is the handover. Before {@code bind} the handle answers from the compile walk;
 * after it, from the finished {@link TsonCompiledSchema} -- and the compile-time delegate is *replaced*, not
 * consulted as a fallback, which is what makes {@code TsonSchemaCompiler.Compilation}'s "never escape a
 * single compile invocation" true rather than aspirational.
 */
class CompiledReadersTest {

    /** A reader identifiable by which phase produced it; never actually read from. */
    private record Marker(String from) implements TsonTypeReader<String> {

        @Override
        public String read(TsonReadContext ctx) {
            throw new UnsupportedOperationException("marker");
        }
    }

    private static TsonCompiledSchema compiledWith(String name, TsonTypeReader<?> reader) {
        TsonSchema schema = new TsonSchema("id", "meta", List.of(), Map.of());
        return new TsonCompiledSchema(new TsonLinkedSchema(schema), Map.of(name, reader));
    }

    @Test
    void resolvesThroughTheCompilationUntilBound() {
        CompiledReaders readers = new CompiledReaders(name -> new Marker("compiling"));

        assertEquals("compiling", ((Marker) readers.resolve("anything")).from());
    }

    @Test
    void bindHandsResolutionToTheFinishedSchema() {
        TsonTypeReader<?> fromSchema = new Marker("compiled");
        CompiledReaders readers = new CompiledReaders(name -> new Marker("compiling"));

        readers.bind(compiledWith("point", fromSchema));

        // the exact reader the finished schema holds, not an equal one rebuilt by the compile-time delegate
        assertSame(fromSchema, readers.resolve("point"));
    }

    /**
     * The compile-time delegate must be unreachable afterwards, not merely deprioritised -- a fallback would
     * keep the compilation alive and would quietly resolve names the finished schema deliberately rejects.
     */
    @Test
    void theCompileTimeDelegateIsNotAFallbackAfterBinding() {
        CompiledReaders readers = new CompiledReaders(name -> new Marker("compiling"));
        readers.bind(compiledWith("point", new Marker("compiled")));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> readers.resolve("nosuchentry"));
        assertTrue(thrown.getMessage().contains("nosuchentry"), thrown.getMessage());
    }

    @Test
    void bindingTwiceIsRejected() {
        CompiledReaders readers = new CompiledReaders(name -> new Marker("compiling"));
        readers.bind(compiledWith("point", new Marker("first")));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> readers.bind(compiledWith("point", new Marker("second"))));
        assertTrue(thrown.getMessage().contains("already bound"), thrown.getMessage());
    }
}
