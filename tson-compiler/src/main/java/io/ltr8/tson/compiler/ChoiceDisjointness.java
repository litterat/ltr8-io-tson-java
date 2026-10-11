package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.reader.DiscriminationClass;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Derives a choice's {@code type_definition.disjoint} (Part 2 §5.4): {@code true} exactly when every
 * variant has a {@link DiscriminationClass} and no class appears twice, {@code false} otherwise. A total,
 * two-valued decision -- there is no "unable to decide" state, because the question is not value-set
 * disjointness (a partial-prover problem over bound intervals, pattern emptiness and record inhabitation)
 * but whether an encoding's single form-resolution pass can tell the variants apart, which the
 * declarations answer by inspection. Same-class variants -- two numeric families, two string-form atoms,
 * two records -- are not disjoint however separated their value sets, because separating them would take
 * exactly the type-directed second inspection of the value's form that [TSON-DATA] §2.4's once-only rule
 * forbids a reader. §5.4 requires exactly this and no more: "a resolver MUST record exactly this -- it MUST
 * NOT prove more (value-set separation such as disjoint numeric bounds or disjoint patterns does not make a
 * choice disjoint) or less".
 *
 * <p><b>The fact is load-bearing twice, so the class table is pinned.</b> {@code
 * TsonSchemaLinker.checkDisjointAssertions} rejects {@code @disjoint} on a {@code false} choice, and
 * {@code ChoiceReader} offers untagged recovery exactly where the fact is {@code true} and every class is
 * scalar -- dispatching through the same {@link DiscriminationClass#of} this derivation classifies with,
 * so the two can never disagree. Changing what classifies therefore changes both which schemas load and
 * which documents read untagged: a compatibility decision, not a free improvement.
 */
final class ChoiceDisjointness {

    private ChoiceDisjointness() {
    }

    static boolean derive(ChoiceBody choice, Map<String, TypeDefinition> namespace) {
        Set<DiscriminationClass> seen = EnumSet.noneOf(DiscriminationClass.class);
        for (TypeRef variant : choice.variants()) {
            Optional<DiscriminationClass> variantClass = DiscriminationClass.of(variant.name(), namespace);
            if (variantClass.isEmpty() || !seen.add(variantClass.get())) {
                return false;
            }
        }
        return true;
    }
}
