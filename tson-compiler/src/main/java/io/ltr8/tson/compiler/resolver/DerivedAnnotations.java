package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;

import java.util.List;
import java.util.Optional;

/**
 * The two annotations the resolver attaches itself rather than reading from a schema's source text
 * ([TSON-SCHEMA] §8.1): {@code @alias:name} on a flattened use-site reference (§8.3), and the bare
 * {@code @synthetic} on the key of every entry the resolver materialised from a sugar form (§8.2).
 *
 * <p>Both are declared in the meta-kernel like any other annotation type and both are <b>derived</b>: on
 * ingest they are discarded and recomputed, so neither can be forged into a resolved document to change how
 * it reads. That is why they are built here, by name, instead of resolving through the governing meta the
 * way an author-written annotation does -- there is no author to resolve against, and the value is fixed.
 *
 * <p><b>{@code @synthetic} marks a synthetic entry, never an instantiation entry.</b> §8.2 draws that line
 * itself: the two families are already distinguishable (an instantiation's {@code source} is an application,
 * a synthetic's is a bare constructor), and only synthetics are the fold-back-into-nested-display case the
 * marker serves.
 */
final class DerivedAnnotations {

    /** §8.3's use-site marker, naming the source-level alias the author wrote at the reference site. */
    static final String ALIAS = "alias";

    /** §8.2's entry marker, valueless -- presence at a schema-map key is the whole of the information. */
    static final String SYNTHETIC = "synthetic";

    private static final Annotations SYNTHETIC_ONLY = Annotations.of(List.of(Annotation.of(SYNTHETIC)));

    private DerivedAnnotations() {
    }

    /** The key annotations of a synthetic entry that carries nothing else -- every one of them, today. */
    static Annotations synthetic() {
        return SYNTHETIC_ONLY;
    }

    /** {@code annotations} with {@code @synthetic} added, or unchanged if it already carries one. */
    static Annotations plusSynthetic(Annotations annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return synthetic();
        }
        if (annotations.has(SYNTHETIC)) {
            return annotations;
        }
        Annotations.Builder builder = new Annotations.Builder();
        annotations.values().forEach(builder::add);
        return builder.add(Annotation.of(SYNTHETIC)).build();
    }

    /** {@code annotations} with {@code @alias:written} added -- the carrier is immutable, so this rebuilds it. */
    static Annotations plusAlias(Annotations annotations, String written) {
        Annotations.Builder builder = new Annotations.Builder();
        annotations.values().forEach(builder::add);
        return builder.add(new Annotation(ALIAS, Optional.of(written))).build();
    }
}
