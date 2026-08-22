package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The "compile" stage's own noun -- an already-built, immutable {@code Map<String,
 * TsonTypeReader<?>>} paired with the {@link TsonLinkedSchema} it was compiled from, produced by
 * {@link TsonSchemaCompiler#compile} and never constructed directly outside this package. Holds no
 * build logic of its own; all the actual compile-time work (the eager walk, cycle detection,
 * per-entry build-failure deferral) lives in {@link TsonSchemaCompiler} itself, matching the
 * verb/noun split this project's own pipeline vocabulary uses everywhere else ({@code
 * TsonSchemaLinker}/{@code TsonLinkedSchema}, {@code TsonSchemaResolver}/its own resolved {@code
 * TsonSchema}).
 *
 * <p>The compile output for *any* resolved schema. A meta-layer schema (one whose {@code !!meta} is
 * meta-kernel) compiles to the {@link TsonCompiledMetaSchema} subtype, which adds the scoped
 * constructor vocabulary needed to *govern* another schema's compilation; every other schema compiles
 * to a bare {@code TsonCompiledSchema}, which can be read but never used as a governing meta. So the
 * type itself records whether a compiled schema is allowed to govern.
 *
 * <p>{@link #get} reads *any* entry, unscoped -- unlike {@link TsonCompiledMetaSchema#reader}, which
 * is deliberately scoped to only the entries a governing meta-schema itself declares as constructors
 * (§3.3.1's structure-namespace rule).
 */
public sealed class TsonCompiledSchema permits TsonCompiledMetaSchema {

    /** How many declared names {@link #unknownTypeMessage} spells out before summarizing the rest. */
    private static final int NAMES_IN_MESSAGE = 8;

    private final TsonLinkedSchema linkedSchema;
    private final Map<String, TsonTypeReader<?>> entries;

    public TsonCompiledSchema(TsonLinkedSchema linkedSchema, Map<String, TsonTypeReader<?>> entries) {
        this.linkedSchema = linkedSchema;
        this.entries = entries;
    }

    /**
     * The {@link TsonLinkedSchema} this was compiled from -- package-private, so the {@link
     * TsonCompiledMetaSchema} subtype can pass it to {@code super} when built from an existing base schema.
     */
    TsonLinkedSchema linkedSchema() {
        return linkedSchema;
    }

    /**
     * The compiled readers, by entry name -- package-private, same reason as {@link #linkedSchema()}. The map
     * is already immutable ({@link TsonSchemaCompiler} copies it before construction).
     */
    Map<String, TsonTypeReader<?>> entries() {
        return entries;
    }

    public TsonTypeReader<?> get(String typeName) {
        TsonTypeReader<?> parser = entries.get(typeName);
        if (parser == null) {
            throw new IllegalArgumentException(unknownTypeMessage(typeName));
        }
        return parser;
    }

    /**
     * Every declared type name, in schema order, joined the way a record reader joins its own closed field
     * list -- the closed set a type-ref at a root position may name, for a diagnostic's machine-readable
     * {@code expected}. Order comes from the resolved schema's entries (insertion-ordered), not from
     * {@link #entries}, which is an unordered immutable copy of the same name set.
     */
    String declaredTypeNames() {
        return String.join(" | ", schema().entries().keySet());
    }

    /**
     * Why {@code typeName} doesn't resolve, told the way an unrecognized record field is told (§7.2's closed
     * field list): name what <em>is</em> declared, so the fix takes one step instead of a guess.
     *
     * <p>Unlike a record's field list, this namespace is not necessarily small -- an {@code !!import}
     * flattens the imported schema's entries into this one, so a schema declaring one type over core.tn has
     * ~50 names, and spelling all of them buries the one the author wrote. So the prose leads with the
     * nearest declared name when there is one, and lists only the first few. The full set stays available
     * through {@link #declaredTypeNames} for the machine-readable end of a {@code Diagnostic}.
     */
    String unknownTypeMessage(String typeName) {
        Collection<String> names = schema().entries().keySet();
        List<String> shown = names.stream().limit(NAMES_IN_MESSAGE).toList();
        StringBuilder message = new StringBuilder("'").append(typeName)
                .append("' is not in this compiled schema, whose types are (").append(String.join(" | ", shown));
        if (names.size() > shown.size()) {
            message.append(", and ").append(names.size() - shown.size()).append(" more");
        }
        message.append(")");
        nearestTypeName(typeName).ifPresent(near -> message.append(" -- did you mean '").append(near).append("'?"));
        return message.toString();
    }

    /**
     * The declared name closest to {@code typeName} by edit distance, if one is close enough to be worth
     * suggesting -- a typo, not a different name that happens to share a few letters. The tolerance grows
     * with the name's own length (one edit for a short name, at most three for any), which is the same shape
     * javac and rustc use for their own "did you mean". Ties go to the first in declaration order.
     */
    private Optional<String> nearestTypeName(String typeName) {
        int tolerance = Math.clamp(typeName.length() / 3, 1, 3);
        String nearest = null;
        int best = tolerance + 1;
        for (String candidate : schema().entries().keySet()) {
            int distance = editDistance(typeName, candidate);
            if (distance < best) {
                best = distance;
                nearest = candidate;
            }
        }
        return Optional.ofNullable(nearest);
    }

    /**
     * Optimal string alignment distance -- Levenshtein plus a one-edit transposition, because swapping two
     * adjacent characters ({@code frist} for {@code first}) is the typo a suggestion most needs to forgive
     * and plain Levenshtein charges two edits for. Three rolling rows rather than a full matrix; only ever
     * run on a failed lookup, which is already on its way to an error.
     */
    private static int editDistance(String a, String b) {
        int[] beforePrevious = new int[b.length() + 1];
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    current[j] = Math.min(current[j], beforePrevious[j - 2] + 1);
                }
            }
            int[] swap = beforePrevious;
            beforePrevious = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * Where a read that entered through {@code typeName} should root its schema pointer: the name the
     * author wrote, not the entry it resolves to.
     *
     * <p>The two differ exactly when the name is an alias for something the resolver minted -- {@code
     * order_response => paged<order>} compiles to the instantiation entry's own reader, and that reader,
     * shared by every entry point, cannot know which of its names a given read arrived through. So the
     * facade seeds this before the reader runs; {@code inRecord} then keeps the pointer and re-anchors only
     * the identity and line, which is the interaction those two methods were already written for.
     *
     * <p>Empty for a name this schema does not declare -- the caller reports that as an unknown type rather
     * than reading anything.
     */
    Optional<SchemaLocation> rootDeclaration(String typeName) {
        return Optional.ofNullable(schema().entries().get(typeName))
                .map(entry -> SchemaLocation.of(linkedSchema.originOf(typeName), typeName, entry.position()));
    }

    /**
     * The reader for {@code typeName}, or empty if this schema has none -- the non-throwing
     * counterpart to {@link #get}, for a caller that treats an absent entry as a normal outcome
     * (e.g. building a governing meta's scoped constructor vocabulary, where a placeholder schema
     * legitimately has no readers yet).
     */
    public Optional<TsonTypeReader<?>> find(String typeName) {
        return Optional.ofNullable(entries.get(typeName));
    }

    /**
     * The resolved {@link TsonSchema} this was compiled from -- e.g. so a caller that only has a
     * compiled reader (such as {@code TsonCompiledSchemaLoader}) can still reach its own resolved
     * {@code entries()} without a separate lookup.
     */
    public TsonSchema schema() {
        return linkedSchema.schema();
    }
}
