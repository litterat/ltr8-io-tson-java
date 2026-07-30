package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
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
 * name still fails clearly, a registered one dispatches to a real factory, and {@link #dom()}/
 * {@link #bind} genuinely disagree for {@code boolean} -- the one constructor the two modes
 * register differently (see {@link ValueReaderFactoryRegistry}'s own Javadoc).
 */
class ValueReaderFactoryRegistryTest {

    private static final TsonValueReaderResolver NEVER_CALLED = name -> {
        throw new UnsupportedOperationException("resolver not expected to be consulted for '" + name + "'");
    };

    @Test
    void resolveThrowsForAnUnregisteredConstructor() {
        ValueReaderFactoryRegistry registry = ValueReaderFactoryRegistry.dom();

        assertThrows(IllegalStateException.class, () -> registry.resolve("no_such_constructor"));
    }

    @Test
    void resolveReturnsTheSameRegisteredFactoryEveryTime() {
        ValueReaderFactoryRegistry registry = ValueReaderFactoryRegistry.dom();

        assertSame(registry.resolve("record"), registry.resolve("record"));
    }

    @Test
    void aConstructorWithNoCompiledParserYetStillResolvesButFailsOnlyWhenActuallyRead() {
        ValueReaderFactoryRegistry registry = ValueReaderFactoryRegistry.dom();
        TypeDefinition entry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), true,
                List.of(), List.of(), Optional.empty(), EmailType.UNCONSTRAINED);

        TsonValueReader<?> reader = registry.resolve("email_type").create("email", entry, NEVER_CALLED);

        UnsupportedOperationException thrown =
                assertThrows(UnsupportedOperationException.class, () -> reader.read((TsonReadContext) null));
        assertEquals(true, thrown.getMessage().contains("email"));
    }

    @Test
    void domAndBindDisagreeOnlyForBooleanEnumMembers() {
        TypeDefinition booleanEntry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), true,
                List.of(), List.of(), Optional.empty(), new EnumBody(List.of("true", "false")));

        TsonValueReader<?> domReader = ValueReaderFactoryRegistry.dom().resolve("enum")
                .create("boolean", booleanEntry, NEVER_CALLED);
        TsonValueReader<?> bindReader = ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext())
                .resolve("enum").create("boolean", booleanEntry, NEVER_CALLED);

        assertEquals("true", domReader.read("true"));
        assertEquals(Boolean.TRUE, bindReader.read("true"));
    }

    @Test
    void domAndBindAgreeForAnOrdinaryNonBooleanEnum() {
        TypeDefinition statusEntry = new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), true,
                List.of(), List.of(), Optional.empty(), new EnumBody(List.of("ACTIVE", "INACTIVE")));

        TsonValueReader<?> domReader = ValueReaderFactoryRegistry.dom().resolve("enum")
                .create("status", statusEntry, NEVER_CALLED);
        TsonValueReader<?> bindReader = ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext())
                .resolve("enum").create("status", statusEntry, NEVER_CALLED);

        assertEquals("ACTIVE", domReader.read("ACTIVE"));
        assertEquals("ACTIVE", bindReader.read("ACTIVE"));
    }
}
