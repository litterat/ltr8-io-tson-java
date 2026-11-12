package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.tree.TsonValue;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.net.UuidType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value at a position typed by a reference that names types ([TSON-SCHEMA] §4.2), read in TSON text: its values
 * are the names of the types built with its constructor, so a value is a type name ([TSON-DATA] §7.7) that resolves, in
 * the governing schema's type-name namespace, to a member. {@code meta.ref<uuid_type>} holds {@code stock_id}, never a
 * uuid.
 */
class TypeNameRefReadTest {

    private static final String IDS = "https://example.test/kind-ids.tn";

    private static final String IDS_SCHEMA = """
            !!id:"https://example.test/kind-ids.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              stock_id    => !net.uuid_type {}
              customer_id => !net.uuid_type {}
              sku         => text
              ledger      => { named: meta.ref<net.uuid_type>  fallback?: meta.ref<net.uuid_type> ~ customer_id }
              box         => <T> { v: T }
              pair        => <A, B> { a: A  b: B }
              inner       => box<text>
              outer       => pair<inner int32>
              typed       => { t: meta.type_ref }
              label       => text
              caption     => label
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
     * A declared application is named by its entry: {@code outer => pair<inner int32>} is a type, and data
     * names it {@code outer}, which finds that entry.
     */
    @Test
    void aDeclaredApplicationIsNamedByItsEntry() {
        TypeRef read = reference(read("!typed { t: outer }").get("t"));
        assertEquals("outer", read.text());
        Top outer = tson().bindRegistry().core().resolveLinked(IDS).schema().entries().get("outer");
        assertEquals(outer, read.referent().orElseThrow());
    }

    /**
     * In a data document a reference is a name ([TSON-SCHEMA] §4.2): an argument list is no data grammar, so an
     * application does not parse whether the schema closes it ({@code box<text>}, declared as {@code inner}; {@code
     * pair<inner int32>}, as {@code outer}; {@code box<caption>}, {@code box<text>} past an alias) or not ({@code
     * box<int32>}): a syntax error at the document, under the base-syntax code ([TSON-DATA] §7.2.5). The token before
     * the {@code <} is read as the position reads any token, as {@code kind} in {@code kind!} is.
     */
    @Test
    void anApplicationDoesNotParseWhetherOrNotTheSchemaClosesIt() {
        for (String application : List.of("box<text>", "pair< inner, int32 >", "box<caption>", "box<int32>")) {
            List<Diagnostic> problems = problems("!typed { t: " + application + " }");
            assertTrue(problems.stream().anyMatch(problem -> problem.code() == Diagnostic.Code.VALIDATION_ERROR
                    && problem.path().equals(Optional.of(""))), application + ": " + problems);
        }
    }

    /**
     * A refusal lists the kind's members as an author writes them, by name: {@code meta.ref<net.uuid_type>} is written
     * in place at {@code ledger}'s fields and is no entry, and data cannot write the application, so the list holds
     * no form of it.
     */
    @Test
    void aRefusalListsOnlyTheNamesAnAuthorWrites() {
        List<Diagnostic> problems = problems("!typed { t: nope }");
        assertEquals(1, problems.size(), problems.toString());
        String message = problems.getFirst().message();
        assertTrue(message.contains("inner"), message);
        assertFalse(message.contains("meta.ref<net.uuid_type>"), message);
        assertFalse(message.contains("reference_uuid_type"), message);
        assertFalse(problems.getFirst().expected().contains("reference_uuid_type"), problems.getFirst().expected());
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

    public record Typed(TypeRef t) {
    }

    /**
     * Bind mode reads the slot as a tree read does: the bound reference names the declared entry, and an argument
     * list is refused, at the token before it or at the {@code <}, whichever a fail-fast read meets first.
     */
    @Test
    void bindModeNamesTheDeclaredEntryAndRefusesAnApplication() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SOURCE))
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("typed", Typed.class))
                                .orElse(SchemaModelNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));

        Typed typed = tson.objectReader().read("!!import:\"" + IDS + "\"\n!typed { t: inner }", Typed.class);

        assertEquals(tson.bindRegistry().core().resolveLinked(IDS).schema().entries().get("inner"),
                typed.t().referent().orElseThrow());
        assertThrows(ReadException.class, () -> tson.objectReader()
                .read("!!import:\"" + IDS + "\"\n!typed { t: box<text> }", Typed.class));
    }

    @Test
    void bindModeReadsTheNameIntoText() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SOURCE))
                .withDataBindContext(DataBindContext.builder()
                        .nameBinder(DataNameBinder.ofMap(Map.of("ledger", Ledger.class))
                                .orElse(SchemaModelNameBinder.INSTANCE))
                        .registerAtoms(AtomContext.hostTypes()).build()));

        Ledger ledger = tson.objectReader().read("!!import:\"" + IDS + "\"\n!ledger { named: stock_id }",
                Ledger.class);

        assertEquals(new Ledger("stock_id", "customer_id"), ledger);
    }
}
