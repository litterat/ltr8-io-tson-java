package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassAnnotated;
import io.ltr8.tson.compiler.TsonValueReader;

/**
 * Turns the reader for a value into the reader for that value <em>boxed with the annotations written at its
 * position</em> ({@code io.ltr8.annotation.Annotated<T>}), when the bound Java type at that position asks
 * for one.
 *
 * <p>Shared because every position boxes identically: capture first, then read. The order is the whole
 * mechanism -- the capture has to run before the delegate, so that the delegate's own framing consumption
 * finds nothing left. Reading first would swallow the annotations as part of the value's framing.
 *
 * <p>Returns {@code value} unchanged when the position is not boxed, which is the overwhelmingly common
 * case, so a caller can wrap unconditionally.
 */
final class AnnotationBoxing {

    private AnnotationBoxing() {
    }

    static TsonValueReader<?> wrap(TsonValueReader<?> value, DataClass target, AnnotationTypes annotationTypes) {
        if (!(target instanceof DataClassAnnotated box)) {
            return value;
        }
        return ctx -> {
            Annotations annotations = AnnotationCapture.bound(ctx, annotationTypes);
            Object read = value.read(ctx);
            try {
                // Built through the descriptor's own handle, never by naming the carrier class: tson-bind is
                // the only thing that constructs a bound object.
                return box.constructor().invoke(read, annotations);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("failed to box an annotated value of " + box.typeClass(), t);
            }
        };
    }
}
