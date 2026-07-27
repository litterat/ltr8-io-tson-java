package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonValueReader;
import io.ltr8.tson.schema.meta.TypeDefinition;

/**
 * Builds one {@link TsonValueReader} for one resolved {@link TypeDefinition}, given a way to resolve
 * its own children. There is exactly one of these per *meta-kernel/meta-schema constructor name*
 * (`record`, `array`, `map`, `tuple`, `choice`, `enum`, `unit`, `integer_type`, `text_type`, ... --
 * every {@code ~}-marked entry meta-kernel or a meta-schema like {@code meta.tn1} declares) -- it's
 * what lets "which constructors exist" be a property of *which meta-schema* is governing (meta-kernel's
 * own closed set today; nothing stops a caller's own extended meta-schema declaring more later)
 * rather than a fixed, closed Java interface.
 *
 * <p>Takes {@code typeName} as its own first argument so {@link TsonSchemaCompiler} can make one
 * uniform call -- {@code factory.create(typeName, name, definition, ctx)} -- regardless of whether
 * {@code factory} is a single-shape implementation (which ignores the argument, the common case:
 * {@link RecordParser#FACTORY}, {@link ArrayParser#FACTORY}, every constant on {@link
 * AtomTypeParser}, ...) or {@link TsonParserFactoryRegistry} itself, whose own {@code create} does
 * the name-keyed lookup and delegates to whichever concrete factory is registered. This keeps the
 * compiler decoupled from {@code TsonParserFactoryRegistry} as a concrete type -- a caller wanting
 * single-constructor test coverage can hand {@link TsonSchemaCompiler#compile} a bare lambda
 * instead of assembling a whole registry.
 *
 * <p>Convention, not enforced: each *composite* constructor's own compiled-parser class holds its
 * own {@code FACTORY} as a package-private {@code static final} constant ({@link
 * RecordParser#FACTORY}, {@link ArrayParser#FACTORY}) -- callers assemble a {@link
 * TsonParserFactoryRegistry} rather than reaching a specific factory directly, keeping "how to build
 * a parser for this shape" next to the parser class it builds. Every *atom-family* factory instead
 * lives as one of these same constants directly on {@link AtomTypeParser} (see its own Javadoc for
 * why).
 *
 * <p>Takes the whole {@link TypeDefinition}, not just its {@code body()} -- a factory casts {@code
 * definition.body()} to whichever concrete {@code Top} leaf it knows how to handle, but keeping the
 * full definition available too costs nothing and leaves room for a factory that legitimately wants
 * more (e.g. {@code definition.source()} for a diagnostic message) without a signature change later.
 */
@FunctionalInterface
public interface TsonParserFactory {

    TsonValueReader<?> create(String typeName, String name, TypeDefinition definition, CompilationContext ctx);
}
