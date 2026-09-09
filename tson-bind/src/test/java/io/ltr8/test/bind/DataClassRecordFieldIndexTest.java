package io.ltr8.test.bind;

import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassRecord;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link DataClassRecord#fieldIndex()} -- each wire field name to its slot, settled once per descriptor.
 *
 * <p>Every reader with no compile step of its own needs this map: {@code SchemalessObjectReader} on the TSON
 * side and {@code DataClassObjectReader} on the JSON side both match a written name to a constructor slot at
 * each record they read, where a schema-driven TSON read settles it when the reader is compiled. Built at the
 * point of use it is one identical map per record value, from data that changes only with the class.
 *
 * <p>A descriptor is resolved once per class per {@link DataBindContext} and this is final, so what those
 * readers share needs no memo and no race to settle. <b>That it is built once is what this pins</b>: the
 * allocation harnesses report the bytes, but a per-record map is a fraction of a record's cost and a
 * threshold tight enough to catch it would be a budget rather than a ratchet.
 */
class DataClassRecordFieldIndexTest {

    public record Line(String sku, int quantity, double price) {
    }

    public record Annotated(String sku, Annotations annotations) {
    }

    private static DataClassRecord descriptorFor(Class<?> type) throws Exception {
        return (DataClassRecord) DataBindContext.builder().build().getDescriptor(type);
    }

    @Test
    void namesEveryFieldAtItsOwnSlot() throws Exception {
        DataClassRecord record = descriptorFor(Line.class);
        Map<String, Integer> index = record.fieldIndex();

        assertEquals(3, index.size());
        for (int i = 0; i < record.fields().length; i++) {
            assertEquals(i, index.get(record.fields()[i].name()), record.fields()[i].name());
        }
    }

    /** The descriptor's own map, not a copy per caller -- which is the whole point of it living there. */
    @Test
    void isBuiltOnceAndHandedOutAsItself() throws Exception {
        DataClassRecord record = descriptorFor(Line.class);

        assertSame(record.fieldIndex(), record.fieldIndex());
    }

    @Test
    void isUnmodifiable() throws Exception {
        Map<String, Integer> index = descriptorFor(Line.class).fieldIndex();

        assertThrows(UnsupportedOperationException.class, () -> index.put("extra", 0));
    }

    /**
     * The annotations carrier is left out, as it is everywhere else: it occupies a constructor slot but is
     * matched against nothing on the wire, so a reader looking a written name up here must not find it.
     */
    @Test
    void leavesOutTheAnnotationsCarrier() throws Exception {
        DataClassRecord record = descriptorFor(Annotated.class);

        assertEquals(Map.of("sku", 0), record.fieldIndex());
        assertFalse(record.fieldIndex().containsKey("annotations"));
    }
}
