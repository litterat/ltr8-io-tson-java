package io.ltr8.tson.compiler.ast.schema;

/**
 * The size specifier after {@code ;} in an {@link ArrayExpr} or a {@link MapExpr} (Part 2 §12.1, §5.3):
 * {@code size-spec = size-bound [ws ".." ws [size-bound]] / ".." ws size-bound}.
 * Four surface spellings, kept structurally distinct here rather than collapsed -- collapsing
 * {@code N} into {@code N..N} is a resolution-time equivalence (§5.3: "two spellings of the same
 * application"), not a grammar fact, and this stage only builds the grammar's own AST.
 *
 * <p>Each bound is preserved as raw token text, not parsed to a number: the bound is read where the
 * lifted form binds it, against {@code array}'s own {@code min_items}/{@code max_items}, so a token that is
 * no {@code decimal-natural} is refused there -- a template's parameter among them, which stands only where
 * a type is named (§5.10).
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
