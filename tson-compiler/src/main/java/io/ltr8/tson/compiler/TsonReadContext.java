package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.io.InputStream;
import java.util.Optional;

/**
 * Carried through every {@link TsonValueReader#read(TsonReadContext)} call -- the read's own pull
 * cursor over a {@link TsonEventSource}, error sink, current path, and position tracking. Orthogonal
 * to DOM vs. object-binding mode: both share the same composite readers (record/array/map/tuple/
 * atom), which are the only things that ever touch this.
 *
 * <p>An interface, not a concrete class, deliberately -- the backing implementation can change later
 * (e.g. a different {@link TsonEventSource}) without breaking this contract. Kept in mind but not
 * built yet: {@link TsonValueReader#read} could eventually take *only* a {@code TsonReadContext} with
 * no separate value parameter at all if every remaining caller moved to pull-based reading; today's
 * shape (a single {@code ctx} parameter already) is the step that got taken.
 *
 * <p><b>{@link #peek()}/{@link #next()} are the core primitives</b> every reader consumes, delegating
 * to a single {@link TsonEventSource} shared across an entire read -- every scoped copy this
 * interface hands back (see {@link #field}/{@link #index}/{@link #withSchemaPosition}) points at the
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
 * <p><b>The "stamp my own schema position" convention</b>: every reader's {@code read(ctx)} starts by
 * claiming {@code ctx = ctx.withSchemaPosition(this.schemaPosition);} before doing anything else,
 * including before descending into its own children -- so a diagnostic reported from inside, say, an
 * atom reader for {@code integer} carries *that atom's own* declared position, not whatever the
 * enclosing record happened to leave in the context. {@link #field}/{@link #index} never touch schema
 * position; it's always claimed by whichever reader is currently running, not inherited through descent.
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

    /** The current position in the <em>schema</em> document -- absent if the governing declaration carries none. */
    Optional<SourcePosition> schemaPosition();

    /** The path to the value currently being read, as an RFC 6901 JSON Pointer accumulated by {@link #field}/{@link #index}. */
    String path();

    /** A copy of this context scoped one record field or map entry deeper -- {@code name} is RFC 6901-escaped into the path. */
    TsonReadContext field(String name);

    /** A copy of this context scoped one array/tuple element deeper. */
    TsonReadContext index(int i);

    /** A copy of this context with {@link #schemaPosition()} replaced -- see this interface's own "stamp my own schema position" note. */
    TsonReadContext withSchemaPosition(Optional<SourcePosition> schemaPosition);

    /**
     * A copy of this context whose {@link #position()} is pinned to {@code position} rather than
     * following the shared cursor -- see this interface's own note on a field never mentioned by the
     * data at all. {@link #peek()}/{@link #next()} on the returned copy still pull from the same
     * live, shared cursor as always; only {@link #position()} itself is overridden.
     */
    TsonReadContext withPosition(Optional<SourcePosition> position);

    /**
     * Builds a {@link Diagnostic} for one problem at the current {@link #path()}/{@link #position()}/{@link
     * #schemaPosition()} and hands it to this read's {@link TsonDiagnosticsReceiver}, which decides its
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
     * document-level framing is assumed, so a caller passing a mid-document or replay source (e.g. a
     * {@code ListEventSource}) gets exactly the events it supplied, and a caller reading a whole document
     * consumes its own leading {@code DocumentStart}.
     */
    static TsonReadContext of(TsonEventSource events, TsonDiagnosticsReceiver receiver) {
        return DefaultTsonReadContext.of(events, receiver);
    }

    /** {@link #of(TsonEventSource, TsonDiagnosticsReceiver)} with the fail-fast receiver -- the first problem throws {@link TsonReadException}. */
    static TsonReadContext throwing(TsonEventSource events) {
        return of(events, TsonDiagnosticsReceiver.throwing());
    }

    /**
     * A context over a whole document's own source text, reporting through {@code receiver}. Unlike {@link
     * #of(TsonEventSource, TsonDiagnosticsReceiver)} this handles the document framing: it builds a {@link
     * TsonDataStream} and consumes the leading {@code DocumentStart} (no {@code !!id}/{@code !!schema} is
     * needed for schema-validated reading), leaving the cursor on the root value's own first event.
     *
     * <p>This is how a per-type reader from a compiled schema collects rather than throws -- the receiver
     * travels on the context, so {@link TsonValueReader} needs nothing of its own:
     *
     * <pre>{@code
     * var problems = TsonDiagnosticsReceiver.collecting();
     * var value = compiled.get("person").read(TsonReadContext.document(source, problems));
     * problems.diagnostics();      // every problem, alongside a possibly-partial value
     * }</pre>
     */
    static TsonReadContext document(String source, TsonDiagnosticsReceiver receiver) {
        return document(new TsonDataStream(source), receiver);
    }

    /** {@link #document(String, TsonDiagnosticsReceiver)} over a whole document's own bytes (UTF-8) -- streams {@code source} genuinely (never buffered into a {@code String} first); {@code source} is not closed here, the caller owns that. */
    static TsonReadContext document(InputStream source, TsonDiagnosticsReceiver receiver) {
        return document(new TsonDataStream(source), receiver);
    }

    /** Fail-fast over a whole document's own source text -- {@link #document(String, TsonDiagnosticsReceiver)} with the throwing receiver. */
    static TsonReadContext throwing(String source) {
        return document(source, TsonDiagnosticsReceiver.throwing());
    }

    /** Fail-fast over a whole document's own source bytes (UTF-8) -- {@link #document(InputStream, TsonDiagnosticsReceiver)} with the throwing receiver. */
    static TsonReadContext throwing(InputStream source) {
        return document(source, TsonDiagnosticsReceiver.throwing());
    }

    private static TsonReadContext document(TsonDataStream stream, TsonDiagnosticsReceiver receiver) {
        TsonReadContext ctx = of(stream, receiver);
        ctx.next(); // DocumentStart
        return ctx;
    }
}
