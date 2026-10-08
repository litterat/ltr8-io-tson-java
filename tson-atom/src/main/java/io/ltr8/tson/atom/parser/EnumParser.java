package io.ltr8.tson.atom.parser;

import java.util.Optional;

import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomValidationException;
import io.ltr8.tson.atom.BuiltinTypeVocabulary;
import io.ltr8.tson.base.unicode.Normalization;
import io.ltr8.tson.schema.meta.EnumBody;
import java.util.List;

/**
 * Parses and validates against an enum -- an instance of meta-kernel's {@code enum_type} or a tightening of it
 * such as {@code enum} or {@code text_enum} (§7.4). Holds an {@link EnumBody} -- the pure constraint values --
 * rather than declaring those fields itself.
 *
 * <p><b>Matches on the token's text directly, never through {@code BaseTypeResolver}'s
 * boolean/number/string identification.</b> This is the one thing that makes {@code boolean
 * => !enum [true false]} readable at all: routed through generic identification (as {@code
 * MetaKernelBootstrapResolver}'s own binding of the *schema* {@code !enum [true false]} instance necessarily
 * is, since it binds via {@code TsonObjectReader}'s ordinary array/atom path), {@code "true"}/
 * {@code "false"} get identified as actual TSON booleans before {@code EnumBody.members: List
 * <String>} ever sees them -- a real, permanent limit of generic binding (see this repo's own
 * CLAUDE.md). This class exists specifically for callers -- {@code reader}'s
 * {@code EnumTypeParserFactory} chief among them -- that already know, from a schema position
 * rather than from identifying the token itself, that an enum match is what's wanted here: checking the
 * token's text against {@link EnumBody#members} directly never invokes identification at all, so the
 * collision simply doesn't arise. It matches by text alone and <b>cannot do otherwise</b> -- an
 * {@link AtomType} is handed a {@code String} and never sees which form carried it, which is the same
 * form-agnostic behavior {@code MetaKernelBootstrapResolver}'s own hand-written enum converter already uses,
 * "correct for every enum member regardless of what it happens to look like".
 *
 * <p><b>The match is in the label type's form</b> ({@link #form}): an enum over a case-folding identifier admits
 * a member however it is cased, and the value is the token in that form. The form is not on the body -- the label
 * type may live in the governing meta -- so the caller supplies what linking recorded.
 *
 * <p><b>Not registered in {@link BuiltinTypeVocabulary} and has no {@code TYPENAME}</b>: {@code enum} is a
 * Part 2 schema constructor rather than a name a schemaless document could write, since the members are the
 * author's. Its two published <em>instances</em> are registered -- {@link BooleanParser} under
 * {@code boolean}, which reads the host values its members stand for rather than their text, and that is
 * the one case this class deliberately does not serve.
 */
public record EnumParser(EnumBody constraints, Normalization form) implements AtomTypeParser<String> {

    /** An enum whose label type keeps its text as written. */
    public EnumParser(EnumBody constraints) {
        this(constraints, Normalization.NONE);
    }

    public EnumParser(List<String> members) {
        this(new EnumBody(members));
    }

    /**
     * The member the token is, compared in {@link #form} -- the normalization of the enum's label type
     * ([TSON-SCHEMA] §5.5) -- so under a case-folding type {@code Content-Type} is the member written
     * {@code content-type}. The value is the token in that form. Each member is put into the form as it is
     * compared, which allocates nothing for a member already in it.
     */
    @Override
    public String read(String text) {
        String value = form.apply(text);
        for (String member : constraints.members()) {
            if (form.apply(member).equals(value)) {
                return value;
            }
        }
        throw new AtomValidationException(
                TextParser.subject(text, value, form) + " is not a member of this enum -- expected one of "
                        + constraints.members(),
                "one of (" + String.join(", ", constraints.members()) + ")");
    }

    @Override
    public String write(String value) {
        return value;
    }

    /** This family already reads to text, so a string target is its own value and nothing else is. */
    @Override
    public Optional<AtomType<?>> boundTo(Class<?> target) {
        return natural(String.class, target);
    }

}
