package io.ltr8.tson.schema.meta;

/**
 * The resolved value of {@code type_definition.body} (Part 2 §4.1, §8.1) -- typed {@code top} in
 * the kernel, i.e. any well-formed construction, instance, or reference value. One variant per
 * meta-kernel constructor whose own shape is simple enough to model without inventing a way to
 * represent field-group mutual-exclusion in a *bound instance* (as opposed to a *field
 * declaration*, which {@link FieldGroup} already covers): the atom constraint-vocabulary families
 * with optional bound groups ({@code integer_type}, {@code text_type}, {@code uri_type}, {@code
 * regex_type}) are deliberately not modeled yet, and {@code SchemaResolver} doesn't resolve
 * anything to them either -- both are later, separate work.
 */
public sealed interface TypeBody permits RecordBody, Reference, Unit, EnumBody, ChoiceBody, ArrayBody, MapBody, TupleBody {
}
