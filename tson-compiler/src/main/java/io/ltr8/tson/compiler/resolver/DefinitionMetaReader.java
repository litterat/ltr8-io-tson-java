package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.schema.meta.Top;

/**
 * What {@link DefinitionResolver#bindAtomInstance} uses to bind a constructor-application/atom-
 * refinement value against its own constructor's compiled reader -- {@code type} is the constructor
 * name ({@code value.typeRef()}'s own value, already attached by the caller), {@code value} the
 * already-normalized (record-form) data to read.
 *
 * <p>A required constructor parameter of {@link DefinitionResolver}, not threaded per call --
 * {@link DefinitionResolver} has no dependency on {@code reader} at all; a caller
 * with a real compiled reader (e.g. {@link TsonSchemaResolver}) supplies one of these wrapping it
 * (typically a one-line lambda, {@code (type, value) -> (Top) metaParser.get(type).read(value)}).
 * {@link MetaKernelBootstrapResolver} never actually reaches {@link
 * DefinitionResolver#bindAtomInstance} at all (its own {@code instanceBody} switch handles every real
 * {@code Instance} itself), so it supplies a reader that throws if ever invoked, rather than passing
 * {@code null}.
 */
@FunctionalInterface
interface DefinitionMetaReader {

    Top read(String type, DataValue value);
}
