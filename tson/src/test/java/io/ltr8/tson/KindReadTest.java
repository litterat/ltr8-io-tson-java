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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void aMemberNameReadsAsThatName() {
        assertEquals(Optional.of("stock_id"), read("!ledger { named: stock_id }").get("named").asString());
        assertEquals(Optional.of("customer_id"),
                read("!ledger { named: \"customer_id\" }").get("named").asString());
    }

    @Test
    void aMemberTheSchemaImportsIsAMember() {
        assertEquals(List.of(), problems("!ledger { named: uuid }"));
    }

    @Test
    void aDefaultAtAKindTypedFieldIsAMemberName() {
        assertEquals(Optional.of("customer_id"), read("!ledger { named: stock_id }").get("fallback").asString());
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
