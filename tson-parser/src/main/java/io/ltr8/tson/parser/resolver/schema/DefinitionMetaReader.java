package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.schema.meta.Top;

/**
 * What {@link DefinitionResolver#bindAtomInstance} uses to bind a constructor-application/atom-
 * refinement value against its own constructor's compiled parser -- {@code type} is the constructor
 * name ({@code value.typeRef()}'s own value, already attached by the caller), {@code value} the
 * already-normalized (record-form) data to read.
 *
 * <p>Replaces a bare {@code TsonCompiledSchema} parameter threaded through every declaration-level
 * method (2026-07-27, on the user's own explicit direction) -- {@link DefinitionResolver} no longer
 * references {@code resolver.schema.compiled} at all; a caller who has a real compiled reader (e.g.
 * {@link TsonSchemaResolver}) supplies one of these wrapping it (typically a one-line lambda,
 * {@code (type, value) -> (Top) metaParser.get(type).read(value)}), and {@link MetaKernelBootstrapResolver}
 * (which never actually reaches {@link DefinitionResolver#bindAtomInstance} at all -- its own
 * {@code instanceBody} switch handles every real {@code Instance} itself, see its own Javadoc)
 * supplies one that throws if this assumption is ever wrong, rather than passing {@code null}.
 */
@FunctionalInterface
interface DefinitionMetaReader {

    Top read(String type, DataValue value);
}
