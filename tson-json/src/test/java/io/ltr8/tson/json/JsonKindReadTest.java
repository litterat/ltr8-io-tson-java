package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.stream.JsonStream;
import io.ltr8.tson.json.tree.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A kind-typed position in JSON (SPEC-FEEDBACK.md #22, [TSON-JSON] §5): a kind is an atom whose values are type
 * names, so its JSON form is a string naming a member of the kind in the governing schema's closure, read as the
 * same token in TSON text is. It is never a discrimination case: {@code kind<uuid_type>} takes {@code "stock_id"}
 * and refuses a uuid.
 */
class JsonKindReadTest {

    private static final String ID = "https://example.test/json-kinds.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-kinds.tn"
            !!meta:"https://tson.io/2026/38/m/meta.tn"
            !!import:"https://tson.io/2026/38/m/core.tn"
            {
              stock_id    => !uuid_type {}
              customer_id => !uuid_type {}
              sku         => text
              ledger      => { named: kind<uuid_type> }
            }
            """;

    private static final JsonCompiledSchema COMPILED = JsonSchemaCompiler.compile(Tson.standard().resolve(SCHEMA));

    private record Read(JsonValue value, List<Diagnostic> problems) {

        JsonValue accepted() {
            assertEquals(List.of(), problems.stream().map(Diagnostic::message).toList(), "expected a clean read");
            return value;
        }

        Diagnostic refusal() {
            assertEquals(1, problems.size(), () -> "expected exactly one problem, got " + problems);
            return problems.getFirst();
        }
    }

    private static Read read(String json) {
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(json)) {
            JsonReadContext ctx = JsonReadContext.of(new JsonStream(bytes, ProcessorPolicy.defaults(), receiver),
                    receiver);
            ctx = COMPILED.rootDeclaration("ledger").map(ctx::underDeclaration).orElse(ctx);
            return new Read((JsonValue) COMPILED.get("ledger").read(ctx), List.copyOf(problems));
        }
    }

    @Test
    void aStringNamingAMemberIsAdmitted() {
        read("""
                {"named": "stock_id"}""").accepted();
        read("""
                {"named": "uuid"}""").accepted();
    }

    @Test
    void theNameOfATypeBuiltWithAnotherConstructorIsRefused() {
        Diagnostic refusal = read("""
                {"named": "sku"}""").refusal();

        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, refusal.code());
        assertEquals("/named", refusal.path().orElseThrow());
        assertTrue(refusal.message().contains("is not a member of this kind"), refusal.message());
    }

    @Test
    void aValueOfAMemberIsNoName() {
        assertEquals(Diagnostic.Code.ATOM_FORM_INVALID, read("""
                {"named": "7d3e5f8a-1b2c-4d5e-8f90-a1b2c3d4e5f6"}""").refusal().code());
    }

    /** A kind is an atom read from a string, so a tagged object is the wrong JSON kind, not a member. */
    @Test
    void aTaggedValueIsNotAKindsForm() {
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, read("""
                {"named": {"$type": "stock_id", "$value": "7d3e5f8a-1b2c-4d5e-8f90-a1b2c3d4e5f6"}}""")
                .refusal().code());
    }

    public record Ledger(String named) {
    }

    @Test
    void bindModeReadsTheNameIntoText() {
        Tson tson = Tson.standard();
        tson.resolve(SCHEMA);
        DataBindContext binding = DataBindContext.builder().nameBinder(DataNameBinder.ofMap(Map.of("ledger",
                Ledger.class))).registerAtoms(AtomContext.hostTypes()).build();
        Json json = Json.of(ProcessorConfig.defaults().withDataBindContext(binding)).withSchemas(tson.schemaRegistry());

        Ledger ledger = json.objectReader().withSchema(ID).readAs("""
                {"named": "customer_id"}""", "ledger", Ledger.class);

        assertEquals(new Ledger("customer_id"), ledger);
    }
}
