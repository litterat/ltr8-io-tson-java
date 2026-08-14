package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.tree.*;
import io.ltr8.tson.tree.TsonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-only projection of a {@link TsonValue} back to the plain {@code Map}/{@code List}/host-value shape the
 * old DOM readers produced -- a record or map to a {@code LinkedHashMap}, an array or tuple to a {@code
 * List}, an atom to its host value, and null/absent/missing to {@code null}. Lets the reader tests that
 * predate the tree keep asserting on values and collapsed shape with a one-line change, while {@link
 * TsonNodeTest}/{@code TreeReadTest} cover the tree's own structure-preserving, typed form directly.
 */
public final class Dom {

    private Dom() {
    }

    public static Object of(TsonValue node) {
        return switch (node) {
            case TsonRecord record -> {
                Map<String, Object> map = new LinkedHashMap<>();
                record.fields().forEach((name, value) -> map.put(name, of(value)));
                yield map;
            }
            case TsonMap mapNode -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                for (TsonMap.Entry entry : mapNode.entries()) {
                    map.put(of(entry.key()), of(entry.value()));
                }
                yield map;
            }
            case TsonArray array -> ofElements(array.elements());
            case TsonTuple tuple -> ofElements(tuple.elements());
            case TsonAtom atom -> atom.value();
            default -> null; // TsonNull / TsonAbsent / TsonMissing
        };
    }

    private static List<Object> ofElements(List<TsonValue> elements) {
        List<Object> list = new ArrayList<>(elements.size());
        for (TsonValue element : elements) {
            list.add(of(element));
        }
        return list;
    }
}
