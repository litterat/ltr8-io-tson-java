package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The meta-kernel's {@code template} body -- the body of an entry that declares type parameters, which
 * [TSON-SCHEMA] §5.10 calls open. {@link #template} is the constructor application as written, held and
 * <em>unread</em> until materialisation substitutes the parameters away; {@link #parameters} are what it
 * binds, in order, each with the type an argument for it is read as ({@link TemplateParam}).
 *
 * <p><b>It holds in both directions</b>: an entry whose value is one of these declares type parameters, and
 * every entry that declares them has one. §5.10's partial application is no exception --
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
 * <p><b>Composes {@code top} alone</b>: it is not a type, its open form denotes nothing, and naming it at a type
 * position is an error. A record template is not one of these -- it is a {@link RecordBody} with
 * {@link RecordBody#parameters}, a family base ({@link OpenEntry}) -- so every held body applies some other
 * constructor, and an application of it is its expansion, with whatever chain the expansion's own body gives.
 */
@Typename(name = "template")
public record TemplateBody(List<TemplateParam> parameters, String template) implements Top {

    @Record
    public TemplateBody {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(template, "template");
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("a held body belongs to an entry that declares type "
                    + "parameters, and a template with none is a closed entry (§5.10)");
        }
        parameters = List.copyOf(parameters);
    }

    /** The parameter names, in order -- what substitution binds and what an application's arity is checked against. */
    public List<String> parameterNames() {
        return parameters.stream().map(TemplateParam::name).toList();
    }

    /** The same body with each parameter replaced by {@code settled}'s entry for it, where there is one. */
    public TemplateBody withParameters(Map<String, TemplateParam> settled) {
        return new TemplateBody(parameters.stream().map(p -> settled.getOrDefault(p.name(), p)).toList(), template);
    }
}
