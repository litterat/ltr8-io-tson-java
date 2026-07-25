package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserFactoryRegistryTest {

    private static TypeDefinition constructorEntry(TypeKind kind, Top body) {
        return new TypeDefinition(Optional.empty(), kind, List.of(), true, List.of(), List.of(), Optional.empty(), body);
    }

    @Test
    void requireThrowsForAnUnregisteredConstructor() {
        ParserFactoryRegistry registry = ParserFactoryRegistry.builder().build();
        assertThrows(IllegalStateException.class, () -> registry.require("record"));
    }

    @Test
    void requireReturnsTheRegisteredFactory() {
        ParserFactoryRegistry registry = ParserFactoryRegistry.builder()
                .register("record", RecordParser.FACTORY)
                .build();
        assertSame(RecordParser.FACTORY, registry.require("record"));
    }

    @Test
    void typenameOfReadsTheBodysOwnAnnotation() {
        assertEquals("record", ParserFactoryRegistry.typenameOf(RecordBody.of(List.of())));
        assertEquals("enum", ParserFactoryRegistry.typenameOf(new EnumBody(List.of("a", "b"))));
    }

    @Test
    void forMetaSchemaScopesToOnlyTheDeclaredConstructorsAndFindsThemInAvailable() {
        Map<String, TypeDefinition> metaEntries = new LinkedHashMap<>();
        metaEntries.put("record", constructorEntry(TypeKind.PRODUCT, RecordBody.of(List.of())));
        metaEntries.put("enum", constructorEntry(TypeKind.ATOM, new EnumBody(List.of())));
        // Not a constructor -- must be excluded from the scoped registry even though it's present.
        metaEntries.put("integer_size", TypeDefinition.product(RecordBody.of(List.of())));
        TsonSchema metaSchema = new TsonSchema(Optional.of("https://example.test/meta.tn1"),
                "https://example.test/meta-kernel.tn1", List.of(), metaEntries);

        ParserFactoryRegistry available = ParserFactoryRegistry.builder()
                .register("record", RecordParser.FACTORY)
                .register("enum", AtomTypeParser.ENUM)
                .register("integer_type", AtomTypeParser.INTEGER_TYPE)
                .build();

        ParserFactoryRegistry scoped = ParserFactoryRegistry.forMetaSchema(metaSchema, available);

        assertSame(RecordParser.FACTORY, scoped.require("record"));
        assertSame(AtomTypeParser.ENUM, scoped.require("enum"));
        // "integer_type" is available but never declared as a constructor by this meta-schema.
        assertThrows(IllegalStateException.class, () -> scoped.require("integer_type"));
    }

    @Test
    void forMetaSchemaErrorsImmediatelyNamingTheEntryWithNoMatchingFactory() {
        Map<String, TypeDefinition> metaEntries = new LinkedHashMap<>();
        metaEntries.put("text_type", constructorEntry(TypeKind.ATOM, RecordBody.of(List.of())));
        TsonSchema metaSchema = new TsonSchema(Optional.of("https://example.test/meta.tn1"),
                "https://example.test/meta-kernel.tn1", List.of(), metaEntries);

        ParserFactoryRegistry available = ParserFactoryRegistry.builder().build();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ParserFactoryRegistry.forMetaSchema(metaSchema, available));
        assertTrue(thrown.getMessage().contains("text_type"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("meta.tn1"), thrown.getMessage());
    }
}
