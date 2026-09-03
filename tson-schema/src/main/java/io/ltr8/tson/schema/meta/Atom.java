package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code atom => top & {}} base kind (Part 2 §4.1) -- every ATOM-kind {@link
 * Top} variant IS-A this. {@link Unit} backs {@code value}/{@code token}/{@code void} (the "atom
 * with no constraint vocabulary"); {@link EnumBody} backs {@code boolean} and the kernel's other
 * internal enumerations; the remaining variants are the atom constraint-vocabulary families, one
 * per {@code *_type} constructor. {@link Scoped} is the sibling SUM-kind case -- see {@link Sum}, not
 * here.
 */
public sealed interface Atom extends Top permits Unit, EnumBody, IntegerType, TextType, UriType, RegexType,
        DecimalType, FloatType, RationalType, UuidType, BytesType, DateType, TimeType, DateTimeType, DurationType,
        PeriodType,
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

    /**
     * Reports how this atom's own constraint fields contradict <em>each other</em> -- an empty list
     * means the body is internally coherent. The {@link #constraintsCheck} twin, asking the other
     * question: that one compares a refinement against its source (§5.7's tightening rule, a relation
     * between two bodies), this one judges a single body on its own, with nothing to compare it
     * against but itself.
     *
     * <p>Nothing else asks it. A facet pair admitting no value at all -- {@code min_length: 10
     * max_length: 3}, {@code min: 10 max: 3}, {@code min_prefix: 40 max_prefix: 8} -- otherwise
     * resolves, links and compiles clean, and the mistake surfaces (if ever) at a read that rejects
     * every value for reasons the author never sees stated. Part 2 §7.2 puts both the rule and its
     * home in one sentence: "family coherence between bindings (e.g. {@code min <= max}) is a
     * compilation and ingest concern (§8), not data validation" -- which is why this is a resolver
     * question and not something an atom parser may decide. {@code meta.tn}'s own header {@code @doc}
     * states the same obligation from the other side: bounds are field groups so that an
     * inclusive/exclusive pair on one side is unrepresentable, while "value-level coherence (the
     * lower bound not exceeding the upper) remains a schema-load check".
     *
     * <p>A family also reports a facet outside the range the family itself fixes, where it has one:
     * {@code cidr4_type}'s {@code @doc} says prefix bounds narrow "within the family range 0-32" and
     * that "bounds outside that range are invalid at the schema level".
     *
     * <p>The same fragment convention as {@link #constraintsCheck} -- each element names one problem,
     * so a body with several reports them all in one pass -- and the same "only where it is decidable
     * by ordinary value comparison" limit. A family carrying no orderable facet at all ({@link Unit},
     * and every selector-only family) keeps the default; a family whose bounds are unparsed text
     * ({@link DurationType}) records why it does the same.
     */
    default List<String> coherenceCheck() {
        return List.of();
    }
}
