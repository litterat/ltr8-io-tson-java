package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Annotated;
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
        if (!(target instanceof DataClassAnnotated)) {
            return value;
        }
        return ctx -> {
            Annotations annotations = AnnotationCapture.bound(ctx, annotationTypes);
            return new Annotated<>(value.read(ctx), annotations);
        };
    }
}
