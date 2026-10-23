package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.Optional;

/**
 * A reference-typed position ({@code ref<T>}) whose bound component is a {@link io.ltr8.tson.schema.meta.TypeRef}
 * or a {@link io.ltr8.tson.schema.meta.Ref}: the value is a value's qualified name (SPEC-FEEDBACK.md #13), such as
 * {@code orders.place_order}, read as written into a {@code TypeRef} whose {@code name} is the token and whose
 * arguments are none, or into a {@code Ref} the linker delivers the value into.
 *
 * <p><b>Nothing here parses segments.</b> The linker splits the name and applies the identifier grammar to each
 * segment, where it also resolves them.
 *
 * <p><b>Every name it reads is recorded</b> ({@link TsonReadContext#heldByName}): in a schema document the read
 * collects each with its slot and the slot's declared type, and the linker judges that collection.
 *
 * <p><b>What a name is, stated once for every reader that asks</b> ({@link #nameIn}): an <b>unquoted</b> token. A
 * quoted token is text, never a name. {@link RecordAbstractReader} asks the same question to decide when §5.6's
 * positional form loses the token in a schema document, so the readers cannot disagree about which tokens are names.
 *
 * <p><b>Names only.</b> The slot holds a reference and nothing else, so a braced value, a quoted token and a framed
 * name ({@code @doc:"x" orders}, {@code !record x}) are refused: a name's type is its referent's, and an annotation
 * would have nowhere to go.
 */
final class ReferenceNameReader implements TsonTypeReader<Object> {

    /** What the position holds, as a message names it: {@code a 'method' value}. */
    private final String expected;

    /** Whether the host component is a {@link io.ltr8.tson.schema.meta.Ref}, delivered at link, not a {@code TypeRef}. */
    private final boolean toCell;

    /** The slot's declared type, {@code ref<T>}, which each name read here is recorded with. */
    private final TsonSchema.SlotType type;

    ReferenceNameReader(String expected, boolean toCell, TsonSchema.SlotType type) {
        this.expected = expected;
        this.toCell = toCell;
        this.type = type;
    }

    /** The name {@code event} spells, or empty where it is no unquoted token. */
    static Optional<String> nameIn(TsonEvent event) {
        return event instanceof TokenEvent token && token.form() == TokenForm.UNQUOTED
                ? Optional.of(token.text()) : Optional.empty();
    }

    @Override
    public Object read(TsonReadContext ctx) {
        TsonEvent event = ctx.peek();
        Optional<String> name = nameIn(event);
        if (name.isPresent()) {
            ctx.next();
            io.ltr8.tson.schema.meta.TypeRef reference = io.ltr8.tson.schema.meta.TypeRef.of(name.get());
            io.ltr8.tson.schema.meta.Ref<Object> cell = toCell ? io.ltr8.tson.schema.meta.Ref.to(name.get()) : null;
            ctx.heldByName(reference, type, cell);
            return cell != null ? cell : reference;
        }
        String found = found(event);
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected the name of " + expected + ", found " + found
                + " -- this position holds a value by name, written as dotted identifiers", "a qualified name", found);
        EventSkip.annotationsAndTypeRef(ctx);
        EventSkip.coreValue(ctx);
        return null;
    }

    /** What stood where a name was wanted, as a message says it. */
    private static String found(TsonEvent event) {
        return switch (event) {
            case TokenEvent token -> "the quoted token \"" + token.text() + "\", which is text rather than a name";
            case AnnotationStart annotation -> "the annotation '@" + annotation.name() + "', and a name carries none";
            case TypeRef typeRef -> "the type-ref '!" + typeRef.name() + "', and a name carries none: its type is "
                    + "its referent's";
            default -> TypeRefCheck.describe(event);
        };
    }
}
