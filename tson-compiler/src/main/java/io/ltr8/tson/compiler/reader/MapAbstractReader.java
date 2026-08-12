package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Everything {@link MapTreeReader} and {@link MapBindReader} share verbatim: resolving the key/value
 * types' own readers once at construction, confirming a map-shaped value's own {@code MapStart} (or
 * {@code EmptyBraceEvent}, zero entries, matching {@code TsonObjectReader.toMap}'s own treatment of
 * {@code {}}), and decoding entries one at a time straight off the event stream -- validating {@code
 * min_items}/{@code max_items} against the final count (known only once {@code MapEnd} arrives),
 * rejecting the absent sentinel {@code _} in key position (§2.9) -- handing each decoded key/value
 * pair to a {@link BiConsumer} rather than assembling a result itself, the same reasoning {@link
 * ArrayAbstractReader#readInto} documents for arrays.
 *
 * <p>Unlike {@link ArrayAbstractReader}, there's no {@code unique_items}/{@code ElementState}
 * concept here at all -- {@link MapBody} carries neither: a map's own keys are inherently unique by
 * construction (a duplicate key is "last value wins" via an ordinary {@code put}, matching {@code
 * TsonObjectReader.toMap}'s own note, not a validation error), and there's no per-entry
 * required/optional state for a value the way an array element or tuple position has.
 *
 * <p><b>A key's own path segment is read from a bare peek, not a fully-decoded value</b> -- an
 * annotated key ({@code @foo "mykey" => ...}, a rare shape in practice) reports its path segment as
 * the raw leading annotation event rather than the key's own eventual text; a deliberate, accepted
 * narrowing for this pass rather than adding a "peek past annotations without consuming" capability
 * for a cosmetic-only purpose.
 */
abstract class MapAbstractReader<T> implements TsonValueReader<T> {

    enum Shape { ENTRIES, EMPTY, MISMATCH }

    final String name;
    final MapBody body;
    final TsonValueReader<?> keyParser;
    final TsonValueReader<?> valueParser;
    final Optional<SourcePosition> schemaPosition;

    MapAbstractReader(String name, MapBody body, TsonValueReaderResolver resolver, Optional<SourcePosition> schemaPosition) {
        this(name, body, resolver.resolve(body.keyType().name()), resolver.resolve(body.valueType().name()),
                schemaPosition);
    }

    /**
     * Takes the key and value readers already built, so a subclass can wrap what the schema resolved before
     * handing them over -- object-binding mode does this when the bound map's own key or value type is a
     * boxed {@code Annotated<T>}. Wrapping here rather than inside {@link #readInto} keeps the entry loop,
     * which both modes share, free of anything only one of them needs.
     */
    MapAbstractReader(String name, MapBody body, TsonValueReader<?> keyParser, TsonValueReader<?> valueParser,
                       Optional<SourcePosition> schemaPosition) {
        this.name = name;
        this.body = body;
        this.keyParser = keyParser;
        this.valueParser = valueParser;
        this.schemaPosition = schemaPosition;
    }

    /**
     * Consumes leading annotations/type-ref, then checks for {@code MapStart} ({@link Shape#ENTRIES},
     * entries to loop over follow) or {@code EmptyBraceEvent} ({@link Shape#EMPTY}, nothing more to
     * read). Reports {@code TYPE_MISMATCH} and discards whatever was actually there on a shape
     * mismatch ({@link Shape#MISMATCH}) -- see {@link RecordAbstractReader#dataFields} for the
     * identical "caller must stop" contract.
     */
    final Shape expectMapShape(TsonReadContext ctx) {
        EventSkip.annotationsAndTypeRef(ctx);
        TsonEvent e = ctx.peek();
        if (e instanceof MapStart) {
            ctx.next();
            return Shape.ENTRIES;
        }
        if (e instanceof EmptyBraceEvent) {
            ctx.next();
            return Shape.EMPTY;
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a map for '" + name + "', found " + e,
                "a map", String.valueOf(e));
        EventSkip.coreValue(ctx);
        return Shape.MISMATCH;
    }

    /**
     * Decodes every entry up to {@code MapEnd} (the cursor assumed already positioned right after
     * {@code MapStart} -- see {@link #expectMapShape}), handing each key/value pair to {@code sink};
     * {@code min_items}/{@code max_items} are validated against the final count once {@code MapEnd}
     * arrives. Keeps decoding every remaining entry after one fails, so sibling entries' own problems
     * still surface in the same pass.
     */
    final void readInto(TsonReadContext ctx, BiConsumer<Object, Object> sink) {
        int count = 0;
        while (!(ctx.peek() instanceof MapEnd)) {
            TsonEvent keyPeek = ctx.peek();
            if (keyPeek instanceof AbsentEvent) {
                ctx.next(); // the absent key itself
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "': the absent sentinel '_' must not appear as a map key (§2.9)",
                        "a real map key, never the absent sentinel '_'", "_");
                ctx.next(); // MapArrow
                EventSkip.scopedValue(ctx); // no meaningful key to associate the value with -- discard it
                count++;
                continue;
            }
            String keySegment = keySegmentFor(keyPeek);
            Object key = keyParser.read(ctx.field(keySegment));
            ctx.next(); // MapArrow
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            Object decodedValue = valueParser.read(ctx.field(keySegment));
            sink.accept(key, decodedValue);
            count++;
        }
        ctx.next(); // MapEnd
        validateSize(count, ctx);
    }

    private static String keySegmentFor(TsonEvent e) {
        if (e instanceof TokenEvent token) {
            return token.text();
        }
        return String.valueOf(e);
    }

    private void validateSize(int size, TsonReadContext ctx) {
        body.minItems().ifPresent(min -> {
            if (BigInteger.valueOf(size).compareTo(min) < 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "' has " + size + " entries, fewer than the minimum " + min,
                        "at least " + min + " entries", String.valueOf(size));
            }
        });
        body.maxItems().ifPresent(max -> {
            if (BigInteger.valueOf(size).compareTo(max) > 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + name + "' has " + size + " entries, more than the maximum " + max,
                        "at most " + max + " entries", String.valueOf(size));
            }
        });
    }
}
