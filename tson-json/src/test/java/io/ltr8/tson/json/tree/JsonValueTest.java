package io.ltr8.tson.json.tree;

import io.ltr8.tson.json.Json;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The value model itself: how a {@link JsonValue} navigates, converts, and compares.
 *
 * <p>Fixtures come through {@link Json#parse}, which is how a tree is actually built; what a document
 * <em>parses to</em>, and what one renders back as, are the front door's tests.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JsonValueTest {

    @Nested
    class Navigation {

        private final JsonValue root = Json.parse("{\"a\": {\"b\": [10, 20]}, \"n\": null}");

        @Test
        void get_chains_across_objects_and_arrays_with_no_cast_between_steps() {
            assertEquals(20, root.get("a").get("b").get(1).asInt());
        }

        @Test
        void get_throws_and_names_what_is_actually_there() {
            // Deliberately opposite to TsonValue, whose get/at never throw. Two models for two
            // audiences; the reason to differ is the reason to align -- one method name must not carry
            // opposite semantics on the two sides a consumer moves between.
            assertTrue(assertThrows(JsonValueException.class, () -> root.get("missing"))
                    .getMessage().contains("has no member 'missing'"));
            assertTrue(assertThrows(JsonValueException.class, () -> root.get(0))
                    .getMessage().contains("an object has no elements"));
            assertTrue(assertThrows(JsonValueException.class, () -> root.get("a").get("b").get(5))
                    .getMessage().contains("has 2 elements, so index 5"));
            assertTrue(assertThrows(JsonValueException.class, () -> root.get("n").get("x"))
                    .getMessage().contains("null has no members"));
        }

        @Test
        void try_get_is_the_non_throwing_peer_at_every_shape() {
            assertEquals(Optional.empty(), root.tryGet("missing"));
            assertEquals(Optional.empty(), root.tryGet(0));
            assertEquals(Optional.empty(), root.get("n").tryGet("x"));
            assertEquals(Optional.of(new JsonNumber("10")), root.get("a").get("b").tryGet(0));
        }

        @Test
        void try_value_is_empty_for_null_and_present_for_everything_else() {
            assertEquals(Optional.empty(), root.get("n").tryValue());
            assertEquals(Optional.of(root.get("a")), root.get("a").tryValue());
            assertEquals(Optional.of(JsonBoolean.FALSE), Json.parse("false").tryValue());
        }
    }

    @Nested
    class Conversion {

        @Test
        void each_conversion_answers_only_for_its_own_kind() {
            assertEquals("x", new JsonString("x").asString());
            assertTrue(JsonBoolean.TRUE.asBoolean());
            assertEquals(List.of(), JsonArray.empty().asList());
            assertEquals(Map.of(), JsonObject.empty().asMap());
            assertTrue(assertThrows(JsonValueException.class, () -> new JsonString("1").asInt())
                    .getMessage().contains("this value is a string, not a number"));
            assertThrows(JsonValueException.class, JsonNull.INSTANCE::asString);
            assertThrows(JsonValueException.class, () -> new JsonNumber("1").asList());
        }

        @Test
        void a_narrowing_conversion_errors_rather_than_rounding() {
            // §3.1: "an implementation that cannot represent the digits MUST error, never round
            // silently". Every exact narrowing here is checked, not truncated.
            assertEquals(42, new JsonNumber("42").asInt());
            assertEquals(42L, new JsonNumber("42").asLong());
            assertTrue(assertThrows(JsonValueException.class, () -> new JsonNumber("1.5").asInt())
                    .getMessage().contains("not exactly representable as an int"));
            assertThrows(JsonValueException.class, () -> new JsonNumber("2147483648").asInt());
            assertThrows(JsonValueException.class, () -> new JsonNumber("9223372036854775808").asLong());
            assertEquals(9007199254740993L, new JsonNumber("9007199254740993").asLong());
        }

        @Test
        void as_double_is_the_one_conversion_allowed_to_lose() {
            // Rounding onto the binary grid is the approximate families' own contract (§5.4).
            assertEquals(0.1, new JsonNumber("0.1").asDouble());
            assertEquals(1e300, new JsonNumber("1e300").asDouble());
        }

        @Test
        void full_precision_is_one_call_away() {
            assertEquals(new BigDecimal("1234567890123456789012345.6789"),
                    ((JsonNumber) Json.parse("1234567890123456789012345.6789")).toBigDecimal());
            assertEquals(new java.math.BigInteger("10"), new JsonNumber("1e1").toBigInteger());
            assertThrows(JsonValueException.class, () -> new JsonNumber("1.5").toBigInteger());
        }

        @Test
        void a_number_keeps_its_digits_and_its_scale_through_the_tree() {
            // §5.3: `199.90` is not `199.9`, and the promise is about digits, so digits are what is held.
            assertEquals("199.90", Json.parse("199.90").toString());
            assertEquals("6.02e23", Json.parse("6.02e23").toString());
            assertEquals("-0.0", Json.parse("-0.0").toString());
        }

        @Test
        void number_equality_is_over_the_lexeme_because_a_value_space_needs_a_type() {
            // [TSON-SCHEMA] §5.5 makes equality a property of a value space, and a value space comes
            // from a type -- which this layer has none of. Under a schema these are one `number`; here
            // they are three nodes, and toBigDecimal().compareTo is the comparison a caller means.
            assertNotEquals(Json.parse("1"), Json.parse("1.0"));
            assertNotEquals(Json.parse("100"), Json.parse("1e2"));
            assertEquals(0, new JsonNumber("100").toBigDecimal().compareTo(new JsonNumber("1e2").toBigDecimal()));
        }
    }

    @Nested
    class ValueModel {

        @Test
        void equality_is_over_content_so_two_parses_of_one_document_are_equal() {
            assertEquals(Json.parse("{\"a\": [1, \"x\"]}"), Json.parse("{\"a\":[1,\"x\"]}"));
        }

        @Test
        void object_equality_is_over_the_member_set_and_not_over_the_order() {
            // The Javadoc claims it, and it follows from §6.1.6 making order presentation: two documents
            // differing only in member order carry the same value.
            assertEquals(Json.parse("{\"a\":1,\"b\":2}"), Json.parse("{\"b\":2,\"a\":1}"));
            assertEquals(Json.parse("{\"a\":1,\"b\":2}").hashCode(), Json.parse("{\"b\":2,\"a\":1}").hashCode());
            // ... and the order each was written in survives for re-emission.
            assertEquals("{\"b\":2,\"a\":1}", Json.parse("{\"b\":2,\"a\":1}").toString());
        }

        @Test
        void a_constructed_value_equals_the_parsed_one() {
            assertEquals(Json.parse("{\"a\": [1, true]}"),
                    JsonObject.of(Map.of("a", JsonArray.of(List.of(JsonNumber.of(1), JsonBoolean.of(true))))));
        }

        @Test
        void the_singletons_are_singletons() {
            assertSame(JsonBoolean.TRUE, JsonBoolean.of(true));
            assertSame(JsonNull.INSTANCE, JsonNull.of());
            assertEquals(JsonNull.INSTANCE, Json.parse("null"));
        }

        @Test
        void containers_are_immutable_and_copy_defensively() {
            List<JsonValue> mutable = new java.util.ArrayList<>(List.of(JsonNumber.of(1)));
            JsonArray array = JsonArray.of(mutable);
            mutable.add(JsonNumber.of(2));
            assertEquals(1, array.elements().size());
            assertThrows(UnsupportedOperationException.class, () -> array.elements().add(JsonNumber.of(3)));
            assertThrows(UnsupportedOperationException.class,
                    () -> JsonObject.empty().members().put("a", JsonNull.INSTANCE));
        }
    }
}
