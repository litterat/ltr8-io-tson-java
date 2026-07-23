package io.ltr8.tson.mapper;

import io.ltr8.annotation.Atom;
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.parser.resolver.vocab.Complex;
import io.ltr8.tson.parser.resolver.vocab.IsoDuration;
import io.ltr8.tson.parser.resolver.vocab.Rational;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    // ── Atoms: enums (EnumStringBridge) ──────────────────────────────────

    @Atom
    public enum Color { RED, GREEN, BLUE }

    public record Paint(Color color) {
    }

    @Test
    void enumBindsViaEnumStringBridge() throws DataBindException {
        Paint p = mapper.toObject("{ color: RED }", Paint.class);
        assertEquals(Color.RED, p.color());
    }

    @Test
    void enumRoundTripsThroughEveryMember() throws DataBindException {
        for (Color c : Color.values()) {
            Paint p = mapper.toObject("{ color: " + c.name() + " }", Paint.class);
            assertEquals(c, p.color());
        }
    }

    @Test
    void unrecognizedEnumMemberThrows() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ color: PURPLE }", Paint.class));
    }

    public record UnannotatedColorHolder(UnannotatedColor color) {
    }

    public enum UnannotatedColor { RED, GREEN, BLUE }

    @Test
    void enumWithoutAtomAnnotationDoesNotBind() throws DataBindException {
        // DefaultAtomBinder only wires EnumStringBridge when the enum type carries @Atom
        // (DefaultAtomBinder.resolveAtom's isEnum() check is gated on enumAtom != null) --
        // documenting the current requirement, not (yet) a claim it's the right one.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ color: RED }", UnannotatedColorHolder.class));
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

    // ── Built-in type vocabulary (§5) ───────────────────────────────────────

    @Test
    void unannotatedTargetTypeStillBindsAsBefore() throws DataBindException {
        // No !type-ref present at all -- falls through to plain BaseTypeResolver/AtomBinder,
        // unaffected by the built-in vocabulary path existing.
        assertEquals(42, mapper.toObject("{ value: 42 }", IntHolder.class).value());
    }

    @Test
    void unrecognizedTypeRefThrowsRatherThanSilentlyFallingThrough() throws DataBindException {
        // SPEC-FEEDBACK.md #7: §5.1's "preserved as an uninterpreted marker" rule governs the
        // Class 1 processing step (tson-parser), not this binding layer -- an unresolvable
        // annotation on a value we're actively binding to a declared type is treated as an error,
        // so a typo doesn't silently disable the validation the author intended.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !notabuiltin 42 }", IntHolder.class));
    }

    @Test
    void caseSensitiveTypoOfABuiltinNameIsRejectedRatherThanSilentlyUnvalidated() throws DataBindException {
        // §5.1: "Annotation names are case-sensitive." !Int32 is not !int32.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !Int32 42 }", IntHolder.class));
    }

    @Test
    void builtinIntegerAnnotationBindsDirectlyToTheDeclaredTarget() throws DataBindException {
        // !uint8's own contract (0..255) is checked, then narrowed straight to the declared int
        // field -- no intermediate natural-width value.
        assertEquals(200, mapper.toObject("{ value: !uint8 200 }", IntHolder.class).value());
    }

    @Test
    void builtinIntegerAnnotationValidatesAgainstItsOwnRangeNotJustTheTarget() throws DataBindException {
        // 300 would fit comfortably in an int field, but uint8's own declared range rejects it --
        // the built-in vocabulary's constraint applies regardless of how wide the target is.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !uint8 300 }", IntHolder.class));
    }

    @Test
    void builtinIntegerAnnotationRejectsATargetNarrowerThanItsOwnGuarantee() throws DataBindException {
        // int32 guarantees up to 2^31-1; a byte target can't hold 200 even though 200 alone would
        // satisfy int32's own range.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !int32 200 }", ByteHolder.class));
    }

    @Test
    void unsignedNegativeValueParsesThenFailsValidationThroughTheMapper() throws DataBindException {
        // §5.6: "the range constraint, not the lexer, enforces unsignedness" -- exercised end to end.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !uint32 -10 }", IntHolder.class));
    }

    @Test
    void nonIntegerTokenUnderAnIntegerAnnotationIsAParseErrorThroughTheMapper() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !int32 3.14 }", IntHolder.class));
    }

    public record WideInt(long value) {
    }

    @Test
    void extendedIntegerFamilyWidthsAreReachableThroughTheMapper() throws DataBindException {
        // SPEC-FEEDBACK.md #6: int16 isn't in §5.6's published table but is implemented anyway.
        assertEquals(30000L, mapper.toObject("{ value: !int16 30000 }", WideInt.class).value());
    }

    public record DecimalHolder(BigDecimal value) {
    }

    @Test
    void builtinNumberAnnotationPreservesExactValueThroughTheMapper() throws DataBindException {
        // !number is the exact tier -- 199.90's trailing zero survives, unlike a double round-trip.
        assertEquals(new BigDecimal("199.90"), mapper.toObject("{ value: !number 199.90 }", DecimalHolder.class).value());
    }

    @Test
    void builtinNumberAnnotationRejectsSpecialValuesThroughTheMapper() throws DataBindException {
        // §5.6: "!number, being exact, does not accept the special values."
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !number .inf }", DecimalHolder.class));
    }

    @Test
    void builtinFloat32AnnotationBindsThroughTheMapper() throws DataBindException {
        assertEquals(3.14f, mapper.toObject("{ value: !float32 3.14 }", FloatHolder.class).value());
    }

    public record FloatHolder(float value) {
    }

    @Test
    void builtinFloat64AnnotationAcceptsHexFloatThroughTheMapper() throws DataBindException {
        // 0x1.8p3 = 1.5 * 2^3 = 12.0 -- only reachable through the typed atom, never base resolution.
        assertEquals(12.0, mapper.toObject("{ value: !float64 0x1.8p3 }", DoubleHolder.class).value());
    }

    @Test
    void builtinFloat64AnnotationRejectsBasedIntegerThroughTheMapper() throws DataBindException {
        // §5.6: float atoms accept integer/float/hex-float/special-value, not based-integer.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !float64 0xFF }", DoubleHolder.class));
    }

    // ── UUID (§5.5) ──────────────────────────────────────────────────────

    public record UuidHolder(UUID value) {
    }

    @Test
    void builtinUuidAnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        // Unlike Rational/Complex, UUID isn't a Java record, so it doesn't collide with
        // tson-bind's record auto-detection -- but it also can't self-declare @Atom (it's a JDK
        // class), so TsonMapper's default DataBindContext pre-registers it (see TsonMapper's
        // defaultContext()) rather than requiring every caller to do so themselves.
        UuidHolder h = mapper.toObject("{ value: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09 }", UuidHolder.class);
        assertEquals(UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09"), h.value());
    }

    @Test
    void builtinUuidAnnotationRejectsMalformedUuidThroughTheMapper() throws DataBindException {
        // UUID.fromString itself would accept "1-2-3-4-5" -- UuidType's own shape check must not.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !uuid 1-2-3-4-5 }", UuidHolder.class));
    }

    // ── URI (§5.5) ───────────────────────────────────────────────────────

    public record UriHolder(URI value) {
    }

    @Test
    void builtinUriAnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        // Like UUID, java.net.URI isn't a record or an array, so no auto-detection collision --
        // but it also can't self-declare @Atom, being a JDK class, so TsonMapper's default context
        // pre-registers it the same way it does UUID/LocalDate. Quoted because ':' '/' '?' '=' are
        // not legal unquoted-token characters (§7.2) -- the URI value itself is unaffected by that,
        // it's a lexer-layer constraint, not a UriType one.
        UriHolder h = mapper.toObject("{ value: !uri \"https://example.com/a/b?x=1\" }", UriHolder.class);
        assertEquals(URI.create("https://example.com/a/b?x=1"), h.value());
    }

    @Test
    void builtinUriAnnotationRejectsMalformedUriThroughTheMapper() throws DataBindException {
        // An unescaped space is not valid anywhere in a URI -- java.net.URI itself rejects it, so
        // this is really exercising the mapper wiring rather than UriType's own leniency.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !uri \"http://example.com/a b\" }", UriHolder.class));
    }

    // ── IPv4 (§5.5) ──────────────────────────────────────────────────────

    public record Ipv4Holder(Inet4Address value) {
    }

    @Test
    void builtinIpv4AnnotationBindsDirectlyThroughTheMapper() throws DataBindException, UnknownHostException {
        // Ipv4Type#read always returns Inet4Address specifically (never the broader InetAddress),
        // and TsonMapper's default context registers exactly that class -- see defaultContext()'s
        // Javadoc on why the field must be declared Inet4Address, not InetAddress, to bind directly.
        Ipv4Holder h = mapper.toObject("{ value: !ipv4 192.168.0.1 }", Ipv4Holder.class);
        assertEquals(InetAddress.getByAddress(new byte[]{(byte) 192, (byte) 168, 0, 1}), h.value());
    }

    @Test
    void builtinIpv4AnnotationRejectsLenientFormsThroughTheMapper() throws DataBindException {
        // InetAddress.ofLiteral itself would accept both of these -- Ipv4Type's own RFC 3986
        // dec-octet check must not.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !ipv4 010.0.0.1 }", Ipv4Holder.class));
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !ipv4 3232235521 }", Ipv4Holder.class));
    }

    // ── IPv6 (§5.5) ──────────────────────────────────────────────────────

    public record Ipv6Holder(Inet6Address value) {
    }

    @Test
    void builtinIpv6AnnotationBindsDirectlyThroughTheMapper() throws DataBindException, UnknownHostException {
        // Ipv6Type#read always returns Inet6Address specifically -- including for IPv4-mapped
        // input text, where the generic InetAddress.getByAddress(byte[]) would otherwise silently
        // hand back an Inet4Address instead (see Ipv6Type's Javadoc). Quoted because ':' is not a
        // legal unquoted-token character (§7.2).
        Ipv6Holder h = mapper.toObject("{ value: !ipv6 \"2001:db8::1\" }", Ipv6Holder.class);
        assertEquals(Inet6Address.getByAddress(null,
                new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1}, -1), h.value());
    }

    @Test
    void builtinIpv6AnnotationRejectsAmbiguousCompressionThroughTheMapper() throws DataBindException {
        // More than one "::" run is ambiguous -- how many zero groups each one represents can't be
        // determined -- so Ipv6Type's own grammar check must reject it.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !ipv6 \"1::2::3\" }", Ipv6Holder.class));
    }

    // ── Temporal types (§5.4) ────────────────────────────────────────────

    public record DateHolder(LocalDate value) {
    }

    @Test
    void builtinDateAnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        // LocalDate isn't a record or an array, so no auto-detection collision -- but it also
        // can't self-declare @Atom, being a JDK class, so TsonMapper's default context
        // pre-registers it the same way it does UUID.
        DateHolder h = mapper.toObject("{ value: !date 2025-03-13 }", DateHolder.class);
        assertEquals(LocalDate.of(2025, 3, 13), h.value());
    }

    @Test
    void builtinDateAnnotationRejectsExtendedYearThroughTheMapper() throws DataBindException {
        // LocalDate.parse("+12025-03-13") would succeed on its own -- DateType's own shape check
        // must not accept RFC 3339's stricter 4-digit-year requirement being violated.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !date +12025-03-13 }", DateHolder.class));
    }

    public record TimeHolder(OffsetTime value) {
    }

    @Test
    void builtinTimeAnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        TimeHolder h = mapper.toObject("{ value: !time \"10:15:30Z\" }", TimeHolder.class);
        assertEquals(OffsetTime.parse("10:15:30Z"), h.value());
    }

    public record DateTimeHolder(OffsetDateTime value) {
    }

    @Test
    void builtinDateTimeAnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        DateTimeHolder h = mapper.toObject("{ value: !datetime \"2025-03-13T10:15:30Z\" }", DateTimeHolder.class);
        assertEquals(OffsetDateTime.parse("2025-03-13T10:15:30Z"), h.value());
    }

    public record DurationHolder(IsoDuration value) {
    }

    @Test
    void directBindingToTsonsOwnIsoDurationDoesNotWork() throws DataBindException {
        // IsoDuration is itself a Java record (calendarPart, clockPart) -- same collision as
        // Rational/Complex, same reason: tson-bind's record auto-detection claims it first.
        assertThrows(DataBindException.class,
                () -> mapper.toObject("{ value: !duration P1Y2M3DT4H5M6S }", DurationHolder.class));
    }

    /** Stand-in for an application's own preferred duration representation -- e.g. threeten-extra's PeriodDuration, or just a total estimate collapsing calendar units. */
    public record UserDuration(int years, int months, int days, long totalSeconds) {
    }

    public static class UserDurationBridge implements DataBridge<IsoDuration, UserDuration> {
        @Override
        public IsoDuration toData(UserDuration d) {
            return new IsoDuration(Period.of(d.years(), d.months(), d.days()),
                    java.time.Duration.ofSeconds(d.totalSeconds()));
        }

        @Override
        public UserDuration toObject(IsoDuration d) {
            Period p = d.calendarPart();
            return new UserDuration(p.getYears(), p.getMonths(), p.getDays(), d.clockPart().toSeconds());
        }
    }

    public record UserDurationHolder(UserDuration value) {
    }

    @Test
    void durationBindsToAThirdPartyTypeViaRegisteredDataBridge() throws DataBindException {
        DataBindContext context = DataBindContext.builder().build();
        context.registerAtom(UserDuration.class, new UserDurationBridge());
        TsonMapper bridgedMapper = new TsonMapper(context);

        UserDurationHolder h = bridgedMapper.toObject("{ value: !duration P1Y2M3DT4H5M6S }", UserDurationHolder.class);
        assertEquals(new UserDuration(1, 2, 3, 4 * 3600L + 5 * 60L + 6L), h.value());
    }

    // ── Binary types (§5.3) ──────────────────────────────────────────────

    public record BytesHolder(byte[] value) {
    }

    @Test
    void directBindingToByteArrayFailsWithoutTsonMappersDefaultPreRegistration() throws DataBindException {
        // byte[].isArray() is true, so DefaultClassBinder's array auto-detection would claim it
        // ahead of the atom/vocabulary path -- the same shape of collision Rational/Complex have
        // with record auto-detection, just against arrays instead of records -- on a bare context
        // that hasn't pre-registered byte[].class the way TsonMapper's own default constructor does
        // (see TsonMapper.defaultContext()). This documents *why* that pre-registration exists.
        TsonMapper bareMapper = new TsonMapper(DataBindContext.builder().build());
        assertThrows(DataBindException.class, () -> bareMapper.toObject("{ value: !base64 TWFu }", BytesHolder.class));
    }

    @Test
    void builtinBase64AnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        BytesHolder h = mapper.toObject("{ value: !base64 TWFu }", BytesHolder.class);
        assertArrayEquals("Man".getBytes(java.nio.charset.StandardCharsets.UTF_8), h.value());
    }

    @Test
    void builtinBase64UrlAnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        BytesHolder h = mapper.toObject("{ value: !base64url TWFu }", BytesHolder.class);
        assertArrayEquals("Man".getBytes(java.nio.charset.StandardCharsets.UTF_8), h.value());
    }

    @Test
    void builtinHexAnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        BytesHolder h = mapper.toObject("{ value: !hex deadbeef }", BytesHolder.class);
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}, h.value());
    }

    @Test
    void builtinBase32AnnotationBindsDirectlyThroughTheMapper() throws DataBindException {
        BytesHolder h = mapper.toObject("{ value: !base32 MZXW6YTB }", BytesHolder.class);
        assertArrayEquals("fooba".getBytes(java.nio.charset.StandardCharsets.UTF_8), h.value());
    }

    @Test
    void builtinBase64AnnotationRejectsMissingPaddingThroughTheMapper() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !base64 TWE }", BytesHolder.class));
    }

    // ── Rational/Complex: binding to a richer third-party type via DataBridge ──────────────

    public record RationalHolder(Rational value) {
    }

    @Test
    void directBindingToTsonsOwnRationalDoesNotWork() throws DataBindException {
        // Rational is itself a Java record (numerator, denominator) -- DefaultClassBinder auto-
        // detects real records ahead of anything atom-related, so a target of Rational.class gets
        // treated as a 2-field record, not routed through RationalType at all. The value's core is
        // a token ("2/3"), not a record, so binding fails outright. This is exactly why the
        // recommended path is a DataBridge (below), not direct binding to tson-parser's own minimal
        // Rational/Complex types.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !rational \"2/3\" }", RationalHolder.class));
    }

    /** Stand-in for a richer third-party type an application might already use, e.g. Apache Commons Math's {@code BigFraction} -- structurally similar, but a different class the binder has never heard of. */
    public record UserFraction(long numerator, long denominator) {
    }

    public static class UserFractionBridge implements DataBridge<Rational, UserFraction> {
        @Override
        public Rational toData(UserFraction f) {
            return new Rational(BigInteger.valueOf(f.numerator()), BigInteger.valueOf(f.denominator()));
        }

        @Override
        public UserFraction toObject(Rational r) {
            return new UserFraction(r.numerator().longValueExact(), r.denominator().longValueExact());
        }
    }

    public record UserFractionHolder(UserFraction value) {
    }

    @Test
    void rationalBindsToAThirdPartyTypeViaRegisteredDataBridge() throws DataBindException {
        DataBindContext context = DataBindContext.builder().build();
        context.registerAtom(UserFraction.class, new UserFractionBridge());
        TsonMapper bridgedMapper = new TsonMapper(context);

        UserFractionHolder h = bridgedMapper.toObject("{ value: !rational \"2/3\" }", UserFractionHolder.class);
        assertEquals(new UserFraction(2, 3), h.value());
    }

    public record ComplexHolder(Complex value) {
    }

    @Test
    void directBindingToTsonsOwnComplexDoesNotWork() throws DataBindException {
        // Same reasoning as Rational: Complex is itself a Java record (real, imaginary), so it's
        // auto-detected as a record target, not routed through ComplexType.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ value: !complex 3+4i }", ComplexHolder.class));
    }

    /** Stand-in for e.g. Apache Commons Math's {@code Complex} (always double-precision, unlike tson-parser's exact-by-default {@link Complex}). */
    public record UserComplex(double real, double imaginary) {
    }

    public static class UserComplexBridge implements DataBridge<Complex, UserComplex> {
        @Override
        public Complex toData(UserComplex c) {
            return new Complex(BigDecimal.valueOf(c.real()), BigDecimal.valueOf(c.imaginary()));
        }

        @Override
        public UserComplex toObject(Complex c) {
            return new UserComplex(c.real().doubleValue(), c.imaginary().doubleValue());
        }
    }

    public record UserComplexHolder(UserComplex value) {
    }

    @Test
    void complexBindsToAThirdPartyTypeViaRegisteredDataBridge() throws DataBindException {
        DataBindContext context = DataBindContext.builder().build();
        context.registerAtom(UserComplex.class, new UserComplexBridge());
        TsonMapper bridgedMapper = new TsonMapper(context);

        UserComplexHolder h = bridgedMapper.toObject("{ value: !complex 3+4i }", UserComplexHolder.class);
        assertEquals(new UserComplex(3.0, 4.0), h.value());
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
