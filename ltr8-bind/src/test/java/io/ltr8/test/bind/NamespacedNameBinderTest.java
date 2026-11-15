package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.bind.UnboundNameException;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type name asked within the namespace declaring it ({@link DataNameBinder#resolve(String, String)}): two namespaces
 * declaring one name bind two classes, and a binder that answers by the name alone answers every namespace alike.
 */
public class NamespacedNameBinderTest {

    public record OrderOne(String id) {
    }

    public record OrderTwo(String ref) {
    }

    public record Money(long cents) {
    }

    private static final DataNameBinder ONE = DataNameBinder.ofMap(Map.of("order", OrderOne.class));
    private static final DataNameBinder TWO = DataNameBinder.ofMap(Map.of("order", OrderTwo.class));
    private static final DataNameBinder SHARED = DataNameBinder.ofMap(Map.of("money", Money.class));

    @Test
    void aBinderAnsweringByNameAloneAnswersEveryNamespaceAlike() throws DataBindException {
        DataNameBinder byName = name -> OrderOne.class;
        assertEquals(OrderOne.class, byName.resolve("a", "order"));
        assertEquals(OrderOne.class, byName.resolve("b", "order"));
        assertEquals(OrderOne.class, ONE.resolve("anything", "order"));
    }

    @Test
    void byNamespaceRoutesEachNamespaceToItsOwnBinder() throws DataBindException {
        DataNameBinder binder = DataNameBinder.byNamespace(Map.of("one", ONE, "two", TWO), SHARED);
        assertEquals(OrderOne.class, binder.resolve("one", "order"));
        assertEquals(OrderTwo.class, binder.resolve("two", "order"));
        assertEquals(Money.class, binder.resolve("common", "money"));
    }

    @Test
    void aNameAskedAloneOfByNamespaceIsTheFallbacks() throws DataBindException {
        DataNameBinder binder = DataNameBinder.byNamespace(Map.of("one", ONE), SHARED);
        assertEquals(Money.class, binder.resolve("money"));
        assertThrows(DataBindException.class, () -> binder.resolve("order"));
    }

    @Test
    void aNamespacesBinderIsTheLastWordForItsNamespace() {
        DataNameBinder binder = DataNameBinder.byNamespace(Map.of("one", ONE), SHARED);
        DataBindException missing = assertThrows(DataBindException.class, () -> binder.resolve("one", "money"));
        assertTrue(missing.getMessage().contains("'money' is not bound: the binding map holds [order]"),
                missing.getMessage());
    }

    @Test
    void orElsePassesTheNamespaceToBothBinders() throws DataBindException {
        DataNameBinder binder = DataNameBinder.ofMap(Map.of())
                .orElse(DataNameBinder.byNamespace(Map.of("one", ONE, "two", TWO), SHARED));
        assertEquals(OrderOne.class, binder.resolve("one", "order"));
        assertEquals(OrderTwo.class, binder.resolve("two", "order"));
        assertEquals(Money.class, binder.resolve("money"));
    }

    @Test
    void aContextDescribesTheClassItsBinderBindsWithinTheNamespace() throws DataBindException {
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.byNamespace(Map.of("one", ONE, "two", TWO), SHARED)).build();
        assertEquals(OrderOne.class, context.getDescriptor("one", "order").typeClass());
        assertEquals(OrderTwo.class, context.getDescriptor("two", "order").typeClass());
        assertThrows(UnboundNameException.class, () -> context.getDescriptor("two", "money"));
    }
}
