package io.ltr8.tson.schema.meta;

/**
 * The meta-kernel's {@code atom => top & {}} base kind (Part 2 §4.1) -- every ATOM-kind {@link
 * TypeBody} variant IS-A this. Currently {@link Unit} (backing {@code value}/{@code token}/{@code
 * void}, the "atom with no constraint vocabulary") and {@link EnumBody} (backing {@code boolean}
 * and the kernel's other internal enumerations); the atom constraint-vocabulary families with
 * optional bound groups ({@code integer_type}, {@code text_type}, {@code uri_type}, {@code
 * regex_type}) aren't modeled as {@code TypeBody} variants yet (see its own Javadoc) and will need
 * adding to this {@code permits} list too once they are.
 */
public sealed interface Atom extends Top permits Unit, EnumBody {
}
