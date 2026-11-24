package io.ltr8.tson.schema.meta;

import io.ltr8.tson.schema.atom.AtomNarrowing;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * [TSON-SCHEMA] §5.7's facet kinds for a refinement of an array, a map or a namespace type,
 * {@code !tags ^ { min_items: 2 }}, judged over effective values: both bodies are bound, so a facet neither wrote
 * compares at the constructor's default. The bounds move inward; a type slot and {@code ordered} are restated only,
 * the one saying what an element is and the other what two values being equal means; {@code unique_items} only rises
 * and {@code voidable} only falls, each a permission withdrawn. A type slot compares as held: two names reaching one
 * entry are one type ([TSON-SCHEMA] §5.7), and a refined body reaching here holds the source's spelling for them.
 */
public final class CollectionFacets {

    private CollectionFacets() {
    }

    /** Each way {@code refined} moves a facet of {@code source} outward, empty where it narrows. */
    public static List<String> narrowing(ArrayBody source, ArrayBody refined) {
        List<String> out = new ArrayList<>();
        restated(out, "element_type", source.elementType(), refined.elementType());
        AtomNarrowing.checkOnlyWithdraws(out, "voidable", source.voidable(), refined.voidable());
        restated(out, "ordered", source.ordered(), refined.ordered());
        AtomNarrowing.checkOnlyWithdraws(out, "duplicate items (unique_items: false)", !source.uniqueItems(),
                !refined.uniqueItems());
        AtomNarrowing.checkAtLeast(out, "min_items", source.minItems(), refined.minItems());
        AtomNarrowing.checkAtMost(out, "max_items", source.maxItems(), refined.maxItems());
        return out;
    }

    /** Each way {@code refined} moves a facet of {@code source} outward, empty where it narrows. */
    public static List<String> narrowing(MapBody source, MapBody refined) {
        List<String> out = new ArrayList<>();
        restated(out, "key_type", source.keyType(), refined.keyType());
        restated(out, "value_type", source.valueType(), refined.valueType());
        AtomNarrowing.checkOnlyWithdraws(out, "voidable", source.voidable(), refined.voidable());
        restated(out, "ordered", source.ordered(), refined.ordered());
        AtomNarrowing.checkAtLeast(out, "min_items", source.minItems(), refined.minItems());
        AtomNarrowing.checkAtMost(out, "max_items", source.maxItems(), refined.maxItems());
        return out;
    }

    /**
     * Each way {@code refined} moves a facet of {@code source} outward, judged as the maps their values are read by:
     * {@code meta.namespace} composes {@code meta.map} and adds no field beside the ones it takes ([TSON-SCHEMA] §5.7).
     */
    public static List<String> narrowing(NamespaceBody source, NamespaceBody refined) {
        return narrowing(source.read(), refined.read());
    }

    private static void restated(List<String> out, String facet, Object source, Object refined) {
        if (!Objects.equals(source, refined)) {
            out.add(replaced(facet, source, refined));
        }
    }

    /** The violation of a refinement writing {@code refined} at {@code facet}, fixed at {@code source}. */
    public static String replaced(String facet, Object source, Object refined) {
        return facet + " " + shown(refined) + " replaces the source's own " + shown(source) + ", and a refinement "
                + "only restates " + facet;
    }

    private static String shown(Object facet) {
        return facet instanceof TypeExpr type ? type.text() : String.valueOf(facet);
    }
}
