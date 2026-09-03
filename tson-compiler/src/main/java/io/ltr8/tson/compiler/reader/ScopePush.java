package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.SchemaRef;

/**
 * Who may open a schema scope, and what happens where nobody may -- the policy half of [TSON-SCHEMA] §7.8,
 * kept apart from {@link ScopedReader}, which is the reading half.
 *
 * <p>{@code TsonDataStream} emits a {@code SchemaRef} ahead of a record field value, a map entry value and an
 * array element -- the three positions [TSON-DATA] §2.3 admits a directive at. Each of those containers used
 * to consume it and throw it away, which is how a document could push a scope its schema never opted in to
 * and be read as though it had not. They now hand the question here before delegating: a position whose type
 * is a {@code scoped} one leaves the event for {@link ScopedReader}, and every other position refuses it.
 *
 * <p><b>The check costs a document nothing.</b> Everything below is guarded by "is the next event a
 * directive at all", which for every value in almost every document it is not, so the walk that answers
 * <em>which</em> reader is standing here runs only where a document actually pushed a scope.
 *
 * <p><b>A schemaless document opens no scope at all</b> ({@link #refuseSchemaless}), a deliberate divergence
 * from §7.8's "schemaless outer documents ... always permit nested {@code !!schema} directives" recorded in
 * {@code SPEC-FEEDBACK.md}. §7.8's own reasoning is the argument for it: the typed-position restriction
 * exists because "cross-schema acceptance is authored intent, not accident", and a Class 1 document states
 * no intent to opt in to anything. Honouring the directive there would also turn a Class 1 read into a Class
 * 2 read halfway down a document, with nothing on the document saying so.
 *
 * <p>Every refusal reports and keeps reading: the directive is consumed and the value it prefixed is read as
 * it would have been without one, so a stray directive costs one diagnostic rather than a value.
 */
final class ScopePush {

    private ScopePush() {
    }

    /**
     * Answers a directive standing at {@code ctx}'s cursor on behalf of the position {@code reader} is about
     * to read: left in the stream where that reader is a scoped one, and consumed and handed back where it is
     * not, for the caller to {@link #refuse} against the position's own context.
     *
     * <p>Split in two so the caller builds that context only when there is something to report -- a scoped
     * copy per value, on every value of every document, to locate a diagnostic almost none of them has, is
     * what the split is avoiding.
     *
     * @return the refused directive, or {@code null} where there was none or the position admits it
     */
    static SchemaRef notAdmitted(TsonReadContext ctx, TsonTypeReader<?> reader) {
        if (!(ctx.peek() instanceof SchemaRef ref) || accepts(reader)) {
            return null;
        }
        ctx.next();
        return ref;
    }

    /**
     * Reports a directive {@link #notAdmitted} took off the stream, against the position that refused it.
     *
     * @param at       the position's own context, so the diagnostic names the field or element rather than
     *                 the container around it
     * @param typeName the position's own declared type, for the refusal to name
     */
    static void refuse(TsonReadContext at, String typeName, SchemaRef ref) {
        at.report(Diagnostic.Code.VALIDATION_ERROR,
                "'" + typeName + "' is not a scoped type, so a value here cannot open a schema scope with "
                        + "'!!schema:\"" + ref.uri() + "\"' -- a position takes a value from a foreign schema "
                        + "only where its own schema said so, by declaring it scoped (§7.8)",
                "a value of '" + typeName + "', carrying no '!!schema' of its own", ref.uri());
    }

    /**
     * Whether {@code reader} is the one reader that reads a scope push. A {@link DeferredTypeReader} is
     * looked through because a recursive entry resolves to itself through one; nothing else stands between a
     * position and its reader here, {@link Subsumption}'s guard wrapping only atoms and products, and a
     * {@code scoped} entry being neither.
     */
    private static boolean accepts(TsonTypeReader<?> reader) {
        return switch (reader) {
            case ScopedReader ignored -> true;
            case DeferredTypeReader<?> deferred -> {
                TsonTypeReader<?> resolved = deferred.resolved();
                yield resolved != null && accepts(resolved);
            }
            default -> false;
        };
    }

    /**
     * Refuses a directive in a schemaless read and consumes it, so the value it prefixed still reads. A no-op
     * where the next event is not a directive, which is every value in almost every document.
     */
    static void refuseSchemaless(TsonReadContext ctx) {
        if (ctx.peek() instanceof SchemaRef ref) {
            ctx.next();
            ctx.report(Diagnostic.Code.VALIDATION_ERROR,
                    "a document with no '!!schema' of its own opens no schema scope, so the nested "
                            + "'!!schema:\"" + ref.uri() + "\"' here has no outer type to admit it (§7.8)",
                    "no nested '!!schema' in a schemaless document", ref.uri());
        }
    }
}
