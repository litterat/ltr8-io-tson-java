package io.ltr8.tson.base.bind;

import io.ltr8.bind.DataBindContext;

import java.util.Objects;

/**
 * What a consumer's data binds to, and how strictly -- the {@link DataBindContext} that maps a schema type
 * to a Java class, and whether that class must account for every field the schema declares.
 *
 * <p><b>One value because they are two arguments to one call.</b> A registry compiles readers from a schema
 * and a context together, checking them against each other as it goes: a context with no strictness stated
 * has an unstated strictness, and a strictness with no context governs nothing. Handing them separately
 * means every caller carries the pair, and one that carries it wrongly is a deployment whose agreement check
 * silently does not run.
 *
 * <p><b>Configuration rather than policy</b>, and the distinction is the one {@code SchemaAccess} makes.
 * A {@code ProcessorPolicy} is a denial rule over documents -- what this processor will admit and spend,
 * meaningful with no schema and no class in hand. This answers a different question: which classes the
 * application brought, and what it wants done when they disagree with its schemas.
 *
 * <p><b>What is deliberately not here is anything a reader can decide for itself.</b> Whether an undeclared
 * field is discarded rather than reported is {@code ignoringUnknownFields}, a reader derivation, and it
 * stays one: nothing is compiled against it, so one endpoint may be lenient about a later version's extra
 * fields while another beside it is strict. {@link #strict} cannot be that, and the difference is not taste
 * -- the agreement check runs when a schema is <em>compiled</em>, so a reader derived afterwards has no
 * answer left to give.
 */
public final class DataBinding {

    private final DataBindContext context;
    private final boolean strict;

    private DataBinding(DataBindContext context, boolean strict) {
        this.context = context;
        this.strict = strict;
    }

    /** Binding through {@link AtomContext#defaultContext()}, strictly -- what an unconfigured deployment gets. */
    public static DataBinding standard() {
        return new DataBinding(AtomContext.defaultContext(), true);
    }

    /** Binding through {@code context}, strictly. */
    public static DataBinding of(DataBindContext context) {
        return new DataBinding(Objects.requireNonNull(context, "context"), true);
    }

    /**
     * This binding, with a class permitted to hold fewer fields than its schema declares -- silently.
     *
     * <p><b>The strict default is the asymmetry between the two ways of being wrong.</b> A strict reader that
     * is wrong says so at startup, in one message naming both sides, and is fixed in minutes; a lenient one
     * that is wrong drops a value from every document and surfaces much later as a field that mysteriously
     * holds its default.
     *
     * <p>Leniency is a real position and not merely an escape hatch -- versioned evolution, where a v1
     * consumer deliberately reads a v2 document and means to ignore what it does not know. This is where
     * that intention is written down, and it is the only path on which a field is dropped at all, every
     * other mismatch being settled before a document exists. It is silent by necessity: reporting abandons
     * the construction, so a lenient reader that reported would hand back nothing for exactly the documents
     * it exists to accept.
     *
     * <p>The narrower alternative is {@code @Unbound} on the one component that is the class's own business
     * rather than the wire's.
     */
    public DataBinding lenient() {
        return new DataBinding(context, false);
    }

    /** Where a schema type name becomes a Java class, and a value becomes an instance of one. */
    public DataBindContext context() {
        return context;
    }

    /** Whether a class must account for every field its schema declares -- true unless {@link #lenient}. */
    public boolean strict() {
        return strict;
    }

    @Override
    public String toString() {
        return strict ? "strict binding" : "lenient binding";
    }
}
