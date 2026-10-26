package io.ltr8.tson.compiler;

import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.DiagnosticsCollector;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TemplateBody;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.10's two kinds of template: a record template is a {@code record} with {@code parameters}, the
 * family base its declared applications are IS-A, and every other template is a held body. What is pinned here is
 * where the open record must not be mistaken for a closed one.
 */
class RecordTemplateIsARecordTest {

    private static final String ID = "https://example.test/record-template-is-a-record.tn";

    private static TsonCompiledSchema compile(String declarations) {
        String schema = """
                !!id:"https://example.test/record-template-is-a-record.tn"
                !!meta:"https://tson.io/2026/38/m/meta.tn"
                !!import:"https://tson.io/2026/38/m/core.tn"
                {
                %s
                }
                """.formatted(declarations);
        SchemaSource source = uri -> {
            if (CanonicalIdentity.sameIdentity(uri, ID)) {
                return schema;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.tree(core).get(ID);
    }

    private static List<Diagnostic> read(TsonCompiledSchema compiled, String rootType, String document) {
        DiagnosticsCollector problems = DiagnosticsReceiver.collecting();
        compiled.get(rootType).read(TestDocuments.document(document, problems));
        return List.copyOf(problems.diagnostics());
    }

    /** The record body is the record itself; a container body is held text. */
    @Test
    void aRecordTemplateIsARecordAndAContainerTemplateIsHeld() {
        TsonCompiledSchema compiled = compile("""
                  box   => <T> { v: T }
                  boxes => <T> [T]
                """);

        RecordBody box = assertInstanceOf(RecordBody.class, compiled.schema().entries().get("box"));
        assertEquals(List.of("T"), box.parameterNames());
        assertTrue(OpenEntry.isFamilyBase(box));
        assertInstanceOf(TemplateBody.class, compiled.schema().entries().get("boxes"));
    }

    /** An operand is flattened into the declaration, so a template there needs its arguments. */
    @Test
    void aTemplateNamedWithoutArgumentsIsNoOperand() {
        String composed = assertThrows(SchemaValidationException.class, () -> compile("""
                  box => <T> { v: T }
                  bad => box & { a: text }
                """)).getMessage();
        String refined = assertThrows(SchemaValidationException.class, () -> compile("""
                  box => <T> { v: T }
                  bad => box ^ { }
                """)).getMessage();

        assertTrue(composed.contains("supertype 'box' is a template and is applied to no arguments"), composed);
        assertTrue(refined.contains("refinement source 'box' is a template and is applied to no arguments"),
                refined);
    }

    /**
     * A record template that composes a member-dispatched base is IS-A it, and may pin the selector itself; it is
     * still no member. Only its declared application is, so a value pinned {@code "dog"} reads as {@code dogs}.
     */
    @Test
    void anOpenSubtypeIsNoMemberOfTheFamilyItComposes() {
        TsonCompiledSchema compiled = compile("""
                  pet    => abstract { type: text =?  name: text }
                  dog_of => <T> pet & { type: text = "dog"  tricks: T }
                  dogs   => dog_of<text>
                  owner  => { pet: pet }
                """);

        assertEquals(List.of(), read(compiled, "owner",
                "{ pet: { type: \"dog\"  name: \"rex\"  tricks: \"sit\" } }"));
    }
}
