package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The meta-kernel's {@code choice} constructor's own vocabulary, resolved (Part 2 §4.1, §5.4,
 * §8.1): {@code variants: [type_ref]} -- a SUM-kind body backing every declared choice type
 * (`contact_method => (email | phone | address)` and similar).
 */
@Typename(name = "choice")
public record ChoiceBody(List<TypeRef> variants) implements TypeBody {

    public ChoiceBody {
        variants = List.copyOf(variants);
    }
}
