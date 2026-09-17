package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The meta-kernel's {@code template} body -- the body of an entry that declares type parameters, which
 * [TSON-SCHEMA] §5.10 calls open. {@link #template} is the constructor application as written, held and
 * <em>unread</em> until materialisation substitutes the parameters away; {@link #parameters} are the names
 * it binds.
 *
 * <p><b>It holds in both directions</b>: a {@link TypeDefinition} whose body is one of these declares type
 * parameters, and every entry that declares them has one. §5.10's partial application is no exception --
 * {@code <B> pair<uuid, B>} holds the {@code !reference { target: pair<uuid, B> }} §8.1 says it denotes.
 *
 * <p><b>Why the body is text, and not a value of the constructor's own vocabulary.</b> A parameter stands
 * wherever a token stands: {@code element_type: T} in a type slot, {@code min_items: N} in a value slot,
 * {@code variants: [T error]} inside a collection. So a body carrying one is not typed by any constructor's
 * record shape until it closes, and writing it as though it were leaves the two halves disagreeing -- a
 * value parameter refuses to read at all ({@code N} is not a {@code non_negative_integer}), and a type
 * parameter reads as a reference to a type nobody declared. Text is what "held" means, and it is what §8.1's
 * ingest rule already asks for: an open entry's body is re-resolved as source.
 *
 * <p><b>The text is authoritative; the parsed form is what compares.</b> Identity is derived from the parsed
 * application -- an open synthetic is named from its held binding record with the parameters renamed
 * positionally -- so two spellings of one form reduce to one entry and whitespace is free. This record's own
 * {@link #equals} is textual, which is sound here because one emitter writes every body this resolver
 * produces; a comparison across producers parses both sides first.
 *
 * <p><b>Which is why nothing here holds an AST.</b> The parsed form is a working value of the phase that
 * needs it, not part of what an entry <em>is</em>: {@code tson-compiler}'s {@code HeldBody} parses this text
 * once and answers the questions substitution and the declaration-time checks ask of it. Keeping the AST
 * here instead would put a grammar type in the value model and, since {@code DataValue} lives one module up,
 * could not be done at all without inverting the dependency this package deliberately keeps.
 *
 * <p><b>Composes with {@code top} directly</b>, like {@link Reference} and {@link Data}: it describes no
 * value's shape, and nothing is ever typed by it.
 *
 * <p><b>{@link #extension} is the parent's, and only a record-bodied template has one.</b> Absent means this
 * template is no type, so naming it at a type position is an error; present, it is ABSTRACT or SEALED and
 * never OPEN or FINAL, a parent having no direct instances (nothing can write a value whose type is the
 * template rather than one of its applications) and its applications being subtypes by construction.
 *
 * <p>It is <em>derived</em> rather than stated, in the manner of {@code choice.disjoint} -- which is what
 * keeps it apart from the author's {@code @abstract} mark. That mark is the <em>instantiation's</em> fact and
 * travels inside {@link #template}'s own text, so the two levels have one carrier each and never collide.
 * Optional with no default, because absence is a fact no member of the enum spells: a default would be
 * omitted from output at its own value (§8.1), and deriving the answer from the body shape instead would
 * mean parsing the held text -- the one thing §1.3 promises a resolved-output consumer never has to do.
 */
@Typename(name = "template")
public record TemplateBody(List<String> parameters, String template,
                            Optional<RecordExtensionType> extension,
                            List<String> discriminators) implements Top {

    /**
     * <b>{@link #discriminators} names the fields a SEALED family base dispatches on</b>, in the order their
     * pins are compared as a tuple -- the same statement {@code record.discriminators} makes for a closed
     * base, so a dispatcher reads one field whichever kind of base it has.
     *
     * <p><b>Absent and empty say the same thing here</b>, unlike {@link #extension}, whose absence is the
     * distinct fact that this template is no type at all and which no member of the enum spells. A template
     * with no discriminators is ABSTRACT or not a family base, and either way the list is nothing.
     */
    @Record
    public TemplateBody {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(extension, "extension");
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("a held body belongs to an entry that declares type "
                    + "parameters, and a template with none is a closed entry (§5.10)");
        }
        parameters = List.copyOf(parameters);
        discriminators = discriminators == null ? List.of() : List.copyOf(discriminators);
    }

    /** The same body with {@code discriminators} unstated -- every producer, until they are wired. */
    public TemplateBody(List<String> parameters, String template,
                         Optional<RecordExtensionType> extension) {
        this(parameters, template, extension, List.of());
    }
}
