package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonDataStream;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonCompiledSchemaRegistry;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The actual payoff of building {@code RecordAbstractReader}/friends directly on {@link
 * TsonDataStream} rather than a pre-built {@code Document}: a reader that fails early never pulls
 * the rest of the document off the stream at all, so a huge trailing field's own cost is never
 * paid. Proven by wrapping the real stream in a counting {@link TsonEventSource} and asserting the
 * pull count stays tiny even though the source text itself contains tens of thousands of events'
 * worth of trailing content the reader never needed to look at.
 */
class StreamingLazinessTest {

    /** Counts every event actually pulled off {@code delegate} via {@link #next()} -- {@link #peek()} doesn't count, since it never advances the cursor. */
    private static final class CountingEventSource implements TsonEventSource {
        private final TsonEventSource delegate;
        private int pulled = 0;

        CountingEventSource(TsonEventSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            return delegate.hasNext();
        }

        @Override
        public TsonEvent next() {
            pulled++;
            return delegate.next();
        }

        @Override
        public TsonEvent peek() {
            return delegate.peek();
        }
    }

    private static TsonCompiledSchema compile(TsonLinkedSchema linkedSchema) {
        TsonCompiledMetaRegistry core = new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext());
        return TsonCompiledSchemaRegistry.tree(core).compile(linkedSchema);
    }

    @Test
    void aFailFastErrorOnAnEarlyFieldNeverPullsAHugeTrailingFieldOffTheStream() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("numbers", TypeDefinition.product(ArrayBody.of(TypeRef.of("integer"))));
        entries.put("big_record", TypeDefinition.product(RecordBody.of(List.of(
                RecordField.required("a", TypeRef.of("integer")),
                RecordField.required("b", TypeRef.of("integer")),
                RecordField.required("huge", TypeRef.of("numbers"))))));
        TsonSchema schema = new TsonSchema("https://example.test/laziness.tn",
                "https://example.test/meta.tn", List.of(), entries);
        TsonCompiledSchema compiled = compile(new TsonLinkedSchema(schema));

        // "b" is malformed (an array where an integer token is expected) -- a fail-fast read must
        // throw right there, well before "huge"'s own 50,000 elements are ever reached.
        StringBuilder hugeArray = new StringBuilder("[");
        hugeArray.append("1 ".repeat(50_000));
        hugeArray.append("]");
        String dataSource = "{ a: 1  b: [1 2 3]  huge: " + hugeArray + " }";

        TsonDataStream realStream = new TsonDataStream(dataSource);
        realStream.next(); // DocumentStart
        CountingEventSource counting = new CountingEventSource(realStream);
        TsonReadContext ctx = TsonReadContext.throwing(counting);

        assertThrows(TsonReadException.class, () -> compiled.get("big_record").read(ctx));

        // A handful of events for "{", "a", "1", "b", and "b"'s own malformed "[1 2 3]" value --
        // nowhere close to the ~50,002 events "huge"'s own array alone would need if the reader had
        // pulled that far, let alone the whole document.
        assertTrue(counting.pulled < 100, "pulled " + counting.pulled + " events, expected well under 100");
    }
}
