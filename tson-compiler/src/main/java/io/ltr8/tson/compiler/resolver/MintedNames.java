package io.ltr8.tson.compiler.resolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The names one phase has minted, and what each was derived from -- [TSON-SCHEMA] §8.2's freshness MUST that
 * an internal name "collides with no declared entry and no other internal entry", stated exactly.
 *
 * <p><b>Deduping by name is the identity discipline working, and is also what would hide a collision.</b>
 * Two occurrences of one form must land on one entry -- that is what lets {@code [text]} written twice be one
 * type, what lets a form written out and the same form arriving through a template agree, and what ties a
 * recursive template's knot. So a second arrival under a name is ordinarily the same form again and nothing
 * to report. By name alone that is indistinguishable from two <em>different</em> bindings that happened to
 * derive one name, which would silently merge two types.
 *
 * <p>So the derivations are compared rather than assumed equal. Both minting sites render one canonically
 * before hashing it, and those renderings are injective by construction -- two are equal exactly when the
 * bindings are -- so this is the MUST decided rather than a probability. That matters because the name's own
 * hash is 32 bits: it is a rendering, and was never load-bearing on its own.
 *
 * <p><b>Per phase, not across the two.</b> Desugaring mints while lifting sugar forms and materialisation
 * mints while closing applications; they run either side of resolution and hold an instance each, so a name
 * minted in one phase colliding with a different form in the other is not caught here. The two share their
 * naming functions, so such a pair would have to have collided within a phase as well to exist at all.
 */
final class MintedNames {

    private final Map<String, String> canonicalByName = new LinkedHashMap<>();

    /**
     * Records that {@code name} was derived from {@code canonical}.
     *
     * @return {@code true} the first time a name is claimed, {@code false} when this is the same derivation
     *         arriving again -- which a caller uses to tell "build the entry" from "it is already there"
     * @throws IllegalStateException if {@code name} was already derived from something else. An invariant of
     *         the naming function has broken, which is neither an author's error nor a gap in this library.
     */
    boolean claim(String name, String canonical) {
        String existing = canonicalByName.putIfAbsent(name, canonical);
        if (existing == null) {
            return true;
        }
        if (!existing.equals(canonical)) {
            throw new IllegalStateException("two different forms derive the internal name '" + name
                    + "', so one would silently take the other's entry ([TSON-SCHEMA] §8.2 requires an "
                    + "internal name to collide with no other): " + existing + " and " + canonical);
        }
        return false;
    }
}
