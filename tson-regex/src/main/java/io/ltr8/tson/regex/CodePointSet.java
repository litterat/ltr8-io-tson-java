package io.ltr8.tson.regex;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;

/**
 * An immutable set of Unicode code points as sorted, disjoint, merged inclusive intervals over
 * {@code [0, 0x10FFFF]}, with the boolean-algebra operations ({@link #union}/{@link #intersection}/{@link
 * #complement}) the {@linkplain RegexDisjointness disjointness} product needs. Unlike the matcher's opaque
 * {@code IntPredicate} transitions, these can be intersected and tested for emptiness -- required to explore
 * a product automaton over a Unicode-sized alphabet without enumerating it. A {@code \p{...}} category is
 * materialised once (a scan of the JDK's own Unicode data, {@link UnicodeCategories}) and cached.
 */
final class CodePointSet {

    static final int MAX = 0x10FFFF;
    static final CodePointSet EMPTY = new CodePointSet(new int[0]);

    /** Flattened inclusive interval pairs {@code [lo0, hi0, lo1, hi1, ...]}: sorted, disjoint, non-adjacent. */
    private final int[] intervals;

    private CodePointSet(int[] intervals) {
        this.intervals = intervals;
    }

    static CodePointSet of(int lo, int hi) {
        return lo > hi ? EMPTY : new CodePointSet(new int[] {lo, hi});
    }

    static CodePointSet single(int codePoint) {
        return of(codePoint, codePoint);
    }

    boolean isEmpty() {
        return intervals.length == 0;
    }

    boolean contains(int codePoint) {
        int lo = 0;
        int hi = intervals.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (codePoint < intervals[2 * mid]) {
                hi = mid - 1;
            } else if (codePoint > intervals[2 * mid + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** The interval endpoints, for computing the elementary partition; treat as read-only. */
    int[] intervals() {
        return intervals;
    }

    CodePointSet complement() {
        List<int[]> out = new ArrayList<>();
        int next = 0;
        for (int k = 0; k < intervals.length; k += 2) {
            if (intervals[k] > next) {
                out.add(new int[] {next, intervals[k] - 1});
            }
            next = intervals[k + 1] + 1;
        }
        if (next <= MAX) {
            out.add(new int[] {next, MAX});
        }
        return fromIntervals(out);
    }

    static CodePointSet union(CodePointSet a, CodePointSet b) {
        if (a.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return a;
        }
        List<int[]> raw = new ArrayList<>();
        addPairs(raw, a.intervals);
        addPairs(raw, b.intervals);
        return fromIntervals(raw);
    }

    static CodePointSet intersection(CodePointSet a, CodePointSet b) {
        List<int[]> out = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.intervals.length && j < b.intervals.length) {
            int lo = Math.max(a.intervals[i], b.intervals[j]);
            int hi = Math.min(a.intervals[i + 1], b.intervals[j + 1]);
            if (lo <= hi) {
                out.add(new int[] {lo, hi});
            }
            if (a.intervals[i + 1] < b.intervals[j + 1]) {
                i += 2;
            } else {
                j += 2;
            }
        }
        return fromIntervals(out);
    }

    // ── Category materialisation (cached) ──────────────────────────────────────

    private static final EnumMap<RegexCategory, CodePointSet> CATEGORY_CACHE = new EnumMap<>(RegexCategory.class);

    static synchronized CodePointSet ofCategory(RegexCategory category) {
        return CATEGORY_CACHE.computeIfAbsent(category, CodePointSet::buildCategory);
    }

    private static CodePointSet buildCategory(RegexCategory category) {
        List<int[]> out = new ArrayList<>();
        int runStart = -1;
        for (int cp = 0; cp <= MAX; cp++) {
            if (UnicodeCategories.matches(category, cp)) {
                if (runStart < 0) {
                    runStart = cp;
                }
            } else if (runStart >= 0) {
                out.add(new int[] {runStart, cp - 1});
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            out.add(new int[] {runStart, MAX});
        }
        return fromIntervals(out);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static void addPairs(List<int[]> raw, int[] flat) {
        for (int k = 0; k < flat.length; k += 2) {
            raw.add(new int[] {flat[k], flat[k + 1]});
        }
    }

    /** Sorts by low bound and coalesces overlapping or adjacent intervals into a normalised set. */
    private static CodePointSet fromIntervals(List<int[]> raw) {
        if (raw.isEmpty()) {
            return EMPTY;
        }
        raw.sort(Comparator.comparingInt(p -> p[0]));
        List<int[]> merged = new ArrayList<>();
        int[] current = raw.get(0).clone();
        for (int k = 1; k < raw.size(); k++) {
            int[] p = raw.get(k);
            if (p[0] <= current[1] + 1) {
                current[1] = Math.max(current[1], p[1]);
            } else {
                merged.add(current);
                current = p.clone();
            }
        }
        merged.add(current);
        int[] flat = new int[merged.size() * 2];
        for (int k = 0; k < merged.size(); k++) {
            flat[2 * k] = merged.get(k)[0];
            flat[2 * k + 1] = merged.get(k)[1];
        }
        return new CodePointSet(flat);
    }
}
