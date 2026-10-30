package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.UuidType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value at a kind position ([TSON-SCHEMA] §4.2), read in TSON text: a kind is an atom whose values are the
 * names of the types built with its constructor, so a value is a type name ([TSON-DATA] §7.7) that resolves, in
 * the governing schema's type-name namespace, to a member. {@code kind<uuid_type>} holds {@code stock_id}, never a
 * uuid.
 */
class KindReadTest {

    private static final String IDS = "https://example.test/kind-ids.tn";

    private static final String IDS_SCHEMA = """
            !!id:"https://example.test/kind-ids.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              stock_id    => !uuid_type {}
              customer_id => !uuid_type {}
              sku         => text
              ledger      => { named: kind<uuid_type>  fallback?: kind<uuid_type> ~ customer_id }
              box         => <T> { v: T }
              pair        => <A, B> { a: A  b: B }
              inner       => box<text>
              outer       => pair<box<text> int32>
              typed       => { t: type_kind }
            }""";

    private static final SchemaSource SOURCE = uri -> IDS_SCHEMA;

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SOURCE)));
    }

    private static TsonValue read(String data) {
        return tson().treeReader().withSchema(IDS).read("!!import:\"" + IDS + "\"\n" + data);
    }

    private static List<Diagnostic> problems(String data) {
        return tson().validate("!!import:\"" + IDS + "\"\n" + data);
    }

    /** What a reference-typed position reads to: the reference itself, its name, and a helper to its referent. */
    private static TypeRef reference(TsonValue node) {
        return node.as(TypeRef.class).orElseThrow(() -> new AssertionError("not a reference: " + node));
    }

    @Test
    void aMemberNameReadsAsThatName() {
        TypeRef named = reference(read("!ledger { named: stock_id }").get("named"));
        assertEquals("stock_id", named.text());
        assertEquals(List.of("stock_id"), named.segments());
        assertEquals(TypeRef.of("customer_id"), reference(read("!ledger { named: \"customer_id\" }").get("named")));
    }

    /** The reference names its referent in the closure it was read under: the entry's value. */
    @Test
    void aReferenceFindsTheEntryItNames() {
        Object referent = reference(read("!ledger { named: stock_id }").get("named")).referent().orElseThrow();
        assertInstanceOf(UuidType.class, referent);
    }

    /**
     * An application resolves level by level, by structural identity, to the entries the schema closed: the
     * inner {@code box<text>} to {@code inner}, then {@code pair<inner int32>} to {@code outer}. Whitespace is free.
     */
    @Test
    void aNestedApplicationResolvesBottomUpToTheEntryTheSchemaClosed() {
        TypeRef read = reference(read("!typed { t: pair< box<text>, int32 > }").get("t"));
        assertEquals("pair<box<text> int32>", read.text());
        Top outer = tson().bindRegistry().core().resolveLinked(IDS).schema().entries().get("outer");
        assertEquals(outer, read.referent().orElseThrow());
        TypeRef inner = ((TypeArgument.Ref) read.arguments().getFirst()).ref();
        assertEquals("box<text>", inner.text());
        assertEquals(tson().bindRegistry().core().resolveLinked(IDS).schema().entries().get("inner"),
                inner.referent().orElseThrow());
    }

    /**
     * A refusal lists the kind's members as an author can write them: the entry {@code kind<uuid_type>} closed to is
     * minted under a name nobody wrote, and the list names it by its application.
     */
    @Test
    void aRefusalNamesAMintedMemberByItsApplication() {
        List<Diagnostic> problems = problems("!typed { t: nope }");
        assertEquals(1, problems.size(), problems.toString());
        String message = problems.getFirst().message();
        assertTrue(message.contains("kind<uuid_type>"), message);
        assertFalse(message.contains("reference_uuid_type"), message);
        assertFalse(problems.getFirst().expected().contains("reference_uuid_type"), problems.getFirst().expected());
    }

    /** An application nothing in the closure closed names no type the schema closes, and is refused. */
    @Test
    void anApplicationNothingClosedIsRefused() {
        List<Diagnostic> problems = problems("!typed { t: box<int32> }");
        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("names no type this schema closes"),
                problems.getFirst().message());
    }

    @Test
    void aMemberTheSchemaImportsIsAMember() {
        assertEquals(List.of(), problems("!ledger { named: uuid }"));
    }

    @Test
    void aDefaultAtAKindTypedFieldIsAMemberName() {
        assertEquals(TypeRef.of("customer_id"), reference(read("!ledger { named: stock_id }").get("fallback")));
    }

    @Test
    void theNameOfATypeBuiltWithAnotherConstructorIsRefused() {
        List<Diagnostic> problems = problems("!ledger { named: sku }");

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
        assertTrue(problems.getFirst().message().contains("is not a member of this kind"),
                problems.getFirst().message());
        assertEquals(Optional.of("/named"), problems.getFirst().path());
    }

    @Test
    void aNameTheSchemaDoesNotDeclareIsRefused() {
        List<Diagnostic> problems = problems("!ledger { named: order_id }");

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, problems.getFirst().code());
    }

    /** A kind holds names: a uuid is a value of a member, and no type name. */
    @Test
    void aValueOfAMemberIsNoName() {
        List<Diagnostic> problems = problems("!ledger { named: \"7d3e5f8a-1b2c-4d5e-8f90-a1b2c3d4e5f6\" }");

        assertEquals(1, problems.size(), problems.toString());
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, problems.getFirst().code());
    }

    public record Ledger(String named, String fallback) {
    }

    @Test
    void bindModeReadsTheNameIntoText() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SOURCE))
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("ledger", Ledger.class))
                                .orElse(SchemaMetaNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));

        Ledger ledger = tson.objectReader().read("!!import:\"" + IDS + "\"\n!ledger { named: stock_id }",
                Ledger.class);

        assertEquals(new Ledger("stock_id", "customer_id"), ledger);
    }
}
