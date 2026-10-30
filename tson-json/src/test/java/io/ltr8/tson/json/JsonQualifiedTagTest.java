package io.ltr8.tson.json;

import io.ltr8.tson.Tson;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.io.ByteSource;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.json.stream.JsonStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * [TSON-JSON] §3.2: {@code $type} holds a type's name as TSON text's {@code !name} does, a qualified name included --
 * {@code "$type": "geo.address"} names the member {@code address} of the schema map nested as {@code geo}, resolved
 * from the root, and one naming no member is refused as an unresolvable tag is, in both encodings alike.
 */
class JsonQualifiedTagTest {

    private static final String SCHEMA = """
            !!id:"https://example.test/geo-json.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              geo => !schema {
                address => { street: text }
                point   => { x: int32  y: int32 }
              }
              holder => { at: (geo.address | geo.point)  any: declared }
            }""";

    private static Tson processor() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of((SchemaSource) uri -> SCHEMA)));
    }

    private static List<Diagnostic> fromJson(String body) {
        JsonCompiledSchema compiled = JsonSchemaCompiler.compile(processor().resolve(SCHEMA));
        List<Diagnostic> problems = new ArrayList<>();
        DiagnosticsReceiver receiver = problems::add;
        try (ByteSource bytes = ByteSource.of(body)) {
            JsonReadContext context = JsonReadContext.of(
                    new JsonStream(bytes, ProcessorPolicy.defaults(), receiver), receiver);
            context = compiled.rootDeclaration("holder").map(context::underDeclaration).orElse(context);
            compiled.get("holder").read(context);
        }
        return List.copyOf(problems);
    }

    private static List<Diagnostic> fromTson(String body) {
        Tson tson = processor();
        tson.resolve(SCHEMA);
        return tson.validate("!!import:\"https://example.test/geo-json.tn\"\n" + body);
    }

    @Test
    void aQualifiedTypeSelectsANestedMembersVariantAndDeclaredType() {
        assertEquals(List.of(), fromJson("""
                {"at": {"$type": "geo.point", "x": 1, "y": 2},
                 "any": {"$type": "geo.address", "street": "Main"}}"""));
        assertEquals(List.of(), fromTson("!holder { at: !geo.point { x: 1  y: 2 }  any: !geo.address { street: Main } }"));
    }

    @Test
    void aQualifiedTypeNamingNoMemberIsRefusedInBoth() {
        List<Diagnostic> json = fromJson("""
                {"at": {"$type": "geo.point", "x": 1, "y": 2},
                 "any": {"$type": "geo.region", "street": "Main"}}""");
        List<Diagnostic> tson = fromTson("!holder { at: !geo.point { x: 1  y: 2 }  any: !geo.region { street: Main } }");
        assertFalse(json.isEmpty(), "JSON took a $type naming no member of geo");
        assertFalse(tson.isEmpty(), "TSON took a tag naming no member of geo");
        assertEquals(tson.stream().map(Diagnostic::code).toList(), json.stream().map(Diagnostic::code).toList());
    }
}
