package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.Optional;

/**
 * A record-typed position whose bound component is a {@link io.ltr8.tson.schema.meta.TypeRef}: the value is a
 * reference written by name (SPEC-FEEDBACK.md #13), a qualified name such as {@code orders.methods.place_order},
 * and this reads it as written into a {@code TypeRef} whose {@code name} is the token and whose arguments are none.
 *
 * <p><b>One host shape for every reference.</b> A {@code type_ref} slot binds a {@code TypeRef}, and so does a slot
 * holding a value by name: both are references that must resolve, and differ only in how the linker judges them --
 * one segment naming a type at a {@code type_ref} slot, segments reaching a value IS-A the slot's declared type here.
 * So nothing here parses segments: the linker splits the name and applies the identifier grammar to each segment,
 * where it also resolves them.
 *
 * <p><b>What a name is, stated once for both readers that ask</b> ({@link #nameIn}): an <b>unquoted</b> token. A
 * quoted token is text, never a name. {@link RecordAbstractReader} asks the same question to decide when §5.6's
 * positional form loses the token in a schema document, so the two cannot disagree about which tokens are names.
 *
 * <p><b>Names only.</b> The component holds a reference and nothing else, so a braced value here is a mismatch the
 * binding declares rather than one the schema does: a class that wants a record value inline at a record-typed slot
 * declares that record's class -- the host's component type selects the spelling until a reference type
 * ({@code ref<T>}) makes it the slot's declaration. A framed name ({@code @doc:"x" orders}, {@code !record x}) is
 * refused too: a name's type is its referent's, and an annotation would have nowhere to go.
 */
final class ReferenceNameReader implements TsonTypeReader<io.ltr8.tson.schema.meta.TypeRef> {

    /** What the position holds, as a message names it: {@code a 'method' value}. */
    private final String expected;

    ReferenceNameReader(String expected) {
        this.expected = expected;
    }

    /** The name {@code event} spells, or empty where it is no unquoted token. */
    static Optional<String> nameIn(TsonEvent event) {
        return event instanceof TokenEvent token && token.form() == TokenForm.UNQUOTED
                ? Optional.of(token.text()) : Optional.empty();
    }

    @Override
    public io.ltr8.tson.schema.meta.TypeRef read(TsonReadContext ctx) {
        TsonEvent event = ctx.peek();
        Optional<String> name = nameIn(event);
        if (name.isPresent()) {
            ctx.next();
            return io.ltr8.tson.schema.meta.TypeRef.of(name.get());
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
