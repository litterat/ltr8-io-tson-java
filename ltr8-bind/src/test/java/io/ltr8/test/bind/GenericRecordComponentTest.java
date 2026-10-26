package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataParameterizedType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A generic record described at a parameterisation binds its components at that parameterisation: the type
 * parameter a component's type names is replaced by the argument, at any depth, so the component has a descriptor.
 * A type variable left in place has none, which is what a raw description still meets.
 */
public class GenericRecordComponentTest {

    public record Named<V>(String type, Map<String, V> entries) {
    }

    public record Holder(Named<Long> counts, Named<List<String>> tags) {
    }

    @Test
    public void aComponentOfAParameterisedRecordIsBoundAtTheArgument() throws DataBindException {
        DataBindContext context = DataBindContext.builder().build();
        DataClassRecord holder = assertInstanceOf(DataClassRecord.class, context.getDescriptor(Holder.class));

        DataClassRecord counts = assertInstanceOf(DataClassRecord.class,
                holder.fields()[holder.fieldIndex().get("counts")].dataClass());
        DataClassMap countEntries = assertInstanceOf(DataClassMap.class,
                counts.fields()[counts.fieldIndex().get("entries")].dataClass());
        assertEquals(Long.class, countEntries.valueDataClass().typeClass());

        DataClassRecord tags = assertInstanceOf(DataClassRecord.class,
                holder.fields()[holder.fieldIndex().get("tags")].dataClass());
        DataClassMap tagEntries = assertInstanceOf(DataClassMap.class,
                tags.fields()[tags.fieldIndex().get("entries")].dataClass());
        assertInstanceOf(DataClassArray.class, tagEntries.valueDataClass());
    }

    @Test
    public void aDescriptorIsPerParameterisation() throws DataBindException {
        DataBindContext context = DataBindContext.builder().build();
        DataClassRecord ofText = (DataClassRecord) context.getDescriptor(Named.class,
                new DataParameterizedType(Named.class, String.class));
        DataClassMap entries = (DataClassMap) ofText.fields()[ofText.fieldIndex().get("entries")].dataClass();
        assertEquals(String.class, entries.valueDataClass().typeClass());
    }

    @Test
    public void aRawDescriptionStillHasNoArgumentToBindAt() {
        assertThrows(DataBindException.class, () -> DataBindContext.builder().build().getDescriptor(Named.class));
    }
}
