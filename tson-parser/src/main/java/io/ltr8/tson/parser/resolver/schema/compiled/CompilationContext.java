package io.ltr8.tson.parser.resolver.schema.compiled;

/**
 * What a {@link TsonParserFactory} uses to turn a field/element/key/value/variant's own {@code
 * type_ref} name into a {@link ParserHandle} for it, without the factory itself needing to
 * know anything about {@link TsonSchemaParser}'s build order, its "currently building" stack, or
 * memoization -- {@link TsonSchemaParser}'s own compiler is the only implementation, handed to
 * each factory call already bound to that one compilation.
 *
 * <p>Takes a bare name, not a full {@code io.ltr8.tson.schema.meta.TypeRef} -- by the time a
 * {@link TsonSchemaParser} is compiling from the registry's own materialized {@code TsonSchema}
 * (see {@link TsonSchemaParser#compile}'s own Javadoc), {@code SchemaValidator}'s own
 * materialization pass has already rewritten every argument-bearing {@code type_ref} reachable
 * from a body into a bare reference to a synthesized entry, so nothing this layer resolves should
 * ever still carry arguments of its own.
 */
@FunctionalInterface
public interface CompilationContext {

    ParserHandle<?> resolve(String typeName);
}
