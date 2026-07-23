package io.ltr8.tson.parser.ast.schema;

/**
 * The array size specifier after {@code ;} in a declaration-level {@link ArrayContainerDef} (Part
 * 2 §12.1, §5.3): {@code size-spec = size-bound [ws ".." ws [size-bound]] / ".." ws size-bound}.
 * Four surface spellings, kept structurally distinct here rather than collapsed -- collapsing
 * {@code N} into {@code N..N} is a resolution-time equivalence (§5.3: "two spellings of the same
 * application"), not a grammar fact, and this stage only builds the grammar's own AST.
 *
 * <p>Each bound is preserved as raw token text, not parsed to a number: within a template body a
 * bound MAY be a value-parameter name instead of a {@code decimal-natural} literal (§5.3), and
 * classifying which is a later, semantic-layer step ("parameters cannot be numeric" is the
 * disambiguation rule, but nothing here needs to act on it yet).
 */
public sealed interface SizeSpec {

    /** {@code N} -- exactly N elements. */
    record Exact(String bound) implements SizeSpec {
    }

    /** {@code N..M} -- bounded range, N MUST be ≤ M once both are concrete (checked at resolution). */
    record Ranged(String lower, String upper) implements SizeSpec {
    }

    /** {@code N..} -- at least N elements. */
    record Min(String lower) implements SizeSpec {
    }

    /** {@code ..M} -- at most M elements. */
    record Max(String upper) implements SizeSpec {
    }
}
