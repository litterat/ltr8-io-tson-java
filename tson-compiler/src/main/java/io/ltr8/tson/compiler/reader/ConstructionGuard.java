package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;

/**
 * <b>A read is all-or-nothing, in both modes:</b> a value whose read reported anything is not assembled, and
 * reads to {@code null} instead. Shared by every reader that builds something -- the tree readers ({@link
 * RecordTreeReader}, {@link ArrayTreeReader}, {@link MapTreeReader}, {@link TupleTreeReader}, {@link
 * AtomTreeReader}, {@link AbsentTreeReader}) and the bind readers ({@link RecordBindReader}, {@link
 * TupleBindReader}, {@link ArrayBindReader}, {@link MapBindReader} and {@link DataClassObjectReader}'s own
 * four) -- so the policy has one statement. The facades apply it once more at the document boundary, over
 * every route a problem takes ({@code CountingReceiver}).
 *
 * <p><b>Why a partial value is worse than none.</b> A bound object is real, typed application data, and the
 * whole point of binding is that reaching it means the document was good. A tree is no different in the end:
 * its placeholder for a refused value is the same node as a real absent one, so a partial tree cannot say
 * which of its parts to trust, and a caller holding one has to consult the diagnostics before using any of it.
 * The diagnostics -- each with a path into the document the caller already holds -- are the answer.
 *
 * <p>A second reason applies where a failed child is carried as {@code null}: a Java constructor cannot take
 * {@code null} for a primitive-typed parameter, and neither can a primitive-component array's own {@code
 * put} -- so a partial build risks a secondary {@code NullPointerException} on a caller's stack in place of
 * the diagnostic already recorded. That is why the array readers check <em>before allocating</em> rather than
 * only before returning.
 *
 * <p><b>What the checkpoint counts.</b> {@link TsonReadContext#reported()} is a single counter for the whole
 * read, not per value, so {@link #abandoned} answers "did anything at all get reported since the mark" --
 * including inside any descendant, at any depth. That is the intended reading: a failure below this value
 * means the document is invalid, so nothing above it is constructed either and a whole invalid document binds
 * to {@code null}. Every reason a value can be reported counts equally, whether or not it left an argument
 * unfilled -- a stray field ({@code UNRECOGNIZED_FIELD}), a repeated one ({@code DUPLICATE_FIELD}) and a
 * group violation all say the document is wrong, which is the only question this asks.
 *
 * <p><b>Where the mark goes: after the framing, before the fields.</b> A value's leading annotations and its
 * type-ref are consumed before any argument is read, and diagnostics from there ({@code UNKNOWN_TYPE_REF} on
 * a container name, or from inside an annotation's own value) belong to the enclosing read that chose to look
 * at this position -- they are not this value's own. Marking after the shape check keeps both bind paths
 * saying the same thing, since a shape mismatch abandons the value on its own without consulting the counter.
 *
 * <p>Narrower uses of the same {@code ctx.reported()} idiom elsewhere in this package are unrelated to this
 * policy and stay where they are: {@code MapAbstractReader}/{@code DataClassObjectReader} ask whether one
 * key bound before treating it as a stated key, {@code RecordAbstractReader.verifyFixed} asks whether one
 * token decoded before comparing it to a FIXED value, and {@code AnnotationCapture} probes through a
 * throwaway context. Each brackets a single child read and means exactly what it says.
 */
final class ConstructionGuard {

    private ConstructionGuard() {
    }

    /** The mark to pass to {@link #abandoned}, taken once the framing is consumed and the shape confirmed. */
    static int mark(TsonReadContext ctx) {
        return ctx.reported();
    }

    /** Whether anything was reported since {@code mark} -- if so, the value binds to {@code null} unbuilt. */
    static boolean abandoned(TsonReadContext ctx, int mark) {
        return ctx.reported() > mark;
    }
}
