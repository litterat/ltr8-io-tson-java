package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.base.BaseTypeResolver;
import io.ltr8.tson.schema.meta.EnumBody;

import java.util.List;

/**
 * Parses and validates against meta-kernel's {@code enum} constructor (§4.1, §8.1): {@code
 * members: set<token>}. Holds an {@link EnumBody} -- the pure constraint values, unchanged by this
 * split -- rather than declaring those fields itself.
 *
 * <p><b>Matches on {@link TokenValue#text()} directly, never through {@link BaseTypeResolver}'s
 * boolean/number/string identification.</b> This is the one thing that makes {@code boolean
 * => !enum [true false]} readable at all: routed through generic identification (as {@code
 * MetaKernelBootstrapResolver}'s own binding of the *schema* {@code !enum [true false]} instance necessarily
 * is, since it binds via {@code TsonObjectReader}'s ordinary array/atom path), {@code "true"}/
 * {@code "false"} get identified as actual TSON booleans before {@code EnumBody.members: List
 * <String>} ever sees them -- a real, permanent limit of generic binding (see this repo's own
 * CLAUDE.md). This class exists specifically for callers -- {@code reader}'s
 * {@code EnumTypeParserFactory} chief among them -- that already know, from a schema position
 * rather than from identifying the token itself, that an enum match is what's wanted here: reading
 * {@link TokenValue#text()} straight off the token and checking it against {@link
 * EnumBody#members} directly never invokes identification at all, so the collision simply doesn't
 * arise. Matches by text only, regardless of {@link io.ltr8.tson.compiler.ast.TokenForm} -- the same
 * form-agnostic behavior {@code MetaKernelBootstrapResolver}'s own hand-written enum converter already uses,
 * "correct for every enum member regardless of what it happens to look like".
 *
 * <p>Not part of Part 1's published built-in vocabulary (§5) -- like {@link TextParser}, never
 * registered in {@link BuiltinTypeVocabulary} and has no {@code TYPENAME} constant. {@code enum} is
 * a Part 2 schema constructor, not a schemaless annotation a Class 1 processor would ever resolve
 * on its own.
 */
public record EnumParser(EnumBody constraints) implements AtomType<String> {

    public EnumParser(List<String> members) {
        this(new EnumBody(members));
    }

    @Override
    public String read(TokenValue token) {
        String text = token.text();
        if (!constraints.members().contains(text)) {
            throw new AtomValidationException(
                    "'" + text + "' is not a member of this enum -- expected one of " + constraints.members(),
                    "one of (" + String.join(", ", constraints.members()) + ")");
        }
        return text;
    }

    @Override
    public String write(String value) {
        return value;
    }
}
