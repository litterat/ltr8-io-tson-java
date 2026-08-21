package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;

/**
 * The meta-kernel's {@code template_argument} record (Part 2 §8.1): one binding of an {@link InstanceTemplate}
 * -- <code>{ ( param: param_name | value: value | type_ref: type_ref ) }</code>, a REQUIRED field group,
 * so exactly one of the three is present.
 *
 * <p><b>{@code param} is canonical for a type slot too</b>, not only for a value slot it is the sole way to
 * fill. {@code array}'s {@code element_type} is a {@code type_ref}, and a {@code type_ref} may already name a
 * parameter, so {@code <T> !array { element_type: T }} could ride the {@code type_ref} channel -- but then one
 * binding would have two spellings, and body identity would depend on which the resolver happened to pick.
 * {@code param} means "unbound" uniformly. {@link TypeArgument} keeps its own convention, where a parameter
 * rides the <em>reference</em> channel: a token in that position is always a reference, and it has only two
 * channels to distinguish. The divergence is deliberate -- one vocabulary has three channels, the other two.
 *
 * <p>A sealed interface rather than a three-{@link java.util.Optional} record, for the reason {@link
 * TypeArgument}'s own Javadoc sets out at length: {@code Ref} holds a {@link TypeRef}, whose {@code arguments}
 * hold {@link TypeArgument}s that wrap a {@code TypeRef} right back, and {@code tson-bind}'s record resolution
 * has no cycle detection. The union defers member resolution and breaks the loop.
 */
public sealed interface TemplateArgument {

    /** An unbound slot: the name of a type parameter of the template this binding belongs to. */
    record Param(String param) implements TemplateArgument {
    }

    /** A concrete literal, read against the slot's declared type when the template closes. */
    record Value(Token value) implements TemplateArgument {
    }

    /** A concrete reference -- a type slot filled by a name rather than by a parameter. */
    record Ref(@Field("type_ref") TypeRef typeRef) implements TemplateArgument {
    }
}
