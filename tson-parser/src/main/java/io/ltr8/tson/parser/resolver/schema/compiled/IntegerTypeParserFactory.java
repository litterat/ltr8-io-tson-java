package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.parser.resolver.vocab.IntegerParser;
import io.ltr8.tson.schema.meta.IntegerType;

/**
 * The {@link TsonParserFactory} for meta-kernel's {@code integer_type} constructor (§5.6) -- the
 * first atom-family factory wired up (alongside {@code EnumTypeParserFactory}), standing in for
 * what every other atom-constraint family (`text_type`, `decimal_type`, ...) will eventually need
 * too: cast the resolved entry's own body to its constraint-values class, hand it straight to the
 * matching {@code resolver.vocab} parser (unchanged, no new parsing/validation logic here), and
 * adapt that into a {@link TsonTypeParser} via {@link AtomTypeParser}. Registered under {@code
 * "integer_type"} -- {@link ParserFactoryRegistry}'s own key convention -- wherever a registry is
 * assembled; every declaration whose resolved body is an {@link IntegerType} (`integer`, `int8`,
 * `uint32`, ...) is routed here regardless of which one it is, since dispatch is by body shape,
 * not declared name (see {@link ParserFactoryRegistry}'s own Javadoc).
 */
final class IntegerTypeParserFactory {

    static final TsonParserFactory FACTORY = (name, definition, ctx) -> {
        IntegerType constraints = (IntegerType) definition.body();
        return new AtomTypeParser<>(new IntegerParser(constraints));
    };

    private IntegerTypeParserFactory() {
    }
}
