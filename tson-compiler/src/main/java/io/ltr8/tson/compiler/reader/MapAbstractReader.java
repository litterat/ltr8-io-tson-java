package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.lexer.Nfc;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.EmptyBraceEvent;
import io.ltr8.tson.compiler.stream.MapEnd;
import io.ltr8.tson.compiler.stream.MapStart;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.MapBody;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Everything {@link MapTreeReader} and {@link MapBindReader} share verbatim: resolving the key/value
 * types' own readers once at construction, confirming a map-shaped value's own {@code MapStart} (or
 * {@code EmptyBraceEvent}, zero entries, matching {@code TsonObjectReader.toMap}'s own treatment of
 * {@code {}}), and decoding entries one at a time straight off the event stream -- validating {@code
 * min_items}/{@code max_items} against the final count (known only once {@code MapEnd} arrives),
 * rejecting the absent sentinel {@code _} in key position and admitting it in value position (§2.9) --
 * handing each decoded key/value pair to a {@link BiConsumer} rather than assembling a result itself, the
 * same reasoning {@link ArrayAbstractReader#readInto} documents for arrays.
 *
 * <p><b>{@code {}} is a zero-entry map here, size rules included.</b> [TSON-DATA] §2.8 defers an empty
 * brace to the resolver and says it resolves to "the empty container of that type" once a schema supplies
 * one, so at a map position it is a map holding nothing -- and a map holding nothing is exactly what {@code
 * min_items: 1} forbids. The count is therefore validated in {@link #expectMapShape}, the one funnel every
 * map reader passes through, rather than in {@link #readInto}, which a {@code {}} never reaches.
 *
 * <p>Unlike {@link ArrayAbstractReader}, there's no {@code unique_items}/{@code ElementState}
 * concept here at all -- {@link MapBody} carries no {@code unique_items}. It does carry an {@link
 * ElementState}, governing the <b>value</b> ({@code {K => V?}}): an entry's value may be the absent
 * sentinel under OPTIONAL and is {@code FIELD_REQUIRED} otherwise, which is the array element's own rule
 * (see {@link #decodedValue}). The key is the opposite and unconditional -- §2.9 forbids the sentinel
 * there whatever the declaration says, checked in {@link #readInto} before the key is decoded at all.
 *
 * <p><b>A repeated key is a validation error</b> ({@code DUPLICATE_MAP_KEY}), reported at the repeat's
 * own position and then decoded like any other entry so the sink still ends up "last value wins".
 * [TSON-DATA] §2.6 makes a duplicate key MUST NOT, with the diagnostic at the repeated occurrence -- a
 * repeated key states an entry for nothing, which needs no schema to see, and last-value-wins survives only
 * as the recovery under the error.
 * Keys compare by their decoded value, so two spellings of one key ({@code 0xFF} and {@code 255}) are
 * the same key, which is what the sink's own {@code put} would have collapsed silently. That exercises
 * [TSON-SCHEMA] §7.7's typed equality wherever a key type is declared, and §2.6's own decoded-value layer
 * ("a processor that decodes values compares decoded values") where no type is.
 *
 * <p><b>A key's own path segment is read from a bare peek, not a fully-decoded value</b> -- an
 * annotated key ({@code @foo "mykey" => ...}, a rare shape in practice) reports its path segment as
 * the raw leading annotation event rather than the key's own eventual text; a deliberate, accepted
 * narrowing for this pass rather than adding a "peek past annotations without consuming" capability
 * for a cosmetic-only purpose.
 */
abstract class MapAbstractReader<T> implements TsonTypeReader<T> {

    enum Shape { ENTRIES, EMPTY, MISMATCH }

    final String name;

    /** What to call this entry in a message -- see {@link ArrayAbstractReader#displayName}. */
    final String displayName;

    final MapBody body;
    final TsonTypeReader<?> keyParser;
    final TsonTypeReader<?> valueParser;
    final SchemaLocation schemaLocation;

    MapAbstractReader(String name, String displayName, MapBody body, TsonTypeReaderResolver resolver,
                       SchemaLocation schemaLocation) {
        this(name, displayName, body, UseSite.reader(body.keyType(), resolver),
                UseSite.reader(body.valueType(), resolver), schemaLocation);
    }

    /**
     * Takes the key and value readers already built, so a subclass can wrap what the schema resolved before
     * handing them over -- object-binding mode does this when the bound map's own key or value type is a
     * boxed {@code Annotated<T>}. Wrapping here rather than inside {@link #readInto} keeps the entry loop,
     * which both modes share, free of anything only one of them needs.
     */
    MapAbstractReader(String name, String displayName, MapBody body, TsonTypeReader<?> keyParser,
                      TsonTypeReader<?> valueParser, SchemaLocation schemaLocation) {
        this.name = name;
        this.displayName = displayName;
        this.body = body;
        this.keyParser = keyParser;
        this.valueParser = valueParser;
        this.schemaLocation = schemaLocation;
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
            // A map with zero entries, so its size faces min_items/max_items exactly as a stated entry list
            // does. Checked here rather than in each subclass's own empty branch, because that is where it
            // went missing: only readInto validated size, and {} never reaches readInto.
            validateSize(0, ctx);
            return Shape.EMPTY;
        }
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "expected a map for '" + displayName + "', found " + TypeRefCheck.describe(e),
                "a map", TypeRefCheck.describe(e));
        EventSkip.coreValue(ctx);
        return Shape.MISMATCH;
    }

    /**
     * Decodes every entry up to {@code MapEnd} (the cursor assumed already positioned right after
     * {@code MapStart} -- see {@link #expectMapShape}), handing each key/value pair to {@code sink};
     * {@code min_items}/{@code max_items} are validated against the final count once {@code MapEnd}
     * arrives. Keeps decoding every remaining entry after one fails, so sibling entries' own problems
     * still surface in the same pass.
     *
     * <p>A key equal to one already decoded is reported ({@code DUPLICATE_MAP_KEY}) at its own position
     * and its entry then read normally -- the continuation policy this reader stack applies everywhere,
     * and what leaves the sink holding the last value for the key either way. A key whose own decoding
     * reported is left out of {@code seen} entirely: it is not a key the document stated, so a second
     * equally-undecodable key would otherwise be reported a second time as a repeat of the first.
     */
    final void readInto(TsonReadContext ctx, BiConsumer<Object, Object> sink) {
        int count = 0;
        Set<Object> seen = new HashSet<>();
        while (!(ctx.peek() instanceof MapEnd)) {
            TsonEvent keyPeek = ctx.peek();
            if (keyPeek instanceof AbsentEvent) {
                ctx.next(); // the absent key itself
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + displayName + "': the absent sentinel '_' must not appear as a map key (§2.9)",
                        "a real map key, never the absent sentinel '_'", "_");
                ctx.next(); // MapArrow
                EventSkip.scopedValue(ctx); // no meaningful key to associate the value with -- discard it
                count++;
                continue;
            }
            String keySegment = keySegmentFor(keyPeek);
            int before = ctx.reported();
            Object key = keyParser.read(ctx.field(keySegment));
            if (ctx.reported() == before && !seen.add(Nfc.keyOf(key))) {
                ctx.field(keySegment).report(Diagnostic.Code.DUPLICATE_MAP_KEY,
                        "duplicate key '" + keySegment + "' in '" + displayName + "' -- a map states each key at most "
                                + "once (§2.6), and the repeat states an entry for nothing",
                        "each key stated once", "'" + keySegment + "' stated again");
            }
            ctx.next(); // MapArrow
            SchemaRef push = ScopePush.notAdmitted(ctx, valueParser);
            if (push != null) {
                ScopePush.refuse(ctx.field(keySegment), body.valueType().name(), push);
            }
            sink.accept(key, decodedValue(keySegment, ctx));
            count++;
        }
        ctx.next(); // MapEnd
        validateSize(count, ctx);
    }

    /**
     * The entry's value, or nothing where the document wrote {@code _} there -- permitted under {@code
     * {K => V?}} ({@link ElementState#OPTIONAL}) and {@code FIELD_REQUIRED} otherwise, the same two answers
     * {@link ArrayAbstractReader#defaultOrRequire} gives an element. Either way the entry is present with an
     * absent value ([TSON-DATA] §2.9) and counts toward the size bounds. Answered here rather than by the
     * value's own reader, which is right to refuse the sentinel: {@code _} is a value of no atom type, so
     * absence is the container's question, exactly as {@link ArrayAbstractReader#readInto} asks it of an
     * element before reaching for the element parser.
     *
     * <p>{@code null} is what both subclasses already turn into their own no-value form ({@link
     * MapTreeReader} a {@code TsonAbsent}, {@link MapBindReader} a null map value), which is also what a
     * soft-failed value read hands them in collecting mode -- the same conflation an absent array element
     * carries, and for the same reason: what went wrong is the diagnostic's to say, not the value's.
     */
    private Object decodedValue(String keySegment, TsonReadContext ctx) {
        if (ctx.peek() instanceof AbsentEvent) {
            ctx.next(); // consumed regardless of REQUIRED/OPTIONAL, so the entry keeps its place either way
            if (body.state() == ElementState.REQUIRED) {
                ctx.field(keySegment).report(Diagnostic.Code.FIELD_REQUIRED,
                        "'" + displayName + "' entry '" + keySegment + "' is absent, but values are required",
                        "a value", "(absent)");
            }
            return null;
        }
        return valueParser.read(ctx.field(keySegment));
    }

    /** A map key's own path segment: its scalar text, or {@code ?} for a key with no single text form -- the same fallback {@code SchemalessTreeReader.keySegment} uses. A raw event's {@code toString()} has no business in an RFC 6901 path. */
    private static String keySegmentFor(TsonEvent e) {
        if (e instanceof TokenEvent token) {
            return token.text();
        }
        return "?";
    }

    private void validateSize(int size, TsonReadContext ctx) {
        body.minItems().ifPresent(min -> {
            if (BigInteger.valueOf(size).compareTo(min) < 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + displayName + "' has " + size + " entries, fewer than the minimum " + min,
                        "at least " + min + " entries", String.valueOf(size));
            }
        });
        body.maxItems().ifPresent(max -> {
            if (BigInteger.valueOf(size).compareTo(max) > 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + displayName + "' has " + size + " entries, more than the maximum " + max,
                        "at most " + max + " entries", String.valueOf(size));
            }
        });
    }
}
