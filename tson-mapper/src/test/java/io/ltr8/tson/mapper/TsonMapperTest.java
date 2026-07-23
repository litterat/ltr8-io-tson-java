package io.ltr8.tson.mapper;

import io.ltr8.annotation.Annotated;
import io.ltr8.annotation.Atom;
import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.parser.ast.Annotation;
import io.ltr8.tson.parser.ast.TokenValue;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ── Annotations (§3.1) ───────────────────────────────────────────────

    public record AnnotatedItem(@Annotated TsonAnnotations meta, String name) {
    }

    @Test
    void annotatedComponentReceivesTheValuesOwnAnnotations() throws DataBindException {
        AnnotatedItem item = mapper.toObject("@doc:\"a widget\" { name: Widget }", AnnotatedItem.class);
        assertEquals("Widget", item.name());
        Annotation doc = item.meta().get("doc").orElseThrow();
        TokenValue text = (TokenValue) doc.value().orElseThrow().coreValue();
        assertEquals("a widget", text.text());
    }

    @Test
    void annotatedComponentIsEmptyWhenTheValueHasNoAnnotations() throws DataBindException {
        AnnotatedItem item = mapper.toObject("{ name: Widget }", AnnotatedItem.class);
        assertTrue(item.meta().values().isEmpty());
    }

    @Test
    void annotatedComponentPreservesRepeatedAnnotationsInSourceOrder() throws DataBindException {
        // §3.1: "An annotation name MAY appear any number of times on a single value; all
        // occurrences are preserved in source order."
        AnnotatedItem item = mapper.toObject("@tag:one @tag:two { name: Widget }", AnnotatedItem.class);
        List<Annotation> tags = item.meta().getAll("tag");
        assertEquals(2, tags.size());
        assertEquals("one", ((TokenValue) tags.get(0).value().orElseThrow().coreValue()).text());
        assertEquals("two", ((TokenValue) tags.get(1).value().orElseThrow().coreValue()).text());
    }

    @Test
    void annotatedComponentDoesNotSeeAnnotationsOnFieldValues() throws DataBindException {
        // Deliberate scope limit: @Annotated only recovers the enclosing value's own annotations,
        // not a field value's -- a bare String field has nowhere in Java to carry its own
        // annotations (see SPEC-FEEDBACK.md).
        AnnotatedItem item = mapper.toObject("{ name: @deprecated Widget }", AnnotatedItem.class);
        assertTrue(item.meta().values().isEmpty());
    }

    public record BadCarrierType(@Annotated String meta, String name) {
    }

    @Test
    void annotatedComponentMustBeOfTypeTsonAnnotations() {
        assertThrows(DataBindException.class,
                () -> mapper.toObject("{ name: Widget meta: x }", BadCarrierType.class));
    }

    public record TwoCarriers(@Annotated TsonAnnotations a, @Annotated TsonAnnotations b, String name) {
    }

    @Test
    void atMostOneAnnotatedComponentAllowed() {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ name: Widget }", TwoCarriers.class));
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

    // ── Maps ─────────────────────────────────────────────────────────────

    public record CountsHolder(Map<String, Integer> counts) {
    }

    @Test
    void mapOfStringToInt() throws DataBindException {
        CountsHolder h = mapper.toObject("{ counts: { apples => 3 pears => 5 } }", CountsHolder.class);
        assertEquals(2, h.counts().size());
        assertEquals(3, h.counts().get("apples"));
        assertEquals(5, h.counts().get("pears"));
    }

    public record LocationsHolder(Map<String, Point> locations) {
    }

    @Test
    void mapOfStringToRecord() throws DataBindException {
        LocationsHolder h = mapper.toObject(
                "{ locations: { origin => { x: 0 y: 0 } target => { x: 3 y: 4 } } }", LocationsHolder.class);
        assertEquals(2, h.locations().size());
        assertEquals(new Point(0, 0), h.locations().get("origin"));
        assertEquals(new Point(3, 4), h.locations().get("target"));
    }

    @Test
    void emptyMap() throws DataBindException {
        CountsHolder h = mapper.toObject("{ counts: {} }", CountsHolder.class);
        assertTrue(h.counts().isEmpty());
    }

    @Test
    void mapDuplicateKeyLastValueWins() throws DataBindException {
        // §2.6: "last value wins" for a duplicate map key, the same rule §2.5 gives record fields --
        // falls out for free here from repeated put() calls in source order.
        CountsHolder h = mapper.toObject("{ counts: { apples => 3 apples => 7 } }", CountsHolder.class);
        assertEquals(1, h.counts().size());
        assertEquals(7, h.counts().get("apples"));
    }

    @Test
    void mapRejectsAbsentSentinelAsKey() {
        // §2.9: "_" MUST NOT appear as a map key -- a resolver-layer constraint, not a grammar
        // one, so the parser itself accepts { _ => 1 } (see ParserTest); toMap is where it's
        // actually rejected.
        assertThrows(DataBindException.class, () -> mapper.toObject("{ counts: { _ => 3 } }", CountsHolder.class));
    }

    @Test
    void mapAllowsAbsentSentinelAsValue() throws DataBindException {
        // §2.9 only restricts the key position -- a value of "_" is legitimately "present with
        // an absent value" (distinct from the entry not existing at all), so this must still bind.
        CountsHolder h = mapper.toObject("{ counts: { apples => _ } }", CountsHolder.class);
        assertEquals(1, h.counts().size());
        assertNull(h.counts().get("apples"));
    }

    public record ByIdHolder(Map<Integer, String> names) {
    }

    @Test
    void mapOfIntegerToString() throws DataBindException {
        // §2.6: a map key is a full data-value, not just a string token -- bound recursively the
        // same way a value is, so a non-string key works with no map-specific code at all.
        ByIdHolder h = mapper.toObject("{ names: { 1 => Alice 2 => Bob } }", ByIdHolder.class);
        assertEquals(2, h.names().size());
        assertEquals("Alice", h.names().get(1));
        assertEquals("Bob", h.names().get(2));
    }

    public record ByUuidHolder(Map<UUID, String> owners) {
    }

    @Test
    void mapOfUuidToString() throws DataBindException {
        // A key type that itself needs a type-ref to resolve (!uuid isn't the default token
        // resolution) still binds correctly -- the key's own DataValue carries its own annotation.
        ByUuidHolder h = mapper.toObject(
                "{ owners: { !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09 => \"Alice\" } }", ByUuidHolder.class);
        assertEquals(1, h.owners().size());
        assertEquals("Alice", h.owners().get(UUID.fromString("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09")));
    }

    // ── Tuples ───────────────────────────────────────────────────────────

    @io.ltr8.annotation.Tuple
    public record NameAndAge(String name, int age) {
    }

    public record PersonHolder(NameAndAge person) {
    }

    @Test
    void tupleBindsFromAnArrayPositionally() throws DataBindException {
        // A tuple is array-shaped on the wire, unlike an ordinary named-field record.
        PersonHolder h = mapper.toObject("{ person: [ Alice 30 ] }", PersonHolder.class);
        assertEquals(new NameAndAge("Alice", 30), h.person());
    }

    @Test
    void tupleRejectsRecordSyntax() throws DataBindException {
        // { name: ... age: ... } is a TSON record, not an array -- @Tuple binds positionally from
        // an ArrayValue only.
        assertThrows(DataBindException.class,
                () -> mapper.toObject("{ person: { name: Alice age: 30 } }", PersonHolder.class));
    }

    @Test
    void tupleRejectsWrongArity() throws DataBindException {
        assertThrows(DataBindException.class, () -> mapper.toObject("{ person: [ Alice ] }", PersonHolder.class));
        assertThrows(DataBindException.class,
                () -> mapper.toObject("{ person: [ Alice 30 extra ] }", PersonHolder.class));
    }

    public record NestedTuple(NameAndAge a, NameAndAge b) {
    }

    @Test
    void tupleElementsBindRecursively() throws DataBindException {
        // A tuple slot's own type is bound the same way any other DataClass is -- here, nested
        // tuples-of-tuples.
        NestedTuple h = mapper.toObject("{ a: [ Alice 30 ] b: [ Bob 25 ] }", NestedTuple.class);
        assertEquals(new NameAndAge("Alice", 30), h.a());
        assertEquals(new NameAndAge("Bob", 25), h.b());
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
    void enumWithoutAtomAnnotationBindsViaEnumStringBridge() throws DataBindException {
        // DefaultClassBinder auto-detects Class#isEnum() the same way it does isRecord()/isArray()
        // -- no @Atom needed, same default EnumStringBridge (by name()) as an annotated enum gets.
        UnannotatedColorHolder h = mapper.toObject("{ color: RED }", UnannotatedColorHolder.class);
        assertEquals(UnannotatedColor.RED, h.color());
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

    // ── toTson (write direction) ──────────────────────────────────────────

    @Test
    void writeSimpleRecord() throws DataBindException {
        assertEquals("{ x: 1 y: 2 }", mapper.toTson(new Point(1, 2)));
    }

    @Test
    void writeNestedRecordRoundTrips() throws DataBindException {
        Order original = mapper.toObject(
                "{ orderId: 1042 customer: { name: \"Ada Lovelace\" email: \"ada@example.com\" } }", Order.class);
        Order roundTripped = mapper.toObject(mapper.toTson(original), Order.class);
        assertEquals(original, roundTripped);
    }

    @Test
    void writeArrayOfRecordsRoundTrips() throws DataBindException {
        Items original = mapper.toObject("{ points: [ { x: 1 y: 2 } { x: 3 y: 4 } ] }", Items.class);
        Items roundTripped = mapper.toObject(mapper.toTson(original), Items.class);
        assertEquals(original, roundTripped);
    }

    @Test
    void writeEmptyArray() throws DataBindException {
        assertEquals("{ tags: [] }", mapper.toTson(mapper.toObject("{ tags: [] }", StringListHolder.class)));
    }

    @Test
    void writeMapRoundTrips() throws DataBindException {
        CountsHolder original = mapper.toObject("{ counts: { apples => 3 pears => 5 } }", CountsHolder.class);
        CountsHolder roundTripped = mapper.toObject(mapper.toTson(original), CountsHolder.class);
        assertEquals(original.counts(), roundTripped.counts());
    }

    @Test
    void writeTupleIsArrayShapedWithNoTypeInformation() throws DataBindException {
        // Schemaless output has no way to say "this array is really a tuple" -- see toTson's own
        // Javadoc, the same accepted loss as an integer's exact width.
        NameAndAge original = new NameAndAge("Alice", 30);
        String tson = mapper.toTson(original);
        assertEquals("[ \"Alice\" 30 ]", tson);

        PersonHolder roundTripped = mapper.toObject("{ person: " + tson + " }", PersonHolder.class);
        assertEquals(original, roundTripped.person());
    }

    @Test
    void writeEnumGoesThroughItsBridgeAsAQuotedStringAndRoundTrips() throws DataBindException {
        // EnumStringBridge.toData() produces a plain String ("RED"), which then writes through the
        // ordinary string path (quoted) -- no enum-specific special case in TsonMapper.write().
        Paint original = mapper.toObject("{ color: RED }", Paint.class);
        String tson = mapper.toTson(original);
        assertEquals("{ color: \"RED\" }", tson);
        assertEquals(original, mapper.toObject(tson, Paint.class));
    }

    @Test
    void writeUnionEmitsTypeRefAndRoundTrips() throws DataBindException {
        ShapeHolder original = mapper.toObject("{ shape: !circle { radius: 5 } }", ShapeHolder.class);
        String tson = mapper.toTson(original);
        assertEquals("{ shape: !circle { radius: 5 } }", tson);
        assertEquals(original, mapper.toObject(tson, ShapeHolder.class));
    }

    @Test
    void writeUnionMemberWithExplicitTypenameUsesItVerbatim() throws DataBindException {
        NamedShapeHolder original = mapper.toObject("{ shape: !sq { side: 2 } }", NamedShapeHolder.class);
        assertEquals("{ shape: !sq { side: 2 } }", mapper.toTson(original));
    }

    @Test
    void writeUuidEmitsTypeRefAndRoundTrips() throws DataBindException {
        UuidHolder original = mapper.toObject("{ value: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09 }", UuidHolder.class);
        String tson = mapper.toTson(original);
        assertEquals("{ value: !uuid \"9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09\" }", tson);
        assertEquals(original, mapper.toObject(tson, UuidHolder.class));
    }

    @Test
    void writeIpv4UsesHostAddressNotToString() throws DataBindException, UnknownHostException {
        // Inet4Address#toString() prepends "/" -- confirmed empirically before writing AtomWriter;
        // this is the regression test for that exact gotcha.
        Ipv4Holder original = mapper.toObject("{ value: !ipv4 192.168.0.1 }", Ipv4Holder.class);
        String tson = mapper.toTson(original);
        assertEquals("{ value: !ipv4 \"192.168.0.1\" }", tson);
        assertEquals(original, mapper.toObject(tson, Ipv4Holder.class));
    }

    @Test
    void writeIpv6UsesHostAddressNotToString() throws DataBindException {
        Ipv6Holder original = mapper.toObject("{ value: !ipv6 \"2001:db8::1\" }", Ipv6Holder.class);
        String tson = mapper.toTson(original);
        assertEquals(original, mapper.toObject(tson, Ipv6Holder.class));
        assertTrue(tson.contains("!ipv6 \""), "expected a quoted !ipv6 value, got: " + tson);
        assertFalse(tson.contains("/"), "Inet6Address#toString()'s leading '/' must not leak through: " + tson);
    }

    @Test
    void writeBinaryEmitsBase64TypeRefRegardlessOfOriginalEncoding() throws DataBindException {
        // No way to recover which of base64/base64url/base32/hex a byte[] came from -- base64 is
        // the writer's arbitrary but reasonable default (see AtomWriter's own Javadoc).
        BytesHolder original = mapper.toObject("{ value: !hex deadbeef }", BytesHolder.class);
        String tson = mapper.toTson(original);
        assertEquals("{ value: !base64 \"3q2+7w==\" }", tson);
        assertArrayEquals(original.value(), mapper.toObject(tson, BytesHolder.class).value());
    }

    public record Uint8Holder(int value) {
    }

    @Test
    void writeIntegerFamilyLosesItsExactWidthByDesign() throws DataBindException {
        // §5.6's !uint8 constrains the *range*, not the host representation -- once bound to a
        // plain int, nothing records that it was ever narrower than "some integer". A schemaless
        // writer has no annotation to reach for, the same reason a schemaless reader can't validate
        // a bare 42 against any width at all. Documented in toTson's own Javadoc, not just here.
        Uint8Holder h = mapper.toObject("{ value: !uint8 42 }", Uint8Holder.class);
        assertEquals("{ value: 42 }", mapper.toTson(h));
    }

    public record OptionalHolder(int required, Optional<String> nickname) {
    }

    @Test
    void writeOmitsAbsentOptionalFieldEntirely() throws DataBindException {
        OptionalHolder h = mapper.toObject("{ required: 1 }", OptionalHolder.class);
        assertEquals("{ required: 1 }", mapper.toTson(h));
    }

    @Test
    void writePresentOptionalFieldWritesTheUnwrappedValue() throws DataBindException {
        OptionalHolder h = mapper.toObject("{ required: 1 nickname: \"Ada\" }", OptionalHolder.class);
        assertEquals("{ required: 1 nickname: \"Ada\" }", mapper.toTson(h));
    }

    public record StringHolder(String value) {
    }

    @Test
    void writeStringEscapesQuotesAndBackslashes() throws DataBindException {
        StringHolder h = new StringHolder("a\"b\\c");
        String tson = mapper.toTson(h);
        assertEquals("{ value: \"a\\\"b\\\\c\" }", tson);
        assertEquals(h, mapper.toObject(tson, StringHolder.class));
    }

    @Test
    void writeNaNAndInfinityUseSpecialValueTokensNotJavaSpelling() throws DataBindException {
        // NumberGrammar's special-value form is spelled .nan/.inf/-.inf -- nothing like Java's own
        // Double.toString() (NaN/Infinity/-Infinity); confirmed empirically before writing this.
        assertEquals("{ value: .nan }", mapper.toTson(new DoubleHolder(Double.NaN)));
        assertEquals("{ value: +.inf }", mapper.toTson(new DoubleHolder(Double.POSITIVE_INFINITY)));
        assertEquals("{ value: -.inf }", mapper.toTson(new DoubleHolder(Double.NEGATIVE_INFINITY)));

        DoubleHolder h = mapper.toObject(mapper.toTson(new DoubleHolder(Double.NaN)), DoubleHolder.class);
        assertTrue(Double.isNaN(h.value()));
    }

    @Test
    void writeRationalComplexDurationRoundTripThroughDataBridges() throws DataBindException {
        DataBindContext fractionContext = DataBindContext.builder().build();
        fractionContext.registerAtom(UserFraction.class, new UserFractionBridge());
        TsonMapper fractionMapper = new TsonMapper(fractionContext);
        UserFractionHolder fraction = fractionMapper.toObject("{ value: !rational \"2/3\" }", UserFractionHolder.class);
        String fractionTson = fractionMapper.toTson(fraction);
        assertEquals("{ value: !rational \"2/3\" }", fractionTson);
        assertEquals(fraction, fractionMapper.toObject(fractionTson, UserFractionHolder.class));

        DataBindContext complexContext = DataBindContext.builder().build();
        complexContext.registerAtom(UserComplex.class, new UserComplexBridge());
        TsonMapper complexMapper = new TsonMapper(complexContext);
        UserComplexHolder complex = complexMapper.toObject("{ value: !complex 3+4i }", UserComplexHolder.class);
        // UserComplexBridge.toData() goes through BigDecimal.valueOf(double), which reflects
        // Double.toString()'s own canonical form -- "3.0", not "3" -- for a whole-number double.
        assertEquals("{ value: !complex \"3.0+4.0i\" }", complexMapper.toTson(complex));

        DataBindContext durationContext = DataBindContext.builder().build();
        durationContext.registerAtom(UserDuration.class, new UserDurationBridge());
        TsonMapper durationMapper = new TsonMapper(durationContext);
        UserDurationHolder duration =
                durationMapper.toObject("{ value: !duration P1Y2M3DT4H5M6S }", UserDurationHolder.class);
        assertEquals("{ value: !duration \"P1Y2M3DT4H5M6S\" }", durationMapper.toTson(duration));
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
