package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.bind.UnboundNameException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.SequencedCollection;
import java.util.SequencedSet;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every collection and map is built through one {@code (int):collection} handle, the capacity a hint: a class
 * with a capacity constructor takes it, and one without -- {@code TreeSet}, {@code LinkedList}, {@code TreeMap} --
 * is built through its no-argument constructor with the capacity ignored. A component declaring an interface is
 * built as a default class for it.
 */
class CollectionConstructionTest {

    public record Concrete(TreeSet<String> sorted, LinkedList<String> linked, CopyOnWriteArrayList<String> copied,
                           TreeMap<String, Integer> tree, ConcurrentSkipListMap<String, Integer> skip) {
    }

    public record Interfaces(SortedSet<String> sorted, NavigableSet<String> navigable, SequencedSet<String> sequenced,
                             SequencedCollection<String> sequence, Collection<String> collection,
                             SortedMap<String, Integer> sortedMap, NavigableMap<String, Integer> navigableMap,
                             ConcurrentMap<String, Integer> concurrent) {
    }

    /** A collection with neither a capacity constructor nor a no-argument one. */
    public static class Unbuildable<E> extends ArrayList<E> {
        private static final long serialVersionUID = 1L;

        public Unbuildable(E seed) {
            add(seed);
        }
    }

    public record HoldsUnbuildable(Unbuildable<String> values) {
    }

    private final DataBindContext context = DataBindContext.builder().build();

    private Object built(Class<?> holder, String component) throws Throwable {
        DataClassRecord record = (DataClassRecord) context.getDescriptor(holder);
        for (var field : record.fields()) {
            if (field.name().equals(component)) {
                return switch (field.dataClass()) {
                    case DataClassArray array -> array.constructor().invoke(4);
                    case DataClassMap map -> map.constructor().invoke(4);
                    default -> throw new AssertionError(component + " is not a collection");
                };
            }
        }
        throw new AssertionError("no component " + component);
    }

    @Test
    void aClassWithNoCapacityConstructorIsBuiltWithoutOne() throws Throwable {
        assertInstanceOf(TreeSet.class, built(Concrete.class, "sorted"));
        assertInstanceOf(LinkedList.class, built(Concrete.class, "linked"));
        assertInstanceOf(CopyOnWriteArrayList.class, built(Concrete.class, "copied"));
        assertInstanceOf(TreeMap.class, built(Concrete.class, "tree"));
        assertInstanceOf(ConcurrentSkipListMap.class, built(Concrete.class, "skip"));
    }

    @Test
    void anInterfaceIsBuiltAsADefaultClassForIt() throws Throwable {
        assertInstanceOf(TreeSet.class, built(Interfaces.class, "sorted"));
        assertInstanceOf(TreeSet.class, built(Interfaces.class, "navigable"));
        assertInstanceOf(LinkedHashSet.class, built(Interfaces.class, "sequenced"));
        assertInstanceOf(ArrayList.class, built(Interfaces.class, "sequence"));
        assertInstanceOf(ArrayList.class, built(Interfaces.class, "collection"));
        assertInstanceOf(TreeMap.class, built(Interfaces.class, "sortedMap"));
        assertInstanceOf(TreeMap.class, built(Interfaces.class, "navigableMap"));
        assertInstanceOf(ConcurrentHashMap.class, built(Interfaces.class, "concurrent"));
    }

    /** A sorted map keeps its keys' order, not the order they were put in. */
    @Test
    void aSortedMapInterfaceIsNotOrdered() throws Throwable {
        DataClassRecord record = (DataClassRecord) context.getDescriptor(Interfaces.class);
        for (var field : record.fields()) {
            if (field.name().equals("sortedMap")) {
                assertFalse(((DataClassMap) field.dataClass()).ordered());
            }
        }
    }

    @Test
    void aClassWithNeitherConstructorSaysSo() {
        DataBindException e = assertThrows(DataBindException.class, () -> context.getDescriptor(HoldsUnbuildable.class));
        StringBuilder chain = new StringBuilder();
        for (Throwable at = e; at != null; at = at.getCause()) {
            chain.append(at.getMessage()).append(" | ");
        }
        assertTrue(chain.toString().contains(Unbuildable.class.getName() + " has neither a public (int) nor a public "
                + "no-argument constructor"), chain.toString());
    }

    /** A name nothing binds and a name bound to a class that cannot be analysed are different mistakes. */
    @Test
    void anUnboundNameIsToldApartFromAnUnanalysableClass() {
        DataBindContext named = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("holder", HoldsUnbuildable.class))).build();
        assertThrows(UnboundNameException.class, () -> named.getDescriptor("elsewhere"));
        DataBindException unanalysable = assertThrows(DataBindException.class, () -> named.getDescriptor("holder"));
        assertFalse(unanalysable instanceof UnboundNameException);
        assertTrue(unanalysable.getMessage().startsWith("'holder' binds " + HoldsUnbuildable.class.getName()
                + ", which cannot be analysed: "), unanalysable.getMessage());
        assertTrue(unanalysable.getMessage().contains("has neither a public (int) nor a public no-argument"),
                unanalysable.getMessage());
    }

    @Test
    void aCapacityIsOnlyAHint() throws Throwable {
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) built(Concrete.class, "linked");
        list.add("a");
        list.add("b");
        list.add("c");
        list.add("d");
        list.add("e");
        assertEquals(5, list.size());
    }
}
