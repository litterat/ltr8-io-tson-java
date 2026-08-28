package io.ltr8.tson.schema.meta;

import java.util.List;

/**
 * The meta-kernel's {@code product => top & { access_pattern: ... size_type: ... } }} base kind
 * (Part 2 §4.1) -- every PRODUCT-kind {@link Top} variant IS-A this: {@link RecordBody},
 * {@link ArrayBody} (and {@code set}, which refines it), {@link MapBody}, and {@link TupleBody} --
 * exactly {@code record}/{@code array}/
 * {@code set}/{@code map}/{@code tuple}, the kernel's own structural-type family (Part 2 §4.1: "record,
 * array, set, map, and tuple compose with product, fixing access_pattern and size_type").
 */
public sealed interface Product extends Top permits RecordBody, ArrayBody, MapBody, TupleBody {

    /**
     * Reports how this body's own structural facets contradict <em>each other</em> -- an empty list means it
     * is internally coherent. {@link Atom#coherenceCheck}'s structural twin, asking the identical question of
     * the other base kind, and answered the same way: the family is the only thing that knows which of its
     * own fields form a range, so the rule lives here rather than in a generic comparison.
     *
     * <p>The default admits everything, which is correct for a family with no orderable facet at all
     * ({@link RecordBody}, {@link TupleBody}); {@link ArrayBody} and {@link MapBody} carry the {@code
     * min_items}/{@code max_items} pair and override it. That pair is one rule over two families, which is
     * why they share {@link AtomCoherence}'s own comparison rather than each spelling it.
     *
     * <p><b>Stated here rather than where a container is written.</b> [TSON-SCHEMA] §5.3's size specifier and
     * the explicit {@code !array { ... min_items: ... }} body denote the same type, and a rule that lived
     * with either spelling would refuse one and admit the other. §8.2's materialisation-time check has the
     * same need from a third direction -- bounds that were parameters when the template was written -- and
     * reaches the same rule here.
     */
    default List<String> coherenceCheck() {
        return List.of();
    }
}
