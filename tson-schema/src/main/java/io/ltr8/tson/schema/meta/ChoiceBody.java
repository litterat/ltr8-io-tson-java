package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.List;
import java.util.Optional;

/**
 * The meta-kernel's {@code choice} constructor's own vocabulary, resolved (Part 2 §4.1, §5.4,
 * §8.1): {@code variants: [type_ref]} -- a SUM-kind body backing every declared choice type
 * (`contact_method => (email | phone | address)` and similar).
 */
@Typename(name = "choice")
public record ChoiceBody(List<TypeRef> supertypes, List<String> subtypes,
        List<TypeRef> variants, Optional<Boolean> disjoint) implements Sum {

    @Record
    public ChoiceBody {
        supertypes = Chains.copy(supertypes);
        subtypes = Chains.copy(subtypes);
        variants = List.copyOf(variants);
        disjoint = disjoint == null ? Optional.empty() : disjoint;
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public ChoiceBody(List<TypeRef> variants, Optional<Boolean> disjoint) {
        this(List.of(), List.of(), variants, disjoint);
    }

    @Override
    public ChoiceBody withChain(List<TypeRef> supertypes, List<String> subtypes) {
        return new ChoiceBody(supertypes, subtypes, variants, disjoint);
    }

    /** A choice as an author declares it -- the resolver derives {@link #disjoint} at link time. */
    public ChoiceBody(List<TypeRef> variants) {
        this(variants, Optional.empty());
    }
}
