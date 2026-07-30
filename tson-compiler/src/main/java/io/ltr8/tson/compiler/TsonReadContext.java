package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.List;
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
 * <p><b>Fail-fast vs. collecting is decided entirely inside {@link #report}</b> -- a fail-fast
 * context throws {@link TsonReadException} the instant it's called (today's single-error behavior,
 * unchanged); a collecting context appends to its own internal sink and returns normally. Every
 * reader calls {@code report(...)} identically either way; no call site branches on {@link
 * #failFast()} itself.
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

    boolean failFast();

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
     * Records one problem at the current {@link #path()}/{@link #position()}/{@link #schemaPosition()}.
     * Throws {@link TsonReadException} immediately in fail-fast mode; appends to this context's own
     * sink and returns normally in collecting mode.
     */
    void report(Diagnostic.Code code, String message, String expected, String actual);

    /** Every diagnostic collected so far -- empty in fail-fast mode (a fail-fast context never accumulates; it throws instead). */
    List<Diagnostic> diagnostics();

    /** A context that throws {@link TsonReadException} from the first {@link #report} call -- today's single-error behavior. */
    static TsonReadContext throwing(TsonEventSource events) {
        return DefaultTsonReadContext.throwing(events);
    }

    /** A context that accumulates every {@link #report} call into {@link #diagnostics()} instead of throwing. */
    static TsonReadContext collecting(TsonEventSource events) {
        return DefaultTsonReadContext.collecting(events);
    }
}
