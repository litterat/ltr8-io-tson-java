package io.ltr8.tson.mapper;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsonMapperTest {

    private final TsonMapper mapper = new TsonMapper();

    // ── Records ──────────────────────────────────────────────────────────

    public record Point(int x, int y) {
    }

    @Test
    void simpleRecord() throws DataBindException {
        Point p = mapper.toObject("{ x: 1 y: 2 }", Point.class);
        assertEquals(new Point(1, 2), p);
    }

    @Test
    void recordFieldOrderInSourceDoesNotMatterMatchedByName() throws DataBindException {
        Point p = mapper.toObject("{ y: 2 x: 1 }", Point.class);
        assertEquals(new Point(1, 2), p);
    }

    public record Customer(String name, String email) {
    }

    public record Order(int orderId, Customer customer) {
    }

    @Test
    void nestedRecord() throws DataBindException {
        Order o = mapper.toObject("""
                { orderId: 1042 customer: { name: "Ada Lovelace" email: "ada@example.com" } }
                """, Order.class);
        assertEquals(1042, o.orderId());
        assertEquals("Ada Lovelace", o.customer().name());
        assertEquals("ada@example.com", o.customer().email());
    }

    public record WithOptional(int required, Optional<String> nickname) {
    }

    @Test
    void missingOptionalFieldBindsToEmpty() throws DataBindException {
        WithOptional w = mapper.toObject("{ required: 1 }", WithOptional.class);
        assertEquals(1, w.required());
        assertTrue(w.nickname().isEmpty());
    }

    @Test
    void absentSentinelBindsSameAsMissingForOptionalField() throws DataBindException {
        WithOptional w = mapper.toObject("{ required: 1 nickname: _ }", WithOptional.class);
        assertTrue(w.nickname().isEmpty());
    }

    @Test
    void presentOptionalFieldBindsToValue() throws DataBindException {
        WithOptional w = mapper.toObject("{ required: 1 nickname: \"Ada\" }", WithOptional.class);
        assertEquals("Ada", w.nickname().orElseThrow());
    }

    @Test
    void missingRequiredFieldThrows() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ y: 2 }", Point.class));
    }

    @Test
    void duplicateFieldNameLastValueWins() throws DataBindException {
        // §2.5: "If duplicate field names are present, the last value wins."
        Point p = mapper.toObject("{ x: 1 x: 99 y: 2 }", Point.class);
        assertEquals(99, p.x());
    }

    public record Renamed(@Field("xx") int x, @Field("yy") int y) {
    }

    @Test
    void fieldAnnotationRenamesBoundField() throws DataBindException {
        Renamed r = mapper.toObject("{ xx: 1 yy: 2 }", Renamed.class);
        assertEquals(1, r.x());
        assertEquals(2, r.y());
    }

    public record EmptyBraceRecord(Optional<String> a, Optional<String> b) {
    }

    @Test
    void emptyBraceBindsAsRecordWithNoRequiredFields() throws DataBindException {
        EmptyBraceRecord r = mapper.toObject("{}", EmptyBraceRecord.class);
        assertTrue(r.a().isEmpty());
        assertTrue(r.b().isEmpty());
    }

    // ── Arrays ───────────────────────────────────────────────────────────

    public record Items(List<Point> points) {
    }

    @Test
    void listOfRecords() throws DataBindException {
        Items items = mapper.toObject("{ points: [ { x: 1 y: 2 } { x: 3 y: 4 } ] }", Items.class);
        assertEquals(2, items.points().size());
        assertEquals(new Point(1, 2), items.points().get(0));
        assertEquals(new Point(3, 4), items.points().get(1));
    }

    public record IntArrayHolder(int[] values) {
    }

    @Test
    void primitiveIntArray() throws DataBindException {
        IntArrayHolder h = mapper.toObject("{ values: [1 2 3] }", IntArrayHolder.class);
        assertEquals(3, h.values().length);
        assertEquals(2, h.values()[1]);
    }

    public record StringListHolder(List<String> tags) {
    }

    @Test
    void emptyArray() throws DataBindException {
        StringListHolder h = mapper.toObject("{ tags: [] }", StringListHolder.class);
        assertTrue(h.tags().isEmpty());
    }

    // ── Atoms: strings, booleans, null ───────────────────────────────────

    public record Flags(boolean active, String label) {
    }

    @Test
    void booleanAndStringAtoms() throws DataBindException {
        Flags f = mapper.toObject("{ active: true label: GOLD }", Flags.class);
        assertTrue(f.active());
        assertEquals("GOLD", f.label());
    }

    @Test
    void quotedTokenAlwaysBindsAsString() throws DataBindException {
        Flags f = mapper.toObject("{ active: true label: \"true\" }", Flags.class);
        assertEquals("true", f.label());
    }

    public record Nullable(String text) {
    }

    @Test
    void nullKeywordBindsToJavaNull() throws DataBindException {
        Nullable n = mapper.toObject("{ text: null }", Nullable.class);
        assertNull(n.text());
    }

    public record RequiresInt(int x) {
    }

    @Test
    void cannotBindNullToPrimitive() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ x: null }", RequiresInt.class));
    }

    // ── Numeric binding (AtomBinder) ─────────────────────────────────────

    public record Numbers(int i, long l, double d, BigInteger bi, BigDecimal bd) {
    }

    @Test
    void numericTargetTypesAllBindFromPlainIntegerToken() throws DataBindException {
        Numbers n = mapper.toObject(
                "{ i: 42 l: 42 d: 42 bi: 42 bd: 42 }", Numbers.class);
        assertEquals(42, n.i());
        assertEquals(42L, n.l());
        assertEquals(42.0, n.d());
        assertEquals(BigInteger.valueOf(42), n.bi());
        assertEquals(new BigDecimal("42"), n.bd());
    }

    public record HexInt(int value) {
    }

    @Test
    void hexAndDecimalRepresentationsBindEqually() throws DataBindException {
        // §4.3 equivalence: 255 and 0xFF must resolve to equal values.
        assertEquals(255, mapper.toObject("{ value: 255 }", HexInt.class).value());
        assertEquals(255, mapper.toObject("{ value: 0xFF }", HexInt.class).value());
    }

    public record UnderscoreInt(int value) {
    }

    @Test
    void underscoreSeparatedDigitsBindCorrectly() throws DataBindException {
        assertEquals(1_000_000, mapper.toObject("{ value: 1_000_000 }", UnderscoreInt.class).value());
    }

    public record SignedInt(int value) {
    }

    @Test
    void negativeIntegerBinds() throws DataBindException {
        assertEquals(-42, mapper.toObject("{ value: -42 }", SignedInt.class).value());
    }

    public record DoubleHolder(double value) {
    }

    @Test
    void floatFormBindsToDouble() throws DataBindException {
        assertEquals(199.90, mapper.toObject("{ value: 199.90 }", DoubleHolder.class).value(), 0.0001);
    }

    public record IntHolder(int value) {
    }

    @Test
    void floatFormCannotBindToIntegralType() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: 199.90 }", IntHolder.class));
    }

    public record ByteHolder(byte value) {
    }

    @Test
    void overflowingIntegerThrows() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: 1000 }", ByteHolder.class));
    }

    public record DoublePair(double a, double b) {
    }

    @Test
    void infinityAndNanBindToDouble() throws DataBindException {
        DoublePair p = mapper.toObject("{ a: .inf b: .nan }", DoublePair.class);
        assertEquals(Double.POSITIVE_INFINITY, p.a());
        assertTrue(Double.isNaN(p.b()));
    }

    public record NegInf(double value) {
    }

    @Test
    void negativeInfinityBindsToDouble() throws DataBindException {
        assertEquals(Double.NEGATIVE_INFINITY, mapper.toObject("{ value: -.inf }", NegInf.class).value());
    }

    // ── Unions ───────────────────────────────────────────────────────────

    @Union({ Circle.class, Rectangle.class })
    public sealed interface Shape permits Circle, Rectangle {
    }

    public record Circle(int radius) implements Shape {
    }

    public record Rectangle(int width, int height) implements Shape {
    }

    public record ShapeHolder(Shape shape) {
    }

    @Test
    void unionMemberMatchedByCaseInsensitiveSimpleName() throws DataBindException {
        ShapeHolder h = mapper.toObject("{ shape: !circle { radius: 5 } }", ShapeHolder.class);
        assertInstanceOf(Circle.class, h.shape());
        assertEquals(5, ((Circle) h.shape()).radius());
    }

    @Test
    void unionMemberMatchedByCaseInsensitiveSimpleNameOtherMember() throws DataBindException {
        ShapeHolder h = mapper.toObject("{ shape: !rectangle { width: 3 height: 4 } }", ShapeHolder.class);
        assertInstanceOf(Rectangle.class, h.shape());
    }

    @Typename(name = "sq")
    public record Square(int side) implements NamedShape {
    }

    @Union({ Square.class })
    public sealed interface NamedShape permits Square {
    }

    public record NamedShapeHolder(NamedShape shape) {
    }

    @Test
    void unionMemberMatchedByExplicitTypename() throws DataBindException {
        NamedShapeHolder h = mapper.toObject("{ shape: !sq { side: 2 } }", NamedShapeHolder.class);
        assertEquals(2, ((Square) h.shape()).side());
    }

    @Test
    void unionWithoutTypeAnnotationThrows() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ shape: { radius: 5 } }", ShapeHolder.class));
    }

    @Test
    void unionWithUnknownTypeNameThrows() throws DataBindException {
        assertThrows(DataBindException.class,
                () -> mapper.toObject("{ shape: !triangle { a: 1 } }", ShapeHolder.class));
    }

    // ── Full example, adapted from spec §2.1 ─────────────────────────────

    public record Address(String street, String city) {
    }

    public record FullOrder(int orderId, Customer customer, List<Point> items, Optional<Address> shipping) {
    }

    @Test
    void reasonablyFullDocument() throws DataBindException {
        FullOrder order = mapper.toObject("""
                {
                  orderId: 1042
                  customer: { name: "Ada Lovelace" email: "ada@example.com" }
                  items: [ { x: 1 y: 2 } { x: 3 y: 4 } ]
                  shipping: { street: "12 Byron Rd" city: London }
                }
                """, FullOrder.class);

        assertEquals(1042, order.orderId());
        assertEquals("Ada Lovelace", order.customer().name());
        assertEquals(2, order.items().size());
        assertEquals("12 Byron Rd", order.shipping().orElseThrow().street());
        assertEquals("London", order.shipping().orElseThrow().city());
    }
}
