package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.Optional;

/**
 * Carried through every {@link TsonTypeReader#read(TsonReadContext)} call -- the read's own pull
 * cursor over a {@link TsonEventSource}, error sink, current path, and position tracking. Orthogonal
 * to DOM vs. object-binding mode: both share the same composite readers (record/array/map/tuple/
 * atom), which are the only things that ever touch this.
 *
 * <p>An interface, not a concrete class, deliberately -- the backing implementation can change later
 * (e.g. a different {@link TsonEventSource}) without breaking this contract. Kept in mind but not
 * built yet: {@link TsonTypeReader#read} could eventually take *only* a {@code TsonReadContext} with
 * no separate value parameter at all if every remaining caller moved to pull-based reading; today's
 * shape (a single {@code ctx} parameter already) is the step that got taken.
 *
 * <p><b>{@link #peek()}/{@link #next()} are the core primitives</b> every reader consumes, delegating
 * to a single {@link TsonEventSource} shared across an entire read -- every scoped copy this
 * interface hands back (see {@link #field}/{@link #index}/{@link #schemaField}) points at the
 * *same* underlying source and diagnostic sink, so pulling an event through any one copy is visible
 * to all of them. {@link #position()} is not a stored, per-copy value -- it always reflects whichever
 * event was most recently peeked or consumed, on *any* copy, since there is only ever one real cursor
 * per read.
 *
 * <p><b>Where a problem goes is the {@link TsonDiagnosticsReceiver}'s decision, not this context's</b>
 * -- {@link #report} builds the {@link Diagnostic} from the path and positions tracked here, then hands
 * it over. A fail-fast receiver throws {@link TsonReadException} the instant it's called; a collecting
 * one accumulates and returns normally, letting the read continue. Every reader calls {@code report(...)}
 * identically either way, and no call site branches on the policy. A reader needing to know whether its
 * own children reported anything asks {@link #reported()}, which works for every receiver -- including
 * one that streams its diagnostics somewhere and keeps no list at all.
 *
 * <p><b>The "offer my own declaration" convention</b>: every reader's {@code read(ctx)} starts by offering
 * its own declaration to the schema location before doing anything else -- {@link #inRecord} for a record,
 * {@link #underDeclaration} for everything else -- and the two differ in exactly the way {@link
 * SchemaLocation} explains: a record re-anchors the identity and position on itself because it declares the
 * field the pointer ends with, while an atom's or container's own declaration is only a seed, taken when
 * nothing encloses it. So a diagnostic from inside the {@code int32} reader for {@code person}'s {@code age}
 * field carries person.tn's {@code /person/age}, not core.tn's {@code int32}; the same atom read at the root
 * of a document still carries its own.
 *
 * <p>The pointer itself is accumulated on descent, not claimed: {@link #schemaField} steps both the data path
 * and the schema pointer, {@link #field}/{@link #index} step the data path alone. A map key or an array index
 * is a data step with no schema step -- the schema says one thing about every entry of a map, so {@code
 * /person/tags} is the schema location of every {@code /tags/<key>}. An <em>unrecognized</em> field is the
 * other case for plain {@link #field}: it names nothing in the schema, so extending the schema pointer with
 * it would invent a location that does not exist.
 *
 * <p><b>A missing value (e.g. a missing required field) needs no special casing when it's noticed
 * inline</b> -- a reader that decides a field is absent without ever calling {@link #peek()}/{@link
 * #next()} for it (there is nothing in the stream to pull) simply reports against whatever {@link
 * #position()} the shared cursor was already at -- the enclosing container's own nearest real anchor
 * in the submitted text, for free, with no null-value parameter or fallback logic needed anywhere.
 * A field never mentioned by the data at all can only be noticed *after* the whole enclosing record
 * has already been consumed (there's no event of its own to notice it at), by which point the shared
 * cursor has moved on -- {@link #withPosition} exists for exactly this: pinning a context's own
 * {@link #position()} to a value captured earlier (the record's own opening position) rather than
 * whatever the live cursor has drifted to since.
 *
 * <p><b>Placeholder-on-failure, not skip-on-failure</b>: after a call to {@link #report} returns
 * (collecting mode -- in fail-fast mode it never returns), the caller continues with {@code null} as
 * that field's/element's/entry's own value and keeps reading its siblings. An array/tuple element is
 * never dropped from the read on failure -- a {@code null} placeholder is kept at that index
 * specifically so later elements' own {@link #index} positions stay accurate against the original
 * data, not shifted by a removed element.
 */
public interface TsonReadContext {

    /** The next event, without consuming it -- repeated calls with no intervening {@link #next()} return the same event. */
    TsonEvent peek();

    /** Consumes and returns the next event, advancing {@link #position()} to reflect it. */
    TsonEvent next();

    /** The position of whichever event was most recently peeked or consumed, on any copy of this context sharing the same read. */
    Optional<SourcePosition> position();

    /** The declaration currently governing this read -- absent for a read with no schema behind it at all. */
    Optional<SchemaLocation> schemaLocation();

    /** The path to the value currently being read, as an RFC 6901 JSON Pointer accumulated by {@link #field}/{@link #index}. */
    String path();

    /**
     * A copy of this context scoped one step deeper in the <em>data</em> only -- {@code name} is RFC
     * 6901-escaped into the path. For a map entry, or a field the schema does not declare; a declared record
     * field uses {@link #schemaField} so both ends descend together.
     */
    TsonReadContext field(String name);

    /** A copy of this context scoped one array/tuple element deeper -- a data step, with no schema step. */
    TsonReadContext index(int i);

    /**
     * A copy of this context scoped one <em>declared record field</em> deeper, stepping the data path and the
     * schema pointer together -- the one descent where the schema has a name of its own for where we went.
     */
    TsonReadContext schemaField(String name);

    /**
     * {@link #schemaField(String)} with the field's <em>own</em> declaration position, where the schema
     * records one -- so a diagnostic against {@code /person/age} is positioned at {@code age} rather than at
     * {@code person}'s declaration line, which is the finest a per-declaration table can offer.
     *
     * <p>Absent leaves the enclosing record's position in place, which is the honest answer for a field this
     * resolver never saw a source line for -- a hand-built document, or the bootstrap.
     */
    TsonReadContext schemaField(String name, Optional<SourcePosition> fieldPosition);

    /**
     * A copy of this context anchored on the record now reading: {@code declaration}'s identity and position
     * replace whatever was there, and its pointer is taken only if none has been established. See this
     * interface's own "offer my own declaration" note.
     */
    TsonReadContext inRecord(SchemaLocation declaration);

    /**
     * A copy of this context with {@code declaration} taken <em>only</em> if no schema location has been
     * established yet -- what every non-record reader offers, so its own declaration locates a value read at
     * the root of a document without displacing the enclosing record's when there is one.
     */
    TsonReadContext underDeclaration(SchemaLocation declaration);

    /**
     * A copy of this context whose {@link #position()} is pinned to {@code position} rather than
     * following the shared cursor -- see this interface's own note on a field never mentioned by the
     * data at all. {@link #peek()}/{@link #next()} on the returned copy still pull from the same
     * live, shared cursor as always; only {@link #position()} itself is overridden.
     */
    TsonReadContext withPosition(Optional<SourcePosition> position);

    /**
     * Builds a {@link Diagnostic} for one problem at the current {@link #path()}/{@link #position()}/{@link
     * #schemaLocation()} and hands it to this read's {@link TsonDiagnosticsReceiver}, which decides its
     * fate -- a fail-fast receiver throws {@link TsonReadException} from here and never returns.
     */
    void report(Diagnostic.Code code, String message, String expected, String actual);

    /**
     * How many problems have been reported through this read so far, counting every scoped copy since they
     * share one cursor. Monotonic, and independent of what the receiver does with them, so a reader can
     * checkpoint around a child read ({@code int before = ctx.reported(); ...; if (ctx.reported() > before)})
     * whether the receiver collects, streams, or throws.
     */
    int reported();

    /**
     * A context over a <em>raw</em> {@link TsonEventSource}, reporting through {@code receiver}. Raw: no
     * document-level framing is assumed or performed, so a caller passing a mid-document or replay source
     * (e.g. a {@code ListEventSource}) gets exactly the events it supplied.
     *
     * <p><b>This is not a whole-document read.</b> Framing -- consuming the leading {@code DocumentStart},
     * and pulling past the root value so a lazy {@link TsonDataStream} actually checks for trailing content
     * -- belongs to whoever owns the document: {@link TsonTreeReader}/{@link TsonObjectReader}, which is what
     * a consumer reads through. Driving a {@link TsonTypeReader} over a raw source here reads one value at
     * the cursor and polices nothing around it.
     */
    static TsonReadContext of(TsonEventSource events, TsonDiagnosticsReceiver receiver) {
        return DefaultTsonReadContext.of(events, receiver);
    }

    /** {@link #of(TsonEventSource, TsonDiagnosticsReceiver)} with the fail-fast receiver -- the first problem throws {@link TsonReadException}. */
    static TsonReadContext throwing(TsonEventSource events) {
        return of(events, TsonDiagnosticsReceiver.throwing());
    }

    /**
     * Runs {@code lookahead} against {@code ctx}'s cursor and then rewinds every event it consumed, so
     * whatever reads next sees a stream nothing has touched.
     *
     * <p><b>Why one event of lookahead is not always enough.</b> {@link #peek()} answers "what is here". A
     * reader that has to <em>dispatch</em> asks something else: {@code data-value = *annotation [type-ref]
     * core-value}, so the type-ref it decides on sits behind a run of annotations that can be any length.
     * Reading the annotations to get past them is not a substitute -- they belong to the value, and the
     * reader that ends up building it would never see them, which is a silent loss rather than a failure.
     * Looking and rewinding lets the dispatcher decide and the delegate still read the whole value, framing
     * included, exactly as it would if nothing had dispatched to it.
     *
     * <p>Consumed events are replayed from a buffer rather than re-lexed, so a lookahead costs what it looked
     * past and never the document. {@link #position()} is deliberately left where the lookahead reached
     * rather than restored: a caller looks ahead in order to say something about what it found, and that is
     * where the saying belongs.
     *
     * <p>A static method taking the context rather than an instance method, so it adds nothing an
     * implementation of this interface has to provide.
     */
    static <T> T lookingAhead(TsonReadContext ctx, java.util.function.Function<TsonReadContext, T> lookahead) {
        return DefaultTsonReadContext.lookingAhead(ctx, lookahead);
    }
}
