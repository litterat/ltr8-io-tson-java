package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.schema.meta.TypeInline;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * A type written in place, {@code { of: !C { ... } }}, bound to a {@link TypeInline} under {@code C}: the constructor
 * is the tag on {@code of}, no field of the wrapper, so the wrapper's own reader ({@code wrapper}, the record reader of
 * {@code meta.type_inline}) builds the value with none and this reader supplies it, past its alias hops ({@code
 * terminal}, [TSON-SCHEMA] §8.3), as resolution names the constructor of a type it writes in place.
 *
 * <p>The tag is found by looking ahead over the wrapper's opening, which consumes nothing, so the wrapper's reader
 * reads the whole value as written and reports what it finds wrong with it; where the lookahead finds no tag, that
 * reader's value stands as it built it.
 *
 * <p>What the kernel admits at {@code of} and a type in place may not be ({@link TypeInline#refusal}) is reported
 * here, where an author's type in place becomes a value, so no pass sees one the rule refuses.
 */
record TypeInlineReader(TsonTypeReader<?> wrapper, UnaryOperator<String> terminal) implements TsonTypeReader<Object> {

    private static final String OF = "of";

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> constructor = TsonReadContext.lookingAhead(ctx, TypeInlineReader::constructorAhead);
        Object read = wrapper.read(ctx);
        if (!(read instanceof TypeInline inline)) {
            return read;
        }
        TypeInline tagged = constructor.map(tag -> inline.withConstructor(terminal.apply(tag))).orElse(inline);
        Optional<String> refusal = tagged.refusal();
        if (refusal.isPresent()) {
            ctx.report(Diagnostic.Code.SCHEMA_ERROR, refusal.get() + " ([TSON-SCHEMA] §4.2, §5.2): declare it under a "
                    + "name and write that name", "a type that may be written in place", tagged.constructor());
            return null;
        }
        return tagged;
    }

    /** The tag on the wrapper's {@code of}, read past whatever stands ahead of it; empty where there is none. */
    private static Optional<String> constructorAhead(TsonReadContext ctx) {
        EventSkip.annotationsAndTypeRef(ctx);
        if (!(ctx.next() instanceof RecordStart)) {
            return Optional.empty();
        }
        while (ctx.peek() instanceof FieldName field) {
            ctx.next();
            if (OF.equals(field.name())) {
                EventSkip.annotations(ctx);
                return EventSkip.typeRef(ctx);
            }
            EventSkip.dataValue(ctx);
        }
        return Optional.empty();
    }
}
