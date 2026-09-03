package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.stream.AbsentEvent;
import io.ltr8.tson.compiler.stream.ArrayEnd;
import io.ltr8.tson.compiler.stream.ArrayStart;
import io.ltr8.tson.compiler.stream.SchemaRef;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Everything {@link ArrayTreeReader} and {@link ArrayBindReader} share verbatim: resolving the
 * element type's own reader once at construction, confirming an array-shaped value's own
 * {@code ArrayStart}, and decoding elements one at a time straight off the event stream --
 * validating {@code min_items}/{@code max_items} (against the final count, known only once {@code
 * ArrayEnd} arrives -- a stream has no up-front length the way an already-built element list did),
 * tolerating (or rejecting) an absent element per {@link ElementState}, and rejecting a duplicate
 * *decoded* element when {@code unique_items} says to -- handing each decoded element to a {@link
 * Consumer} rather than assembling a result itself, since how a decoded element gets stored (a plain
 * {@code List.add}, or a {@code tson-bind} {@code DataClassArray}'s own {@code put()} {@link
 * java.lang.invoke.MethodHandle}) differs completely between the two subclasses. Array elements have
 * no default/fixed-value concept at all ({@link ElementState} has only {@code REQUIRED}/{@code
 * OPTIONAL}, unlike a record field's five-member {@code FieldState}), so there's nothing here
 * resembling {@link RecordAbstractReader}'s own precomputed-default machinery.
 *
 * <p>{@code unordered} is deliberately never validated here -- there's nothing to check about a
 * single array value's own ordering in isolation, only meaningful when *comparing* two arrays.
 *
 * <p><b>{@code max_items} is checked once, at {@code ArrayEnd}, not as soon as the count is
 * exceeded</b> -- a deliberate simplification for this pass; a genuinely oversized array still gets
 * fully read (and every element validated) before the violation is reported, rather than failing
 * fast the instant the limit is crossed. Correctness is unaffected either way; only how much of an
 * already-too-large array gets read before reporting it.
 */
abstract class ArrayAbstractReader<T> implements TsonTypeReader<T> {

    final String name;

    /**
     * What to call this entry in a message -- {@link #name} where the author wrote it, and the form they
     * wrote where they did not (see {@link EntryDisplayName}). Separate from {@code name}, which stays the
     * entry's real name: that is what a type-ref resolves against and what a tree node carries.
     */
    final String displayName;

    final ArrayBody body;
    final TsonTypeReader<?> elementParser;
    final SchemaLocation schemaLocation;

    ArrayAbstractReader(String name, String displayName, ArrayBody body, TsonTypeReaderResolver resolver,
                         SchemaLocation schemaLocation) {
        this(name, displayName, body, UseSite.reader(body.elementType(), resolver), schemaLocation);
    }

    /**
     * Takes the element reader already built, so a subclass can wrap what the schema resolved -- object-
     * binding mode does this when the bound element type is a boxed {@code Annotated<T>}. Wrapping here
     * rather than in the element loop keeps that loop, which both modes share, free of anything only one of
     * them needs.
     */
    ArrayAbstractReader(String name, String displayName, ArrayBody body, TsonTypeReader<?> elementParser,
                         SchemaLocation schemaLocation) {
        this.name = name;
        this.displayName = displayName;
        this.body = body;
        this.elementParser = elementParser;
        this.schemaLocation = schemaLocation;
    }

    /**
     * Consumes leading annotations/type-ref, then checks for {@code ArrayStart}, consuming it on
     * success and returning {@code true}. On a shape mismatch, reports {@code TYPE_MISMATCH},
     * discards whatever was actually there (see {@link RecordAbstractReader#dataFields} for the
     * identical "caller must stop, not also report every element missing" contract), and returns
     * {@code false}.
     */
    final boolean expectArrayStart(TsonReadContext ctx) {
        EventSkip.annotationsAndTypeRef(ctx);
        if (ctx.peek() instanceof ArrayStart) {
            ctx.next();
            return true;
        }
        TsonEvent e = ctx.peek();
        ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                "expected an array for '" + displayName + "', found " + TypeRefCheck.describe(e),
                "an array", TypeRefCheck.describe(e));
        EventSkip.coreValue(ctx);
        return false;
    }

    /**
     * Decodes every element up to {@code ArrayEnd} (the cursor assumed already positioned right
     * after {@code ArrayStart} -- see {@link #expectArrayStart}), handing each to {@code sink};
     * {@code min_items}/{@code max_items} are validated against the final count once {@code
     * ArrayEnd} arrives. Keeps decoding every remaining element after one fails (a {@code null}
     * placeholder is handed to {@code sink} for that element, never skipped) so later elements' own
     * {@link TsonReadContext#index} positions stay accurate against the original data.
     */
    final void readInto(TsonReadContext ctx, Consumer<Object> sink) {
        Set<Object> seen = body.uniqueItems() ? new LinkedHashSet<>() : null;
        int index = 0;
        while (!(ctx.peek() instanceof ArrayEnd)) {
            SchemaRef push = ScopePush.notAdmitted(ctx, elementParser);
            if (push != null) {
                ScopePush.refuse(ctx.index(index), body.elementType().name(), push);
            }
            Object decoded = ctx.peek() instanceof AbsentEvent ? defaultOrRequire(index, ctx)
                    : elementParser.read(ctx.index(index));
            if (seen != null && !seen.add(ValueIdentity.of(decoded))) {
                ctx.index(index).report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + displayName + "' requires unique elements, '" + Rendered.value(decoded)
                                + "' appears more than once",
                        "a value not already present in this array", Rendered.value(decoded));
            }
            sink.accept(decoded);
            index++;
        }
        ctx.next(); // ArrayEnd
        validateSize(index, ctx);
    }

    private Object defaultOrRequire(int index, TsonReadContext ctx) {
        ctx.next(); // consume the AbsentEvent regardless of REQUIRED/OPTIONAL
        if (body.state() == ElementState.REQUIRED) {
            ctx.index(index).report(Diagnostic.Code.FIELD_REQUIRED,
                    "'" + displayName + "' element [" + index + "] is absent, but elements are required",
                    "a value", "(absent)");
        }
        return null;
    }

    private void validateSize(int size, TsonReadContext ctx) {
        body.minItems().ifPresent(min -> {
            if (BigInteger.valueOf(size).compareTo(min) < 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + displayName + "' has " + size + " elements, fewer than the minimum " + min,
                        "at least " + min + " elements", String.valueOf(size));
            }
        });
        body.maxItems().ifPresent(max -> {
            if (BigInteger.valueOf(size).compareTo(max) > 0) {
                ctx.report(Diagnostic.Code.TYPE_MISMATCH,
                        "'" + displayName + "' has " + size + " elements, more than the maximum " + max,
                        "at most " + max + " elements", String.valueOf(size));
            }
        });
    }
}
