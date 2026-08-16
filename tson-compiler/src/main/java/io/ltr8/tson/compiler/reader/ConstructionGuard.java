package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;

/**
 * <b>Object-binding mode is all-or-nothing:</b> a value whose read reported anything is not assembled, and
 * binds to {@code null} instead. Shared by every bind-mode reader that builds something -- {@link
 * RecordBindReader}, {@link TupleBindReader}, {@link ArrayBindReader}, {@link MapBindReader} and {@link
 * SchemalessObjectReader}'s own four -- so the policy has one statement rather than nine, and one place to
 * change if it ever moves. {@code TsonObjectReader} applies the same rule once more at the document boundary,
 * for the root value's own framing, which no enclosing read brackets.
 *
 * <p><b>Why bind mode differs from tree mode here.</b> A tree read hands back structure: a {@code TsonRecord}
 * missing a field is a coherent value a caller can inspect alongside the diagnostics, and both tree readers
 * therefore keep everything they built. A bound object is the opposite -- it is real, typed application data,
 * and the whole point of binding is that reaching it means the document was good. Assembling one out of a
 * document already known to be wrong hands a caller data that looks trustworthy and is not, which is a worse
 * outcome than {@code null} plus the diagnostics explaining why.
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
 * policy and stay where they are: {@code MapAbstractReader}/{@code SchemalessObjectReader} ask whether one
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
