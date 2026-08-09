package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code atom => top & {}} base kind (Part 2 §4.1) -- every ATOM-kind {@link
 * Top} variant IS-A this. {@link Unit} backs {@code value}/{@code token}/{@code void} (the "atom
 * with no constraint vocabulary"); {@link EnumBody} backs {@code boolean} and the kernel's other
 * internal enumerations; the remaining variants are the atom constraint-vocabulary families, one
 * per {@code *_type} constructor. {@link UnknownType} is the sibling SUM-kind case -- see {@link
 * Sum}, not here.
 */
public sealed interface Atom extends Top permits Unit, EnumBody, IntegerType, TextType, UriType, RegexType,
        DecimalType, FloatType, RationalType, UuidType, BinaryType, DateType, TimeType, DateTimeType, DurationType,
        Cidr4Type, Cidr6Type, EmailType, MacType, Ipv4Type, Ipv6Type, ComplexType {

    /**
     * Reports how {@code refined} fails to narrow this atom's own constraints -- an empty list
     * means it is a valid refinement of this one (§5.7: a refinement tightens, it never loosens).
     * Each element is a human-readable fragment naming one offending facet; a caller composes them
     * into its own error, so several problems surface in one pass rather than one per re-run.
     *
     * <p>A constraint family is the only thing that knows what "more constrained" means for its own
     * fields -- an integer's {@code size}/{@code min}/{@code max}, a text's {@code
     * min_length}/{@code max_length} -- so the rule lives on the family rather than in a generic
     * field-by-field comparison, which cannot tell a tightened bound from a replaced one. The
     * default admits everything, which is correct for a family carrying no orderable facet at all
     * ({@link Unit}); every family that has one overrides it.
     *
     * <p>{@code refined} is the fully merged result of applying a refinement body to this atom, not
     * the body alone (see {@code DefinitionResolver}'s own atom-refinement path), so a facet the
     * body never mentioned still holds this atom's own value and compares equal -- which is exactly
     * why an unchanged facet has to tighten <em>vacuously</em> rather than being treated as a
     * restatement that must strictly narrow.
     *
     * <p>An implementation compares facets only where narrowing is decidable by ordinary value
     * comparison. Where it is not -- a {@code pattern} against another {@code pattern} (regular
     * language containment, which {@code tson-schema} deliberately cannot reach, having no
     * dependency on {@code tson-regex}), or an ISO 8601 duration carried as unparsed text -- the
     * facet is left unchecked rather than guessed at, and each implementation names its own gaps.
     */
    default List<String> constraintsCheck(Atom refined) {
        return List.of();
    }
}
