package io.ltr8.tson.json;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.UuidType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * A constructor is an ordinary record type ([TSON-SCHEMA] §4.1, §4.2; SPEC-FEEDBACK.md #11): a name slot over {@code
 * T} admits a name exactly when the type of the named entry's value IS-A {@code T}, and a constructor whose
 * declaration is a record, {@code uuid_type => atom & { ... }}, holds a value of {@code record}. So it is a type a
 * field may be typed by, its instances being declarations read as data, and a name {@code kind<record>} admits.
 * Pinned in both encodings and both read modes.
 */
class ConstructorAsRecordTypeTest {

    private static final String ID = "https://example.test/constructor-as-type.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/constructor-as-type.tn"
            !!import:"https://tson.io/2026/39/m/meta.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              holder   => { spec: uuid_type }
              pick     => { t: kind<record> }
              tpick    => { t: type_kind }
              boxed    => <T> { v: T }
              box_spec => boxed<uuid_type>
              specs    => [uuid_type]
            }
            """;

    private static final String HEAD = "!!import:\"" + ID + "\"\n";

    private static Tson tson() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaMetaNameBinder.contextExtendedWith(
                new DataNameBinder.DefaultDataNameBinder(Set.of("io.ltr8.tson.json"),
                        Map.of("holder", "constructor_holder", "pick", "constructor_pick")))));
        assertEquals(List.of(), tson.validateSchema(SCHEMA), "a constructor is a type a field may be typed by");
        return tson;
    }

    @Test
    void aConstructorTypedFieldHoldsADeclarationInTheTree() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validate(HEAD + "!holder { spec: { version: 4 } }"));
        assertEquals(List.of(), tson.validate(HEAD + "!holder { spec: !uuid_type { version: 4 } }"));
        assertEquals(List.of(), tson.validate(HEAD + "!specs [{ version: 4 } {}]"));
        assertEquals(List.of(), tson.validate(HEAD + "!box_spec { v: { version: 4 } }"));

        List<Diagnostic> problems = tson.validate(HEAD + "!holder { spec: { nope: 4 } }");
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, problems.getFirst().code());
    }

    @Test
    void aConstructorTypedFieldBindsTheConstructorsModelClass() {
        ConstructorHolder held = assertInstanceOf(ConstructorHolder.class, tson().objectReader().withSchema(ID)
                .readAs("{ spec: { version: 4 } }", "holder", Object.class));

        assertEquals(Optional.of(4), held.spec().version());
        assertInstanceOf(UuidType.class, held.spec());
    }

    @Test
    void aKindOfRecordHoldsAConstructorsName() {
        Tson tson = tson();
        assertEquals(List.of(), tson.validate(HEAD + "!pick { t: uuid_type }"));
        assertEquals(List.of(), tson.validate(HEAD + "!pick { t: record }"));
        assertEquals(List.of(), tson.validate(HEAD + "!tpick { t: uuid_type }"));

        ConstructorPick pick = assertInstanceOf(ConstructorPick.class, tson.objectReader().withSchema(ID)
                .readAs("{ t: uuid_type }", "pick", Object.class));
        assertEquals(TypeRef.of("uuid_type"), pick.t());
    }

    @Test
    void theJsonEncodingReadsTheSame() {
        Tson tson = tson();
        Json json = Json.standard().withSchemas(tson.schemaRegistry());

        assertEquals(List.of(), json.validate("{\"spec\": {\"version\": 4}}", ID, "holder"));
        assertEquals(List.of(), json.validate("{\"t\": \"uuid_type\"}", ID, "pick"));
        List<Diagnostic> problems = json.validate("{\"spec\": {\"nope\": 4}}", ID, "holder");
        assertEquals(Diagnostic.Code.UNRECOGNIZED_FIELD, problems.getFirst().code(), problems::toString);
    }
}
