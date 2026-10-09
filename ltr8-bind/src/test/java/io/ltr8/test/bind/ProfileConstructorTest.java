package io.ltr8.test.bind;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Profile;
import io.ltr8.annotation.Record;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One class, several shapes, one context each ({@code DataBindContext.Builder#profile} and {@code @Profile}).
 *
 * <p>The case is a server speaking several versions of one schema at once. Descriptors are cached per
 * context, so a class still maps to exactly one {@link DataClassRecord} in any given context -- the profile
 * chooses which, once, when the descriptor is built.
 *
 * <p>The profile name is opaque to this module: matched by equality, and nothing here knows or asks what it
 * stands for. That is what keeps selection here rather than in whatever knows about schemas.
 */
public class ProfileConstructorTest {

    /**
     * Three fields, and an older two-field shape. {@code fields} is needed because a secondary constructor
     * keeps no parameter names unless the class was compiled with {@code -parameters}.
     */
    public record Order(String sku, int quantity, String currency) {

        @Profile(value = "api-2", fields = {"sku", "quantity"})
        public Order(String sku, int quantity) {
            this(sku, quantity, "AUD");
        }
    }

    /** The same, naming its parameters one at a time instead. Either route gives the binder the names. */
    public record Ticket(String id, int seat, String tier) {

        @Profile("api-2")
        public Ticket(@Field("id") String id, @Field("seat") int seat) {
            this(id, seat, "economy");
        }
    }

    /** One constructor serving two profiles, and a designated fallback for every other. */
    public record Invoice(String number, int cents, String currency) {

        @Record
        public Invoice {
        }

        @Profile(value = {"api-1", "api-2"}, fields = {"number", "cents"})
        public Invoice(String number, int cents) {
            this(number, cents, "AUD");
        }
    }

    private static DataBindContext context(String profile) {
        DataBindContext.Builder builder = DataBindContext.builder();
        return (profile == null ? builder : builder.profile(profile)).build();
    }

    /** Every message in the chain -- {@code DataBindException} wraps the analysis failure that has the detail. */
    private static String allMessages(Throwable thrown) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append(" | ");
        }
        return all.toString();
    }

    private static List<String> fieldNames(DataBindContext context, Class<?> type) throws DataBindException {
        DataClassRecord record = (DataClassRecord) context.getDescriptor(type);
        return Arrays.stream(record.fields()).map(DataClassField::name).toList();
    }

    /** The profiled constructor decides the shape: the field it does not take is not part of this binding. */
    @Test
    public void aProfiledConstructorSelectsItsOwnFieldSet() throws DataBindException {
        assertEquals(List.of("sku", "quantity"), fieldNames(context("api-2"), Order.class));
    }

    /** And a context naming no profile still gets the canonical shape, unchanged. */
    @Test
    public void anUnnamedContextBindsTheCanonicalConstructor() throws DataBindException {
        assertEquals(List.of("sku", "quantity", "currency"), fieldNames(context(null), Order.class));
    }

    /** A profile the class does not offer falls back to the canonical constructor rather than failing. */
    @Test
    public void aProfileTheClassDoesNotKnowFallsBackToTheDesignatedConstructor() throws DataBindException {
        assertEquals(List.of("sku", "quantity", "currency"), fieldNames(context("api-9"), Order.class));
        assertEquals(List.of("number", "cents", "currency"), fieldNames(context("api-9"), Invoice.class));
    }

    /** One constructor may serve several profiles -- two versions that happen to share a shape. */
    @Test
    public void oneConstructorMayServeSeveralProfiles() throws DataBindException {
        assertEquals(List.of("number", "cents"), fieldNames(context("api-1"), Invoice.class));
        assertEquals(List.of("number", "cents"), fieldNames(context("api-2"), Invoice.class));
    }

    /** {@code @Field} on the parameters names them just as well as the list does. */
    @Test
    public void parameterAnnotationsNameTheFieldsToo() throws DataBindException {
        assertEquals(List.of("id", "seat"), fieldNames(context("api-2"), Ticket.class));
    }

    /**
     * The two contexts are independent, which is the whole deployment story: one JVM, one class, two shapes,
     * neither disturbing the other's cache.
     */
    @Test
    public void twoContextsBindOneClassTwoWays() throws DataBindException {
        DataBindContext older = context("api-2");
        DataBindContext current = context(null);

        assertEquals(List.of("sku", "quantity"), fieldNames(older, Order.class));
        assertEquals(List.of("sku", "quantity", "currency"), fieldNames(current, Order.class));
        assertEquals(List.of("sku", "quantity"), fieldNames(older, Order.class), "still its own after the other");
    }

    /**
     * A class whose every constructor is profiled, asked for a profile none of them serves. There is nothing
     * to fall back to that is not built for some other version, so it fails rather than binding whichever
     * shape happens to be the only candidate -- and says which profiles the class does offer, the likeliest
     * cause being a name that disagrees in one of the two places it has to match.
     */
    public record Strict(String a, String b) {

        @Profile(value = "api-1", fields = {"a", "b"})
        public Strict {
        }
    }

    @Test
    public void anUnmatchedProfileWithNoFallbackNamesWhatTheClassOffers() {
        DataBindException thrown = assertThrows(DataBindException.class,
                () -> fieldNames(context("api-7"), Strict.class));

        assertTrue(allMessages(thrown).contains("api-7"), allMessages(thrown));
        assertTrue(allMessages(thrown).contains("api-1"), "names the profiles it does offer: " + allMessages(thrown));
    }

    /** A listed name that is not a component has no accessor, so it is refused rather than invented. */
    public record Wrong(String a, String b) {

        @Profile(value = "api-1", fields = {"nope"})
        public Wrong(String a) {
            this(a, "");
        }
    }

    @Test
    public void aFieldNameThatMatchesNoComponentIsRefused() {
        DataBindException thrown = assertThrows(DataBindException.class,
                () -> fieldNames(context("api-1"), Wrong.class));

        assertTrue(allMessages(thrown).contains("nope"), allMessages(thrown));
    }

    /** A list that does not match the parameter count is refused before anything is bound to the wrong slot. */
    public record Short2(String a, String b) {

        @Profile(value = "api-1", fields = {"a", "b"})
        public Short2(String a) {
            this(a, "");
        }
    }

    @Test
    public void aFieldListThatDoesNotCoverTheParametersIsRefused() {
        DataBindException thrown = assertThrows(DataBindException.class,
                () -> fieldNames(context("api-1"), Short2.class));

        assertTrue(allMessages(thrown).contains("2 field name"), allMessages(thrown));
    }
}
