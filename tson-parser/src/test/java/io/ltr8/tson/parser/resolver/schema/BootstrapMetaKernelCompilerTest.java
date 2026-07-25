package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UriType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BootstrapMetaKernelCompiler} in isolation -- the six real constructor targets meta-kernel
 * itself instantiates, each hand-constructed rather than read through any general schema-driven
 * mechanism (see that class's own Javadoc for why). {@link MetaKernelSchemaRegistryTest}/{@link
 * MetaKernelEndToEndTest} already prove this end to end against the real fixture; these are the
 * narrower, one-declaration-at-a-time cases.
 */
class BootstrapMetaKernelCompilerTest {

    private static Instance emptyInstance(String target) {
        return new Instance(new DataValue(List.of(), Optional.of(target), new EmptyBrace()));
    }

    private static Instance enumInstance(String... members) {
        List<ScopedValue> elements = List.of(members).stream()
                .map(m -> new ScopedValue(Optional.empty(),
                        new DataValue(List.of(), Optional.empty(), new TokenValue(m, TokenForm.UNQUOTED))))
                .toList();
        return new Instance(new DataValue(List.of(), Optional.of("enum"), new ArrayValue(elements)));
    }

    @Test
    void unitCompilesToABareUnitInstance() {
        Optional<Top> body = BootstrapMetaKernelCompiler.compile(emptyInstance("unit"));

        assertEquals(Optional.of(new Unit()), body);
    }

    @Test
    void integerTypeCompilesToTheUnconstrainedConstant() {
        assertEquals(Optional.of(IntegerType.UNCONSTRAINED), BootstrapMetaKernelCompiler.compile(emptyInstance("integer_type")));
    }

    @Test
    void textTypeCompilesToTheUnconstrainedConstant() {
        assertEquals(Optional.of(TextType.UNCONSTRAINED), BootstrapMetaKernelCompiler.compile(emptyInstance("text_type")));
    }

    @Test
    void uriTypeCompilesToTheUnconstrainedConstant() {
        assertEquals(Optional.of(UriType.UNCONSTRAINED), BootstrapMetaKernelCompiler.compile(emptyInstance("uri_type")));
    }

    @Test
    void regexTypeCompilesToTheUnconstrainedConstant() {
        assertEquals(Optional.of(RegexType.UNCONSTRAINED), BootstrapMetaKernelCompiler.compile(emptyInstance("regex_type")));
    }

    @Test
    void enumCompilesToTheRawTokenTextRegardlessOfBooleanCollision() {
        Optional<Top> body = BootstrapMetaKernelCompiler.compile(enumInstance("true", "false"));

        assertEquals(Optional.of(new EnumBody(List.of("true", "false"))), body);
    }

    @Test
    void unrecognizedTargetCompilesToEmpty() {
        assertEquals(Optional.empty(), BootstrapMetaKernelCompiler.compile(emptyInstance("something_else")));
    }

    @Test
    void aNonEmptyBodyForAnEmptyBodiedTargetThrows() {
        Instance nonEmpty = new Instance(new DataValue(List.of(), Optional.of("unit"),
                new TokenValue("oops", TokenForm.UNQUOTED)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> BootstrapMetaKernelCompiler.compile(nonEmpty));
        assertTrue(thrown.getMessage().contains("unit"));
    }
}
