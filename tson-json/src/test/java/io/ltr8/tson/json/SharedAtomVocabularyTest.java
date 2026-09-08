package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassAtom;
import io.ltr8.tson.base.bind.AtomContext;
import org.junit.jupiter.api.Test;

import java.net.Inet4Address;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Both front doors start from one atom vocabulary.
 *
 * <p>[TSON-JSON] §5.1 hands a string's content to the atom's own parser exactly as a TSON quoted token's
 * text would be, so which host types the built-in families read to is a property of the type system and not
 * of the encoding that carried them. {@link AtomContext} therefore lives in {@code tson-atom} beside
 * {@code HostAtoms}, the index from these same classes back to the family that produces each -- and this
 * pins that the JSON front door starts from it rather than from a bare context.
 *
 * <p><b>What this does not claim</b> is that a schemaless read can convert one. A host type is registered so
 * that {@code tson-bind} treats it as a scalar rather than taking it apart structurally; the
 * string-to-host-value conversion is the position's own atom parser's, under a schema. A schemaless bind of
 * a {@code UUID} component fails on both encodings alike -- which is the point, since they fail alike --
 * and {@code BACKLOG.md}'s "Binding" section carries the shared conversion that would close it.
 */
class SharedAtomVocabularyTest {

    /** Types registered as atoms rather than left to structural auto-detection. */
    private static final List<Class<?>> ATOM_HOST_TYPES = List.of(
            UUID.class, byte[].class, LocalDate.class, OffsetDateTime.class, Duration.class,
            URI.class, Inet4Address.class);

    @Test
    void theJsonFrontDoorStartsFromTheSharedVocabulary() throws DataBindException {
        DataBindContext context = Json.standard().dataBindContext();

        for (Class<?> type : ATOM_HOST_TYPES) {
            assertInstanceOf(DataClassAtom.class, context.getDescriptor(type),
                    () -> type.getSimpleName() + " binds as an atom, not structurally");
        }
    }

    @Test
    void soDoesTheStandaloneReader() throws DataBindException {
        DataBindContext context = JsonObjectReader.standard().dataBindContext();

        for (Class<?> type : ATOM_HOST_TYPES) {
            assertInstanceOf(DataClassAtom.class, context.getDescriptor(type),
                    () -> type.getSimpleName() + " binds as an atom, not structurally");
        }
    }

    /** A caller's own context still wins -- the default is a starting point, not an override. */
    @Test
    void aSuppliedContextIsUsedAsGiven() {
        DataBindContext own = AtomContext.defaultContext();

        assertSame(own, Json.using(own).dataBindContext());
        assertSame(own, JsonObjectReader.using(own).dataBindContext());
    }
}
