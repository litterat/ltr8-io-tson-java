package io.ltr8.tson.parser.binder;

/**
 * Resolves a schema type name (a compiled entry's own name, e.g. {@code "integer_size"}) to the
 * Java class object-binding mode should construct for it -- the general form of the "type-to-Class
 * binding" problem every serialization library eventually needs, per {@code litterat-core}'s own
 * {@code DefaultNameBinder} precedent: there is no reflection API to enumerate "every class in a
 * package," so this is necessarily a name-to-{@code Class.forName} lookup (with whatever naming
 * convention/namespace a caller's own classes follow), not an enumeration of candidates.
 *
 * <p>{@link SchemaMetaTypeNameBinder} is the default implementation, for {@code
 * io.ltr8.tson.schema.meta} specifically. A caller binding their own schema to their own Java
 * library supplies their own implementation instead -- there is no universal naming convention
 * across arbitrary consumer code, only within this library's own, self-consistent one.
 */
@FunctionalInterface
public interface TsonTypeNameBinder {

    /** @throws ClassNotFoundException if {@code schemaTypeName} has no matching Java class under this binder's own convention */
    Class<?> resolve(String schemaTypeName) throws ClassNotFoundException;
}
