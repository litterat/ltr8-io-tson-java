package io.ltr8.tson.schema;

import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which variant of a choice each tag selects ([TSON-SCHEMA] §5.4): a name meaning a variant selects it, before any
 * variant it only IS-A; a name IS-A exactly one variant and meaning none selects that one; a name IS-A more than one
 * and meaning none selects none. One tally for every encoding's choice reader, each handing it what its own namespace
 * index says a variant's names and subtypes are.
 *
 * <p>An extern has no subtypes in the namespace holding it, so an extern variant's names are the ones its position
 * admits ({@link ExternAdmission}), judged of the entries by identity (§5.13): one reaching the target names the
 * variant, one IS-A it is a subtype, and one selecting a variant of a choice the extern lands on is neither, IS-A being
 * no relation to a choice.
 *
 * @param selects     each name a tag may carry, to the variant it selects
 * @param named       the names of {@code selects} meaning their variant, rather than IS-A it
 * @param selectsNone each name IS-A more than one variant and meaning none, to those variants' names
 */
public record ChoiceSelection(Map<String, TypeRef> selects, Set<String> named, Map<String, List<String>> selectsNone) {

    /**
     * The selection over {@code variants}, a choice's named variants in {@code linked}, where {@code naming} gives the
     * names of the namespace meaning a variant past its alias chain and {@code subtypes} the names of the entries IS-A
     * it.
     */
    public static ChoiceSelection of(TsonLinkedSchema linked, List<TypeRef> variants,
                                     Function<TypeRef, Collection<String>> naming,
                                     Function<TypeRef, Collection<String>> subtypes) {
        Map<TypeRef, ExternAdmission> externs = new LinkedHashMap<>();
        for (TypeRef variant : variants) {
            if (linked.landing(variant.name()).isPresent()) {
                externs.put(variant, ExternAdmission.of(linked, linked.terminal(variant.name())));
            }
        }
        Map<String, TypeRef> selects = new LinkedHashMap<>();
        variants.forEach(variant -> selects.put(variant.name(), variant));
        for (TypeRef variant : variants) {
            naming.apply(variant).forEach(written -> selects.putIfAbsent(written, variant));
            if (externs.containsKey(variant)) {
                externs.get(variant).admitted().forEach((written, reach) -> {
                    if (reach.by() == ExternAdmission.By.TARGET) {
                        selects.putIfAbsent(written, variant);
                    }
                });
            }
        }
        Set<String> named = new LinkedHashSet<>(selects.keySet());
        Map<String, List<TypeRef>> below = new LinkedHashMap<>();
        for (TypeRef variant : variants) {
            Set<String> under = new LinkedHashSet<>(subtypes.apply(variant));
            if (externs.containsKey(variant)) {
                externs.get(variant).admitted().forEach((written, reach) -> {
                    if (reach.by() == ExternAdmission.By.SUBTYPE) {
                        under.add(written);
                    }
                });
            }
            for (String written : under) {
                if (!named.contains(written)) {
                    below.computeIfAbsent(written, ignored -> new ArrayList<>()).add(variant);
                }
            }
        }
        Map<String, List<String>> selectsNone = new LinkedHashMap<>();
        below.forEach((written, isA) -> {
            if (isA.size() == 1) {
                selects.put(written, isA.getFirst());
            } else {
                selectsNone.put(written, isA.stream().map(TypeRef::name).toList());
            }
        });
        return new ChoiceSelection(Map.copyOf(selects), Set.copyOf(named), Map.copyOf(selectsNone));
    }
}
