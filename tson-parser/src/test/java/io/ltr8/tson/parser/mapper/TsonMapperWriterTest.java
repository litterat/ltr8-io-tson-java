package io.ltr8.tson.parser.mapper;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.BytesHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.Color;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.CountsHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.Customer;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.DoubleHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.Ipv4Holder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.Ipv6Holder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.Items;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.NameAndAge;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.NamedShapeHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.Order;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.Paint;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.PersonHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.Point;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.ShapeHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.StringListHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserComplex;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserComplexBridge;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserComplexHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserDuration;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserDurationBridge;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserDurationHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserFraction;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserFractionBridge;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UserFractionHolder;
import io.ltr8.tson.parser.mapper.TsonMapperReaderTest.UuidHolder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write-direction counterpart to {@link TsonMapperReaderTest} -- split the same way {@link
 * TsonMapperReader}/{@link TsonMapperWriter} themselves are. Fixture record/class types shared with
 * read-side tests (round-tripping needs to build a value via {@link TsonMapperReader} before writing
 * it back) are reused from {@link TsonMapperReaderTest} directly rather than redeclared here.
 */
class TsonMapperWriterTest {

    private final TsonMapperReader reader = new TsonMapperReader();
    private final TsonMapperWriter writer = new TsonMapperWriter();

    @Test
    void writeSimpleRecord() throws DataBindException {
        assertEquals("{ x: 1 y: 2 }", writer.toTson(new Point(1, 2)));
    }

    @Test
    void writeNestedRecordRoundTrips() throws DataBindException {
        Order original = reader.toObject(
                "{ orderId: 1042 customer: { name: \"Ada Lovelace\" email: \"ada@example.com\" } }", Order.class);
        Order roundTripped = reader.toObject(writer.toTson(original), Order.class);
        assertEquals(original, roundTripped);
    }

    @Test
    void writeArrayOfRecordsRoundTrips() throws DataBindException {
        Items original = reader.toObject("{ points: [ { x: 1 y: 2 } { x: 3 y: 4 } ] }", Items.class);
        Items roundTripped = reader.toObject(writer.toTson(original), Items.class);
        assertEquals(original, roundTripped);
    }

    @Test
    void writeEmptyArray() throws DataBindException {
        assertEquals("{ tags: [] }", writer.toTson(reader.toObject("{ tags: [] }", StringListHolder.class)));
    }

    @Test
    void writeMapRoundTrips() throws DataBindException {
        CountsHolder original = reader.toObject("{ counts: { apples => 3 pears => 5 } }", CountsHolder.class);
        CountsHolder roundTripped = reader.toObject(writer.toTson(original), CountsHolder.class);
        assertEquals(original.counts(), roundTripped.counts());
    }

    @Test
    void writeTupleIsArrayShapedWithNoTypeInformation() throws DataBindException {
        // Schemaless output has no way to say "this array is really a tuple" -- see toTson's own
        // Javadoc, the same accepted loss as an integer's exact width.
        NameAndAge original = new NameAndAge("Alice", 30);
        String tson = writer.toTson(original);
        assertEquals("[ \"Alice\" 30 ]", tson);

        PersonHolder roundTripped = reader.toObject("{ person: " + tson + " }", PersonHolder.class);
        assertEquals(original, roundTripped.person());
    }

    @Test
    void writeEnumGoesThroughItsBridgeAsAQuotedStringAndRoundTrips() throws DataBindException {
        // EnumStringBridge.toData() produces a plain String ("RED"), which then writes through the
        // ordinary string path (quoted) -- no enum-specific special case in TsonMapperWriter.write().
        Paint original = reader.toObject("{ color: RED }", Paint.class);
        String tson = writer.toTson(original);
        assertEquals("{ color: \"RED\" }", tson);
        assertEquals(original, reader.toObject(tson, Paint.class));
    }

    @Test
    void writeUnionEmitsTypeRefAndRoundTrips() throws DataBindException {
        ShapeHolder original = reader.toObject("{ shape: !circle { radius: 5 } }", ShapeHolder.class);
        String tson = writer.toTson(original);
        assertEquals("{ shape: !circle { radius: 5 } }", tson);
        assertEquals(original, reader.toObject(tson, ShapeHolder.class));
    }

    @Test
    void writeUnionMemberWithExplicitTypenameUsesItVerbatim() throws DataBindException {
        NamedShapeHolder original = reader.toObject("{ shape: !sq { side: 2 } }", NamedShapeHolder.class);
        assertEquals("{ shape: !sq { side: 2 } }", writer.toTson(original));
    }

    @Test
    void writeUuidEmitsTypeRefAndRoundTrips() throws DataBindException {
        UuidHolder original = reader.toObject("{ value: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09 }", UuidHolder.class);
        String tson = writer.toTson(original);
        assertEquals("{ value: !uuid \"9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09\" }", tson);
        assertEquals(original, reader.toObject(tson, UuidHolder.class));
    }

    @Test
    void writeIpv4UsesHostAddressNotToString() throws DataBindException {
        // Inet4Address#toString() prepends "/" -- confirmed empirically before writing AtomWriter;
        // this is the regression test for that exact gotcha.
        Ipv4Holder original = reader.toObject("{ value: !ipv4 192.168.0.1 }", Ipv4Holder.class);
        String tson = writer.toTson(original);
        assertEquals("{ value: !ipv4 \"192.168.0.1\" }", tson);
        assertEquals(original, reader.toObject(tson, Ipv4Holder.class));
    }

    @Test
    void writeIpv6UsesHostAddressNotToString() throws DataBindException {
        Ipv6Holder original = reader.toObject("{ value: !ipv6 \"2001:db8::1\" }", Ipv6Holder.class);
        String tson = writer.toTson(original);
        assertEquals(original, reader.toObject(tson, Ipv6Holder.class));
        assertTrue(tson.contains("!ipv6 \""), "expected a quoted !ipv6 value, got: " + tson);
        assertFalse(tson.contains("/"), "Inet6Address#toString()'s leading '/' must not leak through: " + tson);
    }

    @Test
    void writeBinaryEmitsBase64TypeRefRegardlessOfOriginalEncoding() throws DataBindException {
        // No way to recover which of base64/base64url/base32/hex a byte[] came from -- base64 is
        // the writer's arbitrary but reasonable default (see AtomWriter's own Javadoc).
        BytesHolder original = reader.toObject("{ value: !hex deadbeef }", BytesHolder.class);
        String tson = writer.toTson(original);
        assertEquals("{ value: !base64 \"3q2+7w==\" }", tson);
        assertArrayEquals(original.value(), reader.toObject(tson, BytesHolder.class).value());
    }

    public record Uint8Holder(int value) {
    }

    @Test
    void writeIntegerFamilyLosesItsExactWidthByDesign() throws DataBindException {
        // §5.6's !uint8 constrains the *range*, not the host representation -- once bound to a
        // plain int, nothing records that it was ever narrower than "some integer". A schemaless
        // writer has no annotation to reach for, the same reason a schemaless reader can't validate
        // a bare 42 against any width at all. Documented in toTson's own Javadoc, not just here.
        Uint8Holder h = reader.toObject("{ value: !uint8 42 }", Uint8Holder.class);
        assertEquals("{ value: 42 }", writer.toTson(h));
    }

    public record OptionalHolder(int required, Optional<String> nickname) {
    }

    @Test
    void writeOmitsAbsentOptionalFieldEntirely() throws DataBindException {
        OptionalHolder h = reader.toObject("{ required: 1 }", OptionalHolder.class);
        assertEquals("{ required: 1 }", writer.toTson(h));
    }

    @Test
    void writePresentOptionalFieldWritesTheUnwrappedValue() throws DataBindException {
        OptionalHolder h = reader.toObject("{ required: 1 nickname: \"Ada\" }", OptionalHolder.class);
        assertEquals("{ required: 1 nickname: \"Ada\" }", writer.toTson(h));
    }

    public record StringHolder(String value) {
    }

    @Test
    void writeStringEscapesQuotesAndBackslashes() throws DataBindException {
        StringHolder h = new StringHolder("a\"b\\c");
        String tson = writer.toTson(h);
        assertEquals("{ value: \"a\\\"b\\\\c\" }", tson);
        assertEquals(h, reader.toObject(tson, StringHolder.class));
    }

    @Test
    void writeNaNAndInfinityUseSpecialValueTokensNotJavaSpelling() throws DataBindException {
        // NumberGrammar's special-value form is spelled .nan/.inf/-.inf -- nothing like Java's own
        // Double.toString() (NaN/Infinity/-Infinity); confirmed empirically before writing this.
        assertEquals("{ value: .nan }", writer.toTson(new DoubleHolder(Double.NaN)));
        assertEquals("{ value: +.inf }", writer.toTson(new DoubleHolder(Double.POSITIVE_INFINITY)));
        assertEquals("{ value: -.inf }", writer.toTson(new DoubleHolder(Double.NEGATIVE_INFINITY)));

        DoubleHolder h = reader.toObject(writer.toTson(new DoubleHolder(Double.NaN)), DoubleHolder.class);
        assertTrue(Double.isNaN(h.value()));
    }

    @Test
    void writeRationalComplexDurationRoundTripThroughDataBridges() throws DataBindException {
        DataBindContext fractionContext = DataBindContext.builder().build();
        fractionContext.registerAtom(UserFraction.class, new UserFractionBridge());
        TsonMapperReader fractionReader = new TsonMapperReader(fractionContext);
        TsonMapperWriter fractionWriter = new TsonMapperWriter(fractionContext);
        UserFractionHolder fraction = fractionReader.toObject("{ value: !rational \"2/3\" }", UserFractionHolder.class);
        String fractionTson = fractionWriter.toTson(fraction);
        assertEquals("{ value: !rational \"2/3\" }", fractionTson);
        assertEquals(fraction, fractionReader.toObject(fractionTson, UserFractionHolder.class));

        DataBindContext complexContext = DataBindContext.builder().build();
        complexContext.registerAtom(UserComplex.class, new UserComplexBridge());
        TsonMapperReader complexReader = new TsonMapperReader(complexContext);
        TsonMapperWriter complexWriter = new TsonMapperWriter(complexContext);
        UserComplexHolder complex = complexReader.toObject("{ value: !complex 3+4i }", UserComplexHolder.class);
        // UserComplexBridge.toData() goes through BigDecimal.valueOf(double), which reflects
        // Double.toString()'s own canonical form -- "3.0", not "3" -- for a whole-number double.
        assertEquals("{ value: !complex \"3.0+4.0i\" }", complexWriter.toTson(complex));

        DataBindContext durationContext = DataBindContext.builder().build();
        durationContext.registerAtom(UserDuration.class, new UserDurationBridge());
        TsonMapperReader durationReader = new TsonMapperReader(durationContext);
        TsonMapperWriter durationWriter = new TsonMapperWriter(durationContext);
        UserDurationHolder duration =
                durationReader.toObject("{ value: !duration P1Y2M3DT4H5M6S }", UserDurationHolder.class);
        assertEquals("{ value: !duration \"P1Y2M3DT4H5M6S\" }", durationWriter.toTson(duration));
    }

    // ── Full example, adapted from spec §2.1 ─────────────────────────────

    public record Address(String street, String city) {
    }

    public record FullOrder(int orderId, Customer customer, List<Point> items, Optional<Address> shipping) {
    }

    @Test
    void reasonablyFullDocument() throws DataBindException {
        FullOrder order = reader.toObject("""
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
