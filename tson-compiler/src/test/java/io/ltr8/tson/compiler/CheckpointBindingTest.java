package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resolution's checkpoint binds under the consumer's context with bindings it does not hand out: a class that
 * cannot be described fails the read reaching its entry, never the resolution.
 */
class CheckpointBindingTest {

    /** {@code java.net.URL} has no conversion in {@code ltr8-bind}, so this class cannot be described. */
    public record Unreadable(java.net.URL url) {
    }

    @Test
    void aCheckpointHandsOutNoBindingAndDefersAClassThatCannotBeDescribed() {
        DataBindContext context = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(
                Map.of("held", Unreadable.class)).orElse(SchemaModelNameBinder.INSTANCE)).build();
        TsonCompiledVocabularyRegistry core = TsonCompiledVocabularyRegistry.withStandardLibrary(context,
                SchemaSource.ofMap(Map.of()));
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("held", RecordBody.of(RecordBody.fieldMap()));
        TsonSchema ready = new TsonSchema("https://example.test/checkpoint.tn", List.of(), entries);

        TsonCompiledSchema checkpoint = core.compileReady(ready, Set.of(), false);

        assertTrue(checkpoint.binding().isEmpty());
        MissingBindingException missing = assertThrows(MissingBindingException.class,
                () -> checkpoint.get("held").read(TestDocuments.document("{}")));
        assertEquals("'held' of example.test/checkpoint.tn binds " + Unreadable.class.getName() + ", which cannot be "
                + "analysed: Unable to find a valid data conversion for class: class java.net.URL. Bind 'held' to a "
                + "class this bind context can describe, or leave it out of the binding map", missing.getMessage());
    }
}
