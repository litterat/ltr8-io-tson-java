package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * Builds one {@link TsonTypeParser} for one resolved {@link TypeDefinition}, given a way to resolve
 * its own children. Unlike an earlier sketch of this ({@code ParserFactorySet}, a single interface
 * with one fixed Java method per composite kind), there is exactly one of these per *meta-kernel/
 * meta-schema constructor name* (`record`, `array`, `map`, `tuple`, `choice`, `enum`, `unit`,
 * `integer_type`, `text_type`, ... -- every {@code ~}-marked entry meta-kernel or a meta-schema like
 * {@code meta.tn1} declares), looked up dynamically by name through a {@link ParserFactoryRegistry}
 * rather than called through a fixed method -- see that class's own Javadoc for why: it's what
 * lets "which constructors exist" be a property of *which meta-schema* is governing (meta-kernel's
 * own closed set today; nothing stops a caller's own extended meta-schema declaring more later)
 * rather than a fixed, closed Java interface.
 *
 * <p>Convention (not enforced by the compiler, just followed consistently so far): each
 * constructor's own compiled-parser class holds its own {@code FACTORY} as a {@code static final
 * TsonParserFactory} constant -- {@link RecordParser#FACTORY}, {@code IntegerTypeParserFactory
 * .FACTORY}, {@code EnumTypeParserFactory.FACTORY} all already follow this. Package-private, not
 * public -- nothing outside this package needs to reach a specific factory directly; callers
 * assemble a {@link ParserFactoryRegistry} instead. Keeps "how to build a parser for this shape"
 * physically next to the parser class it builds, rather than in one large central registration
 * method that has to know about every shape at once.
 *
 * <p>Takes the whole {@link TypeDefinition}, not just its {@code body()} -- a factory casts {@code
 * definition.body()} to whichever concrete {@code Top} leaf it knows how to handle (that's exactly
 * what {@link ParserFactoryRegistry}'s own name-based dispatch already guarantees will match), but
 * keeping the full definition available too costs nothing and leaves room for a factory that
 * legitimately wants more (e.g. {@code definition.source()} for a diagnostic message) without a
 * signature change later.
 */
@FunctionalInterface
public interface TsonParserFactory {

    TsonTypeParser<?> create(String name, TypeDefinition definition, CompilationContext ctx);
}
