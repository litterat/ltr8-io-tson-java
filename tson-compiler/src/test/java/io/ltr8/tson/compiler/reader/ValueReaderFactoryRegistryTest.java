package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
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

    private static final TsonValueReaderResolver NEVER_CALLED = name -> {
        throw new UnsupportedOperationException("resolver not expected to be consulted for '" + name + "'");
    };

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

    @Test
    void aConstructorWithNoCompiledParserYetStillResolvesButFailsOnlyWhenActuallyRead() {
        ValueReaderFactoryRegistry registry = ValueReaderFactoryRegistry.tree();
        TypeDefinition entry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), true,
                List.of(), List.of(), Optional.empty(), EmailType.UNCONSTRAINED);

        TsonValueReader<?> reader = registry.resolve("email_type").create("email", entry, NEVER_CALLED);

        UnsupportedOperationException thrown =
                assertThrows(UnsupportedOperationException.class, () -> reader.read((TsonReadContext) null));
        assertEquals(true, thrown.getMessage().contains("email"));
    }

    @Test
    void treeAndBindBothReadBooleanEnumMembersAsRealBooleans() {
        TypeDefinition booleanEntry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), true,
                List.of(), List.of(), Optional.empty(), new EnumBody(List.of("true", "false")));

        TsonValueReader<?> treeReader = ValueReaderFactoryRegistry.tree().resolve("enum")
                .create("boolean", booleanEntry, NEVER_CALLED);
        TsonValueReader<?> bindReader = ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext())
                .resolve("enum").create("boolean", booleanEntry, NEVER_CALLED);

        // Both use the object-binding enum factory, so boolean reads as a real Boolean (tree wraps it in an AtomNode).
        assertEquals(Boolean.TRUE, Dom.of((io.ltr8.tson.compiler.tree.TsonNode) treeReader.read("true")));
        assertEquals(Boolean.TRUE, bindReader.read("true"));
    }

    @Test
    void treeAndBindReadAnOrdinaryEnumMemberAsItsText() {
        TypeDefinition statusEntry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), true,
                List.of(), List.of(), Optional.empty(), new EnumBody(List.of("ACTIVE", "INACTIVE")));

        TsonValueReader<?> treeReader = ValueReaderFactoryRegistry.tree().resolve("enum")
                .create("status", statusEntry, NEVER_CALLED);
        TsonValueReader<?> bindReader = ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext())
                .resolve("enum").create("status", statusEntry, NEVER_CALLED);

        assertEquals("ACTIVE", Dom.of((io.ltr8.tson.compiler.tree.TsonNode) treeReader.read("ACTIVE")));
        assertEquals("ACTIVE", bindReader.read("ACTIVE"));
    }
}
