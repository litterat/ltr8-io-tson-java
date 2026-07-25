package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.resolver.vocab.EnumParser;
import io.ltr8.tson.schema.meta.EnumBody;

/**
 * The {@link TsonParserFactory} for meta-kernel's {@code enum} constructor (§4.1) -- same shape as
 * {@link IntegerTypeParserFactory}, wrapping {@link EnumParser} via {@link AtomTypeParser}.
 * Registered under {@code "enum"}.
 *
 * <p>This is what makes {@code boolean => !enum [true false]}'s own data positions readable in
 * this package at all: {@link EnumParser} matches {@link
 * io.ltr8.tson.parser.ast.TokenValue#text()} directly against {@link EnumBody#members}, never
 * through {@code BaseTypeResolver}'s null/boolean/number/string identification -- see {@link
 * EnumParser}'s own Javadoc for why that's exactly the collision {@code boolean}'s own members
 * would otherwise hit.
 */
final class EnumTypeParserFactory {

    static final TsonParserFactory FACTORY = (name, definition, ctx) -> {
        EnumBody constraints = (EnumBody) definition.body();
        return new AtomTypeParser<>(new EnumParser(constraints));
    };

    private EnumTypeParserFactory() {
    }
}
