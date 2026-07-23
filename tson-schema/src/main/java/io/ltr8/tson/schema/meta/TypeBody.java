package io.ltr8.tson.schema.meta;

/**
 * The resolved value of {@code type_definition.body} (Part 2 §4.1, §8.1) -- typed {@code top} in
 * the kernel, i.e. any well-formed construction, instance, or reference value. One variant per
 * meta-kernel constructor whose own shape is simple enough to model without inventing a way to
 * represent field-group mutual-exclusion in a *bound instance* (as opposed to a *field
 * declaration*, which {@link FieldGroup} already covers). {@link IntegerType} needed no such
 * design work (its own compact constructor already enforces {@code min}/{@code exclusiveMin} and
 * {@code max}/{@code exclusiveMax} mutual exclusion directly) and joined this {@code permits} list
 * 2026-07-23; {@link TextType}/{@link UriType}/{@link RegexType} likewise needed none (every field
 * across all three is {@code Optional}) and joined the same day. {@code SchemaResolver} doesn't
 * resolve anything to any of these four via ordinary schema-grammar resolution -- constructor-
 * application instances ({@code !integer_type {}}, {@code !text_type {}}, and friends, §5.5) are
 * bound by hand instead, see {@code MetaKernelParser}.
 */
public sealed interface TypeBody permits RecordBody, Reference, Unit, EnumBody, ChoiceBody, ArrayBody, MapBody, TupleBody, IntegerType, TextType, UriType, RegexType {
}
