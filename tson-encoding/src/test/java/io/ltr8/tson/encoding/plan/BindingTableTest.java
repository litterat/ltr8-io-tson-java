package io.ltr8.tson.encoding.plan;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.schema.EntryKey;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.VoidType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A checkpoint's bindings ({@link BindingTable#checkpoint}): a resolution's namespace is still being built and its bind
 * context is the consumer's, so what fails a strict load is held as absent, and the read reaching it fails.
 */
class BindingTableTest {

    public record U(java.net.URL url) {
    }

    private static DataBindContext context(Map<String, Class<?>> bound) {
        return DataBindContext.builder().nameBinder(DataNameBinder.ofMap(bound))
                .registerAtoms(AtomContext.hostTypes()).build();
    }

    @Test
    void aClassTheContextCannotDescribeIsAbsentWithItsReason() {
        TsonLinkedSchema linked = Tson.standard().resolve("""
                !!id:"https://example.test/checkpoint.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  u => { url: uri }
                }
                """);
        BindingTable checkpoint = BindingTable.checkpoint(linked, context(Map.of("u", U.class)));

        assertTrue(checkpoint.get("u").isEmpty());
        MissingBindingException missing = assertThrows(MissingBindingException.class,
                () -> checkpoint.require(new EntryKey("example.test/checkpoint.tn", "u")));
        assertEquals("'u' of example.test/checkpoint.tn binds " + U.class.getName() + ", which cannot be analysed: "
                + "Unable to find a valid data conversion for class: class java.net.URL. Bind 'u' to a class this bind "
                + "context can describe, or leave it out of the binding map", missing.getMessage());
    }

    @Test
    void aChainReachingNoEntryIsPassedOverAtACheckpointAndRefusedOtherwise() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("lost", new Alias(TypeRef.of("pending")));
        entries.put("v", new VoidType());
        TsonLinkedSchema building = new TsonLinkedSchema(new TsonSchema("https://example.test/building.tn", List.of(),
                entries));

        assertTrue(BindingTable.checkpoint(building, context(Map.of())).get("lost").isEmpty());
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> BindingTable.strict(building, context(Map.of())));
        assertEquals("'lost' of example.test/building.tn reaches no entry, which linking refuses", refused.getMessage());
    }
}
