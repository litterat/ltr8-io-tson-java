package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Carried alongside a {@link DataValue} through every {@link TsonValueReader#read(DataValue,
 * TsonReadContext)} call -- the tree walk's own error sink, current path, and position tracking.
 * Orthogonal to DOM vs. object-binding mode: both share the same composite readers (record/array/
 * map/tuple/atom), which are the only things that ever touch this.
 *
 * <p>An interface, not a concrete class, deliberately -- {@link #position()} exposes "where am I
 * right now," not a raw {@code Map<CoreValue, Position>} plus a lookup a caller has to perform
 * itself, so the backing implementation can change later (e.g. a streaming reader tracking a live
 * cursor position instead of an identity-keyed map built from an already-fully-parsed document)
 * without breaking this contract. Kept in mind but not built yet: a bigger redesign where {@link
 * TsonValueReader#read} takes only a {@code TsonReadContext} (no separate {@code DataValue}
 * parameter) and this interface gains a {@code CoreValue next()} of its own, enabling a genuinely
 * streaming parser -- this shape is chosen specifically so that door stays open.
 *
 * <p><b>Fail-fast vs. collecting is decided entirely inside {@link #report}</b> -- a fail-fast
 * context throws {@link TsonReadException} the instant it's called (today's single-error behavior,
 * unchanged); a collecting context appends to its own internal sink and returns normally. Every
 * reader calls {@code report(...)} identically either way; no call site branches on {@link
 * #failFast()} itself.
 *
 * <p><b>The "stamp my own schema position" convention</b>: every reader's {@code read(value, ctx)}
 * starts by claiming {@code ctx = ctx.withSchemaPosition(this.schemaPosition);} before doing
 * anything else, including before descending into its own children -- so a diagnostic reported from
 * inside, say, an atom reader for {@code integer} carries *that atom's own* declared position, not
 * whatever the enclosing record happened to leave in the context. {@link #field}/{@link #index} only
 * ever update the path and data position; schema position is always claimed by whichever reader is
 * currently running, not inherited through descent.
 *
 * <p><b>Placeholder-on-failure, not skip-on-failure</b>: after a call to {@link #report} returns
 * (collecting mode -- in fail-fast mode it never returns), the caller continues with {@code null} as
 * that field's/element's/entry's own value and keeps reading its siblings. An array/tuple element is
 * never dropped from the read on failure -- a {@code null} placeholder is kept at that index
 * specifically so later elements' own {@link #index} positions stay accurate against the original
 * data, not shifted by a removed element.
 */
public interface TsonReadContext {

    /** The current position in the <em>data</em> document -- absent if untracked (fail-fast mode) or unknown. */
    Optional<SourcePosition> position();

    /** The current position in the <em>schema</em> document -- absent if the governing declaration carries none. */
    Optional<SourcePosition> schemaPosition();

    /** The path to the value currently being read, as an RFC 6901 JSON Pointer accumulated by {@link #field}/{@link #index}. */
    String path();

    boolean failFast();

    /**
     * A copy of this context scoped one record field or map entry deeper -- {@code name} is RFC
     * 6901-escaped into the path. {@code value} may be {@code null} (a missing required field has no
     * value of its own to point at); {@link #position()} on the result then stays whatever it already
     * was -- the enclosing value's own position remains the nearest real anchor in the submitted
     * text, more useful than reporting no position at all -- while the path still descends normally.
     */
    TsonReadContext field(String name, DataValue value);

    /** A copy of this context scoped one array/tuple element deeper. */
    TsonReadContext index(int i, DataValue value);

    /** A copy of this context with {@link #schemaPosition()} replaced -- see this interface's own "stamp my own schema position" note. */
    TsonReadContext withSchemaPosition(Optional<SourcePosition> schemaPosition);

    /**
     * A copy of this context with {@link #position()} resolved directly from {@code value} -- what
     * every reader calls, alongside {@link #withSchemaPosition}, at the very top of its own {@code
     * read(value, ctx)}. For a nested read this is a harmless no-op re-resolving the same value the
     * parent's own {@link #field}/{@link #index} call already resolved; for the top-level read (no
     * parent ever called {@link #field}/{@link #index} to seed it) this is the only place {@link
     * #position()} ever gets set at all.
     */
    TsonReadContext at(DataValue value);

    /**
     * Records one problem at the current {@link #path()}/{@link #position()}/{@link #schemaPosition()}.
     * Throws {@link TsonReadException} immediately in fail-fast mode; appends to this context's own
     * sink and returns normally in collecting mode.
     */
    void report(Diagnostic.Code code, String message, String expected, String actual);

    /** Every diagnostic collected so far -- empty in fail-fast mode (a fail-fast context never accumulates; it throws instead). */
    List<Diagnostic> diagnostics();

    /**
     * A context that throws {@link TsonReadException} from the first {@link #report} call -- today's
     * single-error behavior, with no {@link #position()} tracking (the common case: a caller with no
     * parser-produced position table in hand, e.g. every {@link TsonValueReader#read(DataValue)}
     * call). {@link #failFast()} is {@code true} on the result.
     */
    static TsonReadContext throwing() {
        return DefaultTsonReadContext.throwing(Map.of());
    }

    /**
     * Same as {@link #throwing()}, but with real {@link #position()} tracking -- for a fail-fast
     * caller that still wants the thrown {@link TsonReadException}'s own {@link Diagnostic#dataPosition()}
     * populated, the same {@code dataPositions} table {@link #collecting} accepts.
     */
    static TsonReadContext throwing(Map<CoreValue, Position> dataPositions) {
        return DefaultTsonReadContext.throwing(dataPositions);
    }

    /**
     * A context that accumulates every {@link #report} call into {@link #diagnostics()} instead of
     * throwing -- {@code dataPositions} is normally a parser's own {@code positions()} table (see
     * {@code TsonDataParser}), consulted by {@link #field}/{@link #index} to resolve each child
     * value's own position as the read descends.
     */
    static TsonReadContext collecting(Map<CoreValue, Position> dataPositions) {
        return DefaultTsonReadContext.collecting(dataPositions);
    }
}
