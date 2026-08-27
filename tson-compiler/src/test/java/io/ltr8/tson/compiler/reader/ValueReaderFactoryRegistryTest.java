package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.TsonTypeReaderResolver;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.UnknownType;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link ValueReaderFactoryRegistry} is a fixed, non-extensible {@code constructor name ->
 * ValueReaderFactory} table -- unlike its predecessor, there's no builder to assemble a scoped
 * subset, so this only exercises {@link ValueReaderFactoryResolver#resolve} itself: an unregistered
 * name still fails clearly, a registered one dispatches to a real factory, and {@link
 * ValueReaderFactoryRegistry#tree}/{@link ValueReaderFactoryRegistry#bind} both read {@code boolean} as a
 * real {@code Boolean} (they share the object-binding enum factory; see {@link ValueReaderFactoryRegistry}'s
 * own Javadoc).
 */
class ValueReaderFactoryRegistryTest {

    private static final TsonTypeReaderResolver NEVER_CALLED = name -> {
        throw new UnsupportedOperationException("resolver not expected to be consulted for '" + name + "'");
    };

    // These atom/enum factories consult only name/definition, never the enclosing schema, so an empty one suffices.
    private static final ValueReaderContext CONTEXT =
            new ValueReaderContext(
                    new TsonLinkedSchema(new TsonSchema("id", "meta", List.of(), Map.of())), NEVER_CALLED);

    @Test
    void resolveThrowsForAnUnregisteredConstructor() {
        ValueReaderFactoryRegistry registry = ValueReaderFactoryRegistry.tree();

        assertThrows(IllegalStateException.class, () -> registry.resolve("no_such_constructor"));
    }

    @Test
    void resolveReturnsTheSameRegisteredFactoryEveryTime() {
        ValueReaderFactoryRegistry registry = ValueReaderFactoryRegistry.tree();

        assertSame(registry.resolve("record"), registry.resolve("record"));
    }

    /**
     * {@code unknown_type} stands in for the group with no compiled parser; {@code CoreSchemaImportTest}
     * pins the current membership. If it ever gains one, pick another from that set rather than deleting
     * this -- the ErrorReader-not-compile-failure behaviour is what is under test, not the constructor.
     */
    @Test
    void aConstructorWithNoCompiledParserYetStillResolvesButFailsOnlyWhenActuallyRead() {
        ValueReaderFactoryRegistry registry = ValueReaderFactoryRegistry.tree();
        TypeDefinition entry = new TypeDefinition(Optional.empty(), TypeKind.SUM, List.of(), true,
                List.of(), List.of(), Optional.empty(), new UnknownType());

        TsonTypeReader<?> reader = registry.resolve("unknown_type").create("unknown", entry, CONTEXT);

        // A real context, because the reader reports through it now rather than throwing past it -- the gap
        // rides in `diagnostic().code()` so it can join a collecting read instead of ending it.
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> reader.read(TestDocuments.document("{}")));
        assertEquals(Diagnostic.Code.NOT_IMPLEMENTED, thrown.diagnostic().code());
        assertEquals(true, thrown.getMessage().contains("unknown"));
    }

    @Test
    void treeAndBindBothReadBooleanEnumMembersAsRealBooleans() {
        TypeDefinition booleanEntry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), true,
                List.of(), List.of(), Optional.empty(), new EnumBody(List.of("true", "false")));

        TsonTypeReader<?> treeReader = ValueReaderFactoryRegistry.tree().resolve("enum")
                .create("boolean", booleanEntry, CONTEXT);
        TsonTypeReader<?> bindReader = ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext())
                .resolve("enum").create("boolean", booleanEntry, CONTEXT);

        // Both use the object-binding enum factory, so boolean reads as a real Boolean (tree wraps it in a TsonAtom).
        assertEquals(Boolean.TRUE, Dom.of((TsonValue) treeReader.read(TestDocuments.document("true"))));
        assertEquals(Boolean.TRUE, bindReader.read(TestDocuments.document("true")));
    }

    @Test
    void treeAndBindReadAnOrdinaryEnumMemberAsItsText() {
        TypeDefinition statusEntry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), true,
                List.of(), List.of(), Optional.empty(), new EnumBody(List.of("ACTIVE", "INACTIVE")));

        TsonTypeReader<?> treeReader = ValueReaderFactoryRegistry.tree().resolve("enum")
                .create("status", statusEntry, CONTEXT);
        TsonTypeReader<?> bindReader = ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext())
                .resolve("enum").create("status", statusEntry, CONTEXT);

        assertEquals("ACTIVE", Dom.of((TsonValue) treeReader.read(TestDocuments.document("ACTIVE"))));
        assertEquals("ACTIVE", bindReader.read(TestDocuments.document("ACTIVE")));
    }
}
