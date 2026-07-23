package io.ltr8.tson.parser;

import io.ltr8.tson.parser.ast.AbsentValue;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.MapValue;
import io.ltr8.tson.parser.ast.RecordValue;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    private static Document parse(String source) {
        return new Parser(source).parseDocument();
    }

    private static DataValue root(String source) {
        return parse(source).root();
    }

    private static TokenValue token(DataValue v) {
        return assertInstanceOf(TokenValue.class, v.coreValue());
    }

    // ── Bare tokens as the whole document ───────────────────────────────

    @Test
    void unquotedTokenRoot() {
        TokenValue t = token(root("Alice"));
        assertEquals("Alice", t.text());
        assertEquals(TokenForm.UNQUOTED, t.form());
    }

    @Test
    void quotedTokenRoot() {
        TokenValue t = token(root("\"has spaces\""));
        assertEquals("has spaces", t.text());
        assertEquals(TokenForm.SINGLE_LINE_QUOTED, t.form());
    }

    @Test
    void absentRoot() {
        assertInstanceOf(AbsentValue.class, root("_").coreValue());
    }

    @Test
    void emptyBraceRoot() {
        assertInstanceOf(EmptyBrace.class, root("{}").coreValue());
        assertInstanceOf(EmptyBrace.class, root("{   }").coreValue());
    }

    // ── Document header ──────────────────────────────────────────────────

    @Test
    void idDirectiveOnly() {
        Document doc = parse("!!id:\"https://example.com/x.tn1\"\n_");
        assertEquals("https://example.com/x.tn1", doc.id().orElseThrow());
        assertTrue(doc.schema().isEmpty());
        assertInstanceOf(AbsentValue.class, doc.root().coreValue());
    }

    @Test
    void idAndSchemaDirectives() {
        Document doc = parse("""
                !!id:"https://example.com/orders/1042.tn1"
                !!schema:"https://example.com/order.tn1"
                Alice
                """);
        assertEquals("https://example.com/orders/1042.tn1", doc.id().orElseThrow());
        assertEquals("https://example.com/order.tn1", doc.schema().orElseThrow());
        assertEquals("Alice", token(doc.root()).text());
    }

    @Test
    void schemaDirectiveWithoutId() {
        Document doc = parse("!!schema:\"https://example.com/order.tn1\" Alice");
        assertTrue(doc.id().isEmpty());
        assertEquals("https://example.com/order.tn1", doc.schema().orElseThrow());
    }

    @Test
    void noHeaderJustAValue() {
        Document doc = parse("Alice");
        assertTrue(doc.id().isEmpty());
        assertTrue(doc.schema().isEmpty());
    }

    @Test
    void metaDirectiveIsRejectedAsSchemaDocument() {
        assertThrows(SchemaDocumentException.class, () -> parse("!!meta:\"https://example.com/m.tn1\" { }"));
    }

    @Test
    void idThenMetaIsRejectedAsSchemaDocument() {
        assertThrows(SchemaDocumentException.class,
                () -> parse("!!id:\"https://example.com/x.tn1\"\n!!meta:\"https://example.com/m.tn1\" { }"));
    }

    @Test
    void multilineTokenAsDirectiveArgumentIsParseError() {
        assertThrows(ParseException.class, () -> parse("!!id:\"\"\"\nx\n\"\"\"\n_"));
    }

    @Test
    void directiveNameNotAdjacentToBangBangIsParseError() {
        // Lexically "!!" then whitespace then "id" -- two BANG tokens can't merge into DIRECTIVE
        // across a gap, so this actually fails as "unexpected content", which is still correct:
        // it's not a valid document header either way. Use a directly-malformed but same-shape
        // case: colon not adjacent to the directive name.
        assertThrows(ParseException.class, () -> parse("!!id :\"https://example.com/x.tn1\"\n_"));
    }

    @Test
    void unknownDirectiveNameInHeaderIsParseError() {
        assertThrows(ParseException.class, () -> parse("!!bogus:\"x\"\n_"));
    }

    @Test
    void extraContentAfterRootValueIsParseError() {
        assertThrows(ParseException.class, () -> parse("Alice Bob"));
    }

    // ── Directive arguments must be valid URIs (§3.3) ────────────────────
    // "in every directive of this series the argument is a URI or file reference (RFC 3986)" --
    // checked via java.net.URI's own constructor, the same JDK type (and the same accepted
    // RFC-2396-vs-3986 gap) UriParser binds !uri through.

    @Test
    void idDirectiveArgumentMustBeAValidUri() {
        // An unescaped space is not valid anywhere in a URI.
        assertThrows(ParseException.class, () -> parse("!!id:\"not a uri\"\n_"));
    }

    @Test
    void schemaDirectiveArgumentInHeaderMustBeAValidUri() {
        assertThrows(ParseException.class, () -> parse("!!schema:\"not a uri\" Alice"));
    }

    @Test
    void schemaDirectiveArgumentOnFieldValueMustBeAValidUri() {
        assertThrows(ParseException.class, () -> parse("{ x: !!schema:\"not a uri\" 1 }"));
    }

    @Test
    void metaDirectiveArgumentMustBeAValidUriEvenThoughTheDocumentIsRejectedEitherWay() {
        // A malformed !!meta argument is a genuine ParseException, not merely "a well-formed
        // schema document this processor doesn't support" -- SchemaDocumentException requires the
        // directive itself to actually be well-formed first.
        assertThrows(ParseException.class, () -> parse("!!meta:\"not a uri\" { }"));
    }

    // ── Records ──────────────────────────────────────────────────────────

    @Test
    void recordWithOneField() {
        RecordValue rec = assertInstanceOf(RecordValue.class, root("{ name: Alice }").coreValue());
        assertEquals(1, rec.fields().size());
        assertEquals("name", rec.fields().get(0).name());
        assertEquals("Alice", token(rec.fields().get(0).value().value()).text());
    }

    @Test
    void recordNoSeparatorNeededAroundBraces() {
        RecordValue rec = assertInstanceOf(RecordValue.class, root("{name:Alice}").coreValue());
        assertEquals("Alice", token(rec.fields().get(0).value().value()).text());
    }

    @Test
    void recordWithMultipleFieldsCommaAndWhitespaceSeparators() {
        RecordValue rec = assertInstanceOf(RecordValue.class,
                root("{ a: 1, b: 2 c: 3 }").coreValue());
        assertEquals(3, rec.fields().size());
        assertEquals("a", rec.fields().get(0).name());
        assertEquals("b", rec.fields().get(1).name());
        assertEquals("c", rec.fields().get(2).name());
    }

    @Test
    void recordFieldNameCanBeQuoted() {
        RecordValue rec = assertInstanceOf(RecordValue.class, root("{ \"name\": Alice }").coreValue());
        assertEquals("name", rec.fields().get(0).name());
    }

    @Test
    void recordFieldOrderAndDuplicatesArePreservedNotDeduplicated() {
        // "last value wins" is a resolver-layer rule (§2.5); the structural parser keeps both.
        RecordValue rec = assertInstanceOf(RecordValue.class, root("{ x: 1 x: 2 }").coreValue());
        assertEquals(2, rec.fields().size());
        assertEquals("1", token(rec.fields().get(0).value().value()).text());
        assertEquals("2", token(rec.fields().get(1).value().value()).text());
    }

    @Test
    void nestedRecord() {
        RecordValue outer = assertInstanceOf(RecordValue.class,
                root("{ customer: { name: Alice } }").coreValue());
        RecordValue inner = assertInstanceOf(RecordValue.class,
                outer.fields().get(0).value().value().coreValue());
        assertEquals("Alice", token(inner.fields().get(0).value().value()).text());
    }

    @Test
    void recordFieldValueCanBeAbsent() {
        RecordValue rec = assertInstanceOf(RecordValue.class, root("{ x: _ }").coreValue());
        assertInstanceOf(AbsentValue.class, rec.fields().get(0).value().value().coreValue());
    }

    @Test
    void trailingCommaInRecordIsParseError() {
        assertThrows(ParseException.class, () -> parse("{ x: 1, }"));
    }

    @Test
    void zeroWidthSeparationBetweenFieldsIsParseError() {
        // "1" (unquoted) directly followed by "y" (unquoted) would just merge into one token, so
        // use adjacent quoted/brace values, which the lexer keeps as distinct tokens.
        assertThrows(ParseException.class, () -> parse("{ a: \"x\"b: \"y\" }"));
    }

    @Test
    void annotatedValueAsAttemptedFieldNameIsParseError() {
        assertThrows(ParseException.class, () -> parse("{ @deprecated x: 1 }"));
    }

    @Test
    void typedValueAsAttemptedFieldNameIsParseError() {
        assertThrows(ParseException.class, () -> parse("{ !string x: 1 }"));
    }

    // ── Maps ─────────────────────────────────────────────────────────────

    @Test
    void mapWithOneEntry() {
        MapValue map = assertInstanceOf(MapValue.class, root("{ WELCOME10 => \"10%\" }").coreValue());
        assertEquals(1, map.entries().size());
        assertEquals("WELCOME10", token(map.entries().get(0).key()).text());
        assertEquals("10%", token(map.entries().get(0).value().value()).text());
    }

    @Test
    void mapWithMultipleEntries() {
        MapValue map = assertInstanceOf(MapValue.class,
                root("{ WELCOME10 => \"10%\" loyalty => _ }").coreValue());
        assertEquals(2, map.entries().size());
        assertInstanceOf(AbsentValue.class, map.entries().get(1).value().value().coreValue());
    }

    @Test
    void mapKeyCanBeNonTokenValue() {
        MapValue map = assertInstanceOf(MapValue.class, root("{ { a: 1 } => \"x\" }").coreValue());
        assertInstanceOf(RecordValue.class, map.entries().get(0).key().coreValue());
    }

    @Test
    void mapKeyCanCarryAnnotationsAndTypeRef() {
        MapValue map = assertInstanceOf(MapValue.class, root("{ @deprecated !string key => 1 }").coreValue());
        DataValue key = map.entries().get(0).key();
        assertEquals(1, key.annotations().size());
        assertEquals("string", key.typeRef().orElseThrow());
    }

    @Test
    void absentAsMapKeyParsesStructurally() {
        // The spec forbids this, but as a resolver-layer rule, not a grammar one (§2.9). The
        // structural parser must accept it.
        MapValue map = assertInstanceOf(MapValue.class, root("{ _ => 1 }").coreValue());
        assertInstanceOf(AbsentValue.class, map.entries().get(0).key().coreValue());
    }

    @Test
    void trailingCommaInMapIsParseError() {
        assertThrows(ParseException.class, () -> parse("{ a => 1, }"));
    }

    // ── Arrays ───────────────────────────────────────────────────────────

    @Test
    void emptyArray() {
        ArrayValue arr = assertInstanceOf(ArrayValue.class, root("[]").coreValue());
        assertTrue(arr.elements().isEmpty());
    }

    @Test
    void emptyArrayWithWhitespace() {
        ArrayValue arr = assertInstanceOf(ArrayValue.class, root("[   ]").coreValue());
        assertTrue(arr.elements().isEmpty());
    }

    @Test
    void arrayWithElements() {
        ArrayValue arr = assertInstanceOf(ArrayValue.class, root("[1 2 3]").coreValue());
        assertEquals(3, arr.elements().size());
        assertEquals("1", token(arr.elements().get(0).value()).text());
    }

    @Test
    void arrayCommaSeparated() {
        ArrayValue arr = assertInstanceOf(ArrayValue.class, root("[1, 2, 3]").coreValue());
        assertEquals(3, arr.elements().size());
    }

    @Test
    void absentOccupiesPositionalArraySlot() {
        // [1 _ 3] has three elements (§2.9).
        ArrayValue arr = assertInstanceOf(ArrayValue.class, root("[1 _ 3]").coreValue());
        assertEquals(3, arr.elements().size());
        assertInstanceOf(AbsentValue.class, arr.elements().get(1).value().coreValue());
    }

    @Test
    void nestedArraysAndRecords() {
        ArrayValue arr = assertInstanceOf(ArrayValue.class,
                root("[ { sku: A-100 } { sku: B-205 } ]").coreValue());
        assertEquals(2, arr.elements().size());
        RecordValue first = assertInstanceOf(RecordValue.class, arr.elements().get(0).value().coreValue());
        assertEquals("A-100", token(first.fields().get(0).value().value()).text());
    }

    @Test
    void trailingCommaInArrayIsParseError() {
        assertThrows(ParseException.class, () -> parse("[1, 2, 3,]"));
    }

    @Test
    void unterminatedArrayIsParseErrorNotHang() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> assertThrows(ParseException.class, () -> parse("[1, 2, 3")));
    }

    @Test
    void unterminatedRecordIsParseErrorNotHang() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> assertThrows(ParseException.class, () -> parse("{ x: 1")));
    }

    @Test
    void unterminatedNestedStructureIsParseErrorNotHang() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> assertThrows(ParseException.class, () -> parse("{ x: [1 2")));
    }

    @Test
    void zeroWidthSeparationInArrayIsParseError() {
        assertThrows(ParseException.class, () -> parse("[{a:1}{b:2}]"));
    }

    @Test
    void arrayElementCanHaveOwnSchemaDirective() {
        ArrayValue arr = assertInstanceOf(ArrayValue.class,
                root("[ !!schema:\"https://example.com/s.tn1\" 1 2 ]").coreValue());
        assertEquals("https://example.com/s.tn1", arr.elements().get(0).schemaRef().orElseThrow());
        assertTrue(arr.elements().get(1).schemaRef().isEmpty());
    }

    // ── Type annotations (§3.2) ──────────────────────────────────────────

    @Test
    void typeAnnotationOnRoot() {
        DataValue v = root("!uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09");
        assertEquals("uuid", v.typeRef().orElseThrow());
        assertEquals("9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09", token(v).text());
    }

    @Test
    void typeAnnotationDirectlyBeforeBraceNoSpaceNeeded() {
        DataValue v = root("!person{name:Alice}");
        assertEquals("person", v.typeRef().orElseThrow());
        assertInstanceOf(RecordValue.class, v.coreValue());
    }

    @Test
    void typeAnnotationWithSpaceBeforeBraceAlsoValid() {
        DataValue v = root("!person { name: Alice }");
        assertEquals("person", v.typeRef().orElseThrow());
    }

    @Test
    void typeAnnotationMissingSpaceBeforeQuotedTokenIsParseError() {
        assertThrows(ParseException.class, () -> parse("!int32\"5\""));
    }

    @Test
    void typeAnnotationWithSpaceBeforeQuotedTokenIsValid() {
        DataValue v = root("!int32 \"5\"");
        assertEquals("int32", v.typeRef().orElseThrow());
        assertEquals("5", token(v).text());
    }

    @Test
    void typeAnnotationAppliesToWholeValueNotContents() {
        // !person { name: Alice } tags the record, not the field.
        DataValue v = root("!person { name: Alice }");
        RecordValue rec = assertInstanceOf(RecordValue.class, v.coreValue());
        assertTrue(rec.fields().get(0).value().value().typeRef().isEmpty());
    }

    @Test
    void bangNotAdjacentToTypeNameIsParseError() {
        assertThrows(ParseException.class, () -> parse("! person Alice"));
    }

    // ── Annotations (§3.1) ───────────────────────────────────────────────

    @Test
    void valuelessAnnotation() {
        DataValue v = root("@deprecated GOLD");
        assertEquals(1, v.annotations().size());
        assertEquals("deprecated", v.annotations().get(0).name());
        assertTrue(v.annotations().get(0).value().isEmpty());
        assertEquals("GOLD", token(v).text());
    }

    @Test
    void annotationWithValue() {
        DataValue v = root("@expires:\"2026-12-31\" GOLD");
        assertEquals(1, v.annotations().size());
        DataValue annValue = v.annotations().get(0).value().orElseThrow();
        assertEquals("2026-12-31", token(annValue).text());
    }

    @Test
    void multipleAnnotationsPreserveOrder() {
        DataValue v = root("@a @b value");
        assertEquals(2, v.annotations().size());
        assertEquals("a", v.annotations().get(0).name());
        assertEquals("b", v.annotations().get(1).name());
    }

    @Test
    void nestedAnnotationValueScopeSpecExample() {
        // Spec §3.1: "In `@a:@b:val target`, `@a`'s value is the data-value `@b:val target`: the
        // core value `target`, annotated by `@b`, whose own value is `val`." Traced by hand
        // (see SPEC-FEEDBACK.md #3): `@a:@b:val target` alone can't be a complete data-value --
        // it needs one more trailing token for the outermost core-value. This is that smallest
        // valid extension.
        DataValue v = root("@a:@b:val target extra");
        assertEquals(1, v.annotations().size());
        assertEquals("a", v.annotations().get(0).name());
        DataValue aValue = v.annotations().get(0).value().orElseThrow();
        assertEquals(1, aValue.annotations().size());
        assertEquals("b", aValue.annotations().get(0).name());
        assertEquals("val", token(aValue.annotations().get(0).value().orElseThrow()).text());
        assertEquals("target", token(aValue).text());
        assertEquals("extra", token(v).text());
    }

    @Test
    void nestedAnnotationValueScopeAloneIsIncomplete() {
        // See SPEC-FEEDBACK.md #3: the spec's example as literally written has no core-value at
        // the outermost level once @a's nested value consumes everything.
        assertThrows(ParseException.class, () -> parse("@a:@b:val target"));
    }

    @Test
    void contrastValuelessNestedAnnotationSpecExample() {
        // Spec §3.1 contrast: "@b" here is valueless, so "@a"'s value is "@b val" and "target"
        // belongs to the surrounding context. Unlike the colon variant, this DOES stand alone.
        DataValue v = root("@a:@b val target");
        assertEquals(1, v.annotations().size());
        DataValue aValue = v.annotations().get(0).value().orElseThrow();
        assertEquals("b", aValue.annotations().get(0).name());
        assertTrue(aValue.annotations().get(0).value().isEmpty());
        assertEquals("val", token(aValue).text());
        assertEquals("target", token(v).text());
    }

    @Test
    void annotationCannotItselfBeAValueSpecExample() {
        // Spec §3.1's own error example.
        assertThrows(ParseException.class, () -> parse("{ x: @a:@b:val }"));
    }

    @Test
    void atNotAdjacentToAnnotationNameIsParseError() {
        assertThrows(ParseException.class, () -> parse("@ deprecated GOLD"));
    }

    @Test
    void colonNotAdjacentToAnnotationNameFallsThroughToValuelessThenFails() {
        // "@foo : bar" -- no adjacent ':', so @foo is valueless (whitespace satisfies the
        // trailing-whitespace rule), leaving a bare ':' token where a core-value is expected.
        assertThrows(ParseException.class, () -> parse("@foo : bar"));
    }

    @Test
    void valuelessAnnotationWithoutTrailingWhitespaceIsParseError() {
        // "@foo(" -- no value (':' not adjacent... there is none at all), and no whitespace
        // before the next token either (a reserved special token, but that's a separate error;
        // this specifically must fail on the missing-whitespace rule first).
        assertThrows(ParseException.class, () -> parse("{ x: @foo\"bar\" }"));
    }

    // ── !!schema on scoped values (§2.3, §3.3) ──────────────────────────

    @Test
    void schemaDirectiveOnFieldValue() {
        RecordValue rec = assertInstanceOf(RecordValue.class, root("""
                { database: !!schema:"https://example.com/db-config.tn1" !db_config { host: db1 } }
                """).coreValue());
        ScopedValue fieldValue = rec.fields().get(0).value();
        assertEquals("https://example.com/db-config.tn1", fieldValue.schemaRef().orElseThrow());
        assertEquals("db_config", fieldValue.value().typeRef().orElseThrow());
    }

    @Test
    void schemaDirectiveOnMapEntryValue() {
        MapValue map = assertInstanceOf(MapValue.class,
                root("{ k => !!schema:\"https://example.com/s.tn1\" 1 }").coreValue());
        assertEquals("https://example.com/s.tn1", map.entries().get(0).value().schemaRef().orElseThrow());
    }

    @Test
    void otherDirectiveNamesInScopedValuePositionAreParseErrors() {
        assertThrows(ParseException.class, () -> parse("{ x: !!id:\"https://example.com/x.tn1\" 1 }"));
    }

    @Test
    void directivesNotPermittedBeforeMapKey() {
        assertThrows(ParseException.class, () -> parse("{ !!schema:\"https://example.com/s.tn1\" k => 1 }"));
    }

    @Test
    void directivesNotPermittedBeforeFieldName() {
        assertThrows(ParseException.class, () -> parse("{ !!schema:\"https://example.com/s.tn1\" x: 1 }"));
    }

    // ── Full example document (adapted from spec §2.1) ──────────────────

    @Test
    void fullOrderDocumentFromSpec() {
        String doc = """
                !!id:"https://example.com/orders/1042.tn1"
                !!schema:"https://example.com/order.tn1"
                @doc:"Order record exported 2026-07-03"
                !order {
                  order_id:  1042
                  reference: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09
                  customer: {
                    name:  "Ada Lovelace"
                    email: "ada@example.com"
                    tier:  @deprecated GOLD
                  }
                  placed:  !date 2026-07-01
                  total:   !number 199.90
                  flags:   0b0110
                  items: [
                    { sku: A-100 qty: 2 price: 49.95 discount: .5 }
                    { sku: B-205 qty: 1 price: 100.00 discount: _ }
                  ]
                  discounts: { @expires:"2026-12-31" WELCOME10 => "10%" loyalty => _ }
                  shipping: !!schema:"https://example.com/address.tn1" !address {
                    street: "12 Byron Rd"
                    city:   London
                  }
                  notes: \"""
                    Leave the parcel with the concierge.
                    Gift wrap — no prices on the slip.
                    \"""
                }
                """;
        Document parsed = parse(doc);

        assertEquals("https://example.com/orders/1042.tn1", parsed.id().orElseThrow());
        assertEquals("https://example.com/order.tn1", parsed.schema().orElseThrow());

        DataValue root = parsed.root();
        assertEquals(1, root.annotations().size());
        assertEquals("doc", root.annotations().get(0).name());
        assertEquals("order", root.typeRef().orElseThrow());

        RecordValue order = assertInstanceOf(RecordValue.class, root.coreValue());
        assertEquals(10, order.fields().size());

        assertEquals("order_id", order.fields().get(0).name());
        assertEquals("1042", token(order.fields().get(0).value().value()).text());

        assertEquals("reference", order.fields().get(1).name());
        DataValue reference = order.fields().get(1).value().value();
        assertEquals("uuid", reference.typeRef().orElseThrow());

        RecordValue customer = assertInstanceOf(RecordValue.class,
                order.fields().get(2).value().value().coreValue());
        assertEquals(3, customer.fields().size());
        DataValue tier = customer.fields().get(2).value().value();
        assertEquals("deprecated", tier.annotations().get(0).name());
        assertEquals("GOLD", token(tier).text());

        ArrayValue items = assertInstanceOf(ArrayValue.class,
                order.fields().get(6).value().value().coreValue());
        assertEquals(2, items.elements().size());
        RecordValue item0 = assertInstanceOf(RecordValue.class, items.elements().get(0).value().coreValue());
        assertEquals(".5", token(item0.fields().get(3).value().value()).text());
        RecordValue item1 = assertInstanceOf(RecordValue.class, items.elements().get(1).value().coreValue());
        assertInstanceOf(AbsentValue.class, item1.fields().get(3).value().value().coreValue());

        MapValue discounts = assertInstanceOf(MapValue.class,
                order.fields().get(7).value().value().coreValue());
        assertEquals(2, discounts.entries().size());
        assertEquals("expires", discounts.entries().get(0).key().annotations().get(0).name());

        ScopedValue shipping = order.fields().get(8).value();
        assertEquals("https://example.com/address.tn1", shipping.schemaRef().orElseThrow());
        assertEquals("address", shipping.value().typeRef().orElseThrow());
        RecordValue shippingRecord = assertInstanceOf(RecordValue.class, shipping.value().coreValue());
        assertEquals("street", shippingRecord.fields().get(0).name());

        assertEquals("notes", order.fields().get(9).name());
        DataValue notes = order.fields().get(9).value().value();
        TokenValue notesToken = assertInstanceOf(TokenValue.class, notes.coreValue());
        assertEquals(TokenForm.MULTI_LINE_QUOTED, notesToken.form());
        assertEquals("Leave the parcel with the concierge.\nGift wrap — no prices on the slip.",
                notesToken.text());
    }
}
