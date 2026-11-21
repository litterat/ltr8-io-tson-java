package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.config.ResolverBindContext;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.RefBody;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A constructor composed over another with no field added ([TSON-SCHEMA] §5.8) in the JSON encoding: a type it
 * builds reads with its pin in force, and a value of the constructor itself binds as its parent's body does.
 */
class JsonComposedConstructorTest {

    private static final String ID = "https://example.test/json-composed.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/json-composed.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              my_list    => meta.array & { min_items?: = 1 }
              names      => my_list<text>
              record_ref => meta.ref & { of?: = meta.record }
            }
            """;

    private static Tson tson() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaModelNameBinder.defaultContext()));
        assertEquals(List.of(), tson.validateSchema(SCHEMA));
        return tson;
    }

    @Test
    void aTypeTheConstructorBuiltReadsWithItsPin() {
        Json json = Json.standard().withSchemas(tson().schemaRegistry());
        assertEquals(List.of(), json.validate("[\"a\"]", ID, "names"));
        List<Diagnostic> problems = json.validate("[]", ID, "names");
        assertEquals(1, problems.size(), problems::toString);

        assertEquals(List.of("a"), json.objectReader().withSchema(ID).readAs("[\"a\"]", "names", List.class));
        assertThrows(ReadException.class, () -> json.treeReader().withSchema(ID).readAs("[]", "names"));
        assertThrows(ReadException.class, () -> json.objectReader().withSchema(ID).readAs("[]", "names", List.class));
    }

    /** Only {@code meta.ref} is bound, so a value of {@code record_ref} binds through its parent, its pin injected. */
    @Test
    void aValueOfTheConstructorBindsAsItsParentsBody() {
        DataBindContext binding = ResolverBindContext.registerDefaults(DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.of("meta.ref", RefBody.class)))).build();
        Json json = Json.of(ProcessorConfig.defaults().withDataBindContext(binding))
                .withSchemas(tson().schemaRegistry());

        RefBody read = assertInstanceOf(RefBody.class,
                json.objectReader().withSchema(ID).readAs("{}", "record_ref", Object.class));
        assertEquals(TypeRef.of("meta.record"), read.of());
        assertThrows(ReadException.class, () -> json.objectReader().withSchema(ID)
                .readAs("{\"of\": \"meta.type\"}", "record_ref", Object.class));
    }
}
