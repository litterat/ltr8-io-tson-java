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
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;

import io.ltr8.bind.DataClass;
import java.util.function.IntFunction;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything {@link TupleTreeReader} and {@link TupleBindReader} share verbatim: resolving every
 * position's own reader once at construction, confirming a tuple's own array-shaped event sequence
 * (never {@code EmptyBraceEvent} -- a tuple is array-shaped on the wire, not record-shaped, matching
 * {@code TsonObjectReader.toTuple}'s own note), and decoding every position into a single {@code
 * Object[]} in slot order straight off the stream.
 *
 * <p>Each position carries its own type *and* its own {@link ElementState} (§5.3) -- unlike {@link
 * ArrayAbstractReader}, where every element shares one type/state -- so absent-position handling
 * stays per-slot here rather than shared with arrays; the logic is analogous, not identical, so it's
 * duplicated rather than forced through one shared method (matching how {@code isAbsent} is
 * duplicated, not shared, across every structural kind in this package).
 *
 * <p><b>Arity is fixed and exact</b>, unlike {@link ArrayAbstractReader}/{@link MapAbstractReader}'s
 * {@code min_items}/{@code max_items} range -- but a stream has no up-front element count the way an
 * already-built element list did, so arity is checked incrementally rather than against a pre-known
 * length: an element arriving past {@code slots.size()} reports {@code WRONG_ARITY} once (and every
 * further extra element is still decoded and discarded, not silently dropped -- keeping the cursor
 * correctly positioned for {@code ArrayEnd}), and {@code ArrayEnd} arriving before every slot got a
 * value reports {@code WRONG_ARITY} too.
 */
abstract class TupleAbstractReader<T> implements TsonTypeReader<T> {

    record CompiledSlot(TupleElement schema, TsonTypeReader<?> parser) {
    }

    final String name;

    /** What to call this entry in a message -- see {@link ArrayAbstractReader#displayName}. */
    final String displayName;

    final List<CompiledSlot> slots;
    final SchemaLocation schemaLocation;

    TupleAbstractReader(String name, String displayName, TupleBody body, TsonTypeReaderResolver resolver,
                         SchemaLocation schemaLocation) {
        this(name, displayName, body, resolver, schemaLocation, position -> null, AnnotationTypes.DISCARDED);
    }

    /**
     * {@code boxedAt} is asked, per position, for the bound target at that position, so a subclass can wrap
     * what the schema resolved -- object-binding mode does this when a tuple position's Java type is a boxed
     * {@code Annotated<T>}. Per position rather than once, because a tuple's positions have independent
     * types and any subset of them may be boxed.
     */
    TupleAbstractReader(String name, String displayName, TupleBody body, TsonTypeReaderResolver resolver,
                         SchemaLocation schemaLocation, IntFunction<DataClass> boxedAt,
                         AnnotationTypes annotationTypes) {
        this.name = name;
        this.displayName = displayName;
        List<CompiledSlot> slots = new ArrayList<>(body.elements().size());
        for (int position = 0; position < body.elements().size(); position++) {
            TupleElement element = body.elements().get(position);
            TsonTypeReader<?> parser = AnnotationBoxing.wrap(UseSite.reader(element.elementType(), resolver),
                    boxedAt.apply(position), annotationTypes);
            slots.add(new CompiledSlot(element, parser));
        }
        this.slots = slots;
        this.schemaLocation = schemaLocation;
    }

    /**
     * Consumes leading annotations/type-ref, then checks for {@code ArrayStart}, consuming it on
     * success and returning {@code true}. On a shape mismatch, reports {@code TYPE_MISMATCH},
     * discards whatever was actually there, and returns {@code false} -- see {@link
     * RecordAbstractReader#dataFields} for the identical "caller must stop" contract.
     */
    final boolean expectTupleStart(TsonReadContext ctx) {
        EventSkip.annotationsAndTypeRef(ctx);
        if (ctx.peek() instanceof ArrayStart) {
            ctx.next();
            return true;
        }
        TsonEvent e = ctx.peek();
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected a tuple (array-shaped) for '" + displayName + "', found " + TypeRefCheck.describe(e),
                "a tuple (array-shaped)", TypeRefCheck.describe(e));
        EventSkip.coreValue(ctx);
        return false;
    }

    /**
     * Decodes every position up to {@code ArrayEnd} (the cursor assumed already positioned right
     * after {@code ArrayStart} -- see {@link #expectTupleStart}) into a fixed-size {@code Object[]}.
     * A slot beyond {@code slots.size()} is still fully decoded-and-discarded (see this class's own
     * Javadoc); a diagnostic reported anywhere during decoding (arity or per-slot) is what {@link
     * TupleBindReader#read} checks before ever attempting to construct a bound object from the result.
     */
    final Object[] decode(TsonReadContext ctx) {
        Object[] result = new Object[slots.size()];
        int index = 0;
        boolean reportedExtra = false;
        while (!(ctx.peek() instanceof ArrayEnd)) {
            if (ctx.peek() instanceof SchemaRef) {
                ctx.next();
            }
            if (index >= slots.size()) {
                if (!reportedExtra) {
                    ctx.report(Diagnostic.Code.WRONG_ARITY,
                            "'" + displayName + "' has " + slots.size() + " positions, found more than " + slots.size() + " elements",
                            slots.size() + " elements", "more than " + slots.size());
                    reportedExtra = true;
                }
                EventSkip.dataValue(ctx);
                index++;
                continue;
            }
            CompiledSlot slot = slots.get(index);
            result[index] = ctx.peek() instanceof AbsentEvent ? defaultOrRequire(slot, index, ctx)
                    : slot.parser().read(ctx.index(index));
            index++;
        }
        ctx.next(); // ArrayEnd
        if (index < slots.size()) {
            ctx.report(Diagnostic.Code.WRONG_ARITY,
                    "'" + displayName + "' has " + slots.size() + " positions, found only " + index + " elements",
                    slots.size() + " elements", String.valueOf(index));
        }
        return result;
    }

    private Object defaultOrRequire(CompiledSlot slot, int index, TsonReadContext ctx) {
        ctx.next(); // consume the AbsentEvent regardless of REQUIRED/OPTIONAL
        if (slot.schema().state() == ElementState.REQUIRED) {
            ctx.index(index).report(Diagnostic.Code.FIELD_REQUIRED,
                    "'" + displayName + "' position [" + index + "] is absent, but this position is required",
                    "a value", "(absent)");
        }
        return null;
    }
}
