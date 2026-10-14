package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.EventSkip;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;
import io.ltr8.tson.schema.meta.QualifiedName;

import java.util.Optional;

/**
 * A record-typed position whose bound component holds a {@link QualifiedName}: the value is written by name
 * (SPEC-FEEDBACK.md #13), and this reads the name as written.
 *
 * <p><b>What a name is, stated once for both readers that ask</b> ({@link #nameIn}): an <b>unquoted</b> token
 * whose text splits at each {@code .} into segments that are each a [TSON-DATA] §7.7 identifier. A quoted token
 * is text, never a name, and a token that is not one -- {@code 42}, {@code a..b} -- is read as whatever else
 * the position admits. {@link RecordAbstractReader} asks the same question to decide when §5.6's positional form
 * loses the token, so the two cannot disagree about which tokens are names.
 *
 * <p><b>Nothing is resolved here.</b> The name is the author's, kept as written; the linker resolves it against
 * the governed schema's namespace and judges the referent against the slot's declared type, as it judges a
 * {@code type_ref}. A data document's read resolves neither.
 *
 * <p><b>Names only.</b> The component holds a name and nothing else, so a braced value here is a mismatch the
 * binding declares rather than one the schema does: a class that wants a record value at a record-typed slot
 * declares that record's class, as it always has. A framed name ({@code @doc:"x" orders}, {@code !record x}) is
 * refused too: a name's type is its referent's, and an annotation would have nowhere to go.
 */
final class QualifiedNameReader implements TsonTypeReader<QualifiedName> {

    /** The position's declared type, as a message names it. */
    private final String typeName;

    QualifiedNameReader(String typeName) {
        this.typeName = typeName;
    }

    /** The name {@code event} spells, or empty where it is no unquoted token well-formed as a qualified name. */
    static Optional<QualifiedName> nameIn(TsonEvent event) {
        if (!(event instanceof TokenEvent token) || token.form() != TokenForm.UNQUOTED) {
            return Optional.empty();
        }
        return QualifiedName.split(token.text()).filter(name -> name.segments().stream()
                .allMatch(segment -> IdentifierGrammar.validate(segment).isEmpty()));
    }

    @Override
    public QualifiedName read(TsonReadContext ctx) {
        TsonEvent event = ctx.peek();
        Optional<QualifiedName> name = nameIn(event);
        if (name.isPresent()) {
            ctx.next();
            return name.get();
        }
        String found = found(event);
        ctx.report(Diagnostic.Code.TYPE_MISMATCH, "expected the name of a '" + typeName + "' value, found " + found
                + " -- this position holds a value by name, written as dotted identifiers", "a qualified name", found);
        EventSkip.annotationsAndTypeRef(ctx);
        EventSkip.coreValue(ctx);
        return null;
    }

    /** What stood where a name was wanted, as a message says it. */
    private static String found(TsonEvent event) {
        return switch (event) {
            case TokenEvent token when token.form() == TokenForm.UNQUOTED -> "the token '" + token.text() + "'";
            case TokenEvent token -> "the quoted token \"" + token.text() + "\", which is text rather than a name";
            case AnnotationStart annotation -> "the annotation '@" + annotation.name() + "', and a name carries none";
            case TypeRef typeRef -> "the type-ref '!" + typeRef.name() + "', and a name carries none: its type is "
                    + "its referent's";
            default -> TypeRefCheck.describe(event);
        };
    }
}
