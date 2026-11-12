package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeInline;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Spike probe (Option C): a dependent type in place is equal text under two binders. */
class ScratchDependentIdentityTest {

    private static final String ID = "https://example.test/identity.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              point   => { x: int32  y: int32 }
              any     => { kind: meta.type_ref  v?: kind! }
              records => meta.ref<meta.record>
              rec     => { kind: records  v?: kind! }
              pair    => <T> { kind: meta.type_ref  v?: kind!  w: T }
              holder  => { a: pair<text>  b: pair<text>  c: pair<int32> }
            }""".formatted(ID);

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(
                Map.of(ID, SCHEMA)))));
    }

    private static List<Diagnostic> validate(String document) {
        return tson().validate("!!import:\"" + ID + "\"\n" + document);
    }

    @Test
    void equalTextUnderTwoBindersReadsByEachBinder() {
        RecordBody any = (RecordBody) tson().resolve(SCHEMA).schema().entries().get("any");
        RecordBody rec = (RecordBody) tson().resolve(SCHEMA).schema().entries().get("rec");
        TypeInline anyV = (TypeInline) any.fields().get("v").type();
        TypeInline recV = (TypeInline) rec.fields().get("v").type();
        assertEquals(anyV, recV, "one value, structurally");
        assertNotEquals(any.fields().get("kind"), rec.fields().get("kind"));

        assertEquals(List.of(), validate("!any { kind: int32  v: 5 }"));
        // `rec`'s sibling admits record names only: int32 is refused there, at the sibling, whatever `any` read.
        assertEquals(Optional.of("/kind"), validate("!rec { kind: int32  v: 5 }").getFirst().path());
        assertEquals(List.of(), validate("!rec { kind: point  v: { x: 1  y: 2 } }"));
        assertEquals(List.of(), validate("!any { kind: point  v: { x: 1  y: 2 } }"));
    }

    @Test
    void aDependentFieldsValueInCanonicalFormIsRefusedByTheRead() {
        String canonical = SCHEMA.replace("  point   => { x: int32  y: int32 }", "  point   => { x: int32  y: int32 }\n"
                + "  canon => !meta.record { fields: { kind => !meta.record_field { type: meta.type_ref }  "
                + "v => !meta.record_field { type: { of: !meta.dependent { on: kind } }  optional: true  role: DEFAULT  "
                + "value: 5 } } }");
        List<Diagnostic> problems = Tson.standard().validateSchema(canonical);
        System.out.println("PROBE canonical dependent value: " + problems);
        assertEquals(1, problems.size());
    }

    @Test
    void aDataDocumentHoldingADependentTypeAtAnElementType() {
        List<Diagnostic> problems = Tson.standard().validate("!!import:\"https://tson.io/2026/40/m/meta.tn\"\n"
                + "!meta.array { element_type: { of: !meta.dependent { on: kind } } }");
        System.out.println("PROBE data element_type: " + problems);
    }

    @Test
    void recordsInPlaceHoldingADependentFieldShareByTheirWholeShape() {
        assertEquals(List.of(), validate("!holder { a: { kind: int32  v: 5  w: x }  b: { kind: text  v: y  w: z }  "
                + "c: { kind: point  v: { x: 1  y: 2 }  w: 3 } }"));
        assertEquals(Optional.of("/a/v"), validate("!holder { a: { kind: int32  v: nope  w: x }  "
                + "b: { kind: text  v: y  w: z }  c: { kind: int32  v: 1  w: 3 } }").getFirst().path());
    }
}
