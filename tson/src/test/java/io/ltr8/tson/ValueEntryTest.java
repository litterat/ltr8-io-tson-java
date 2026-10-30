package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.tree.TsonRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An entry whose tag names an ordinary type is a value of it ([TSON-SCHEMA] §4.1): {@code create_order =>
 * !operation { ... }}, {@code operation} a record type an ordinary library declares. It is no type and no kind
 * holds it; it binds through the one context schemas and data share, and where that context binds no class for its
 * type it is held as a tree, so the schema still loads and validates, and is refused where it is obtained to bind.
 */
class ValueEntryTest {

    private static final String LIB = "https://example.test/ops.tn";
    private static final String API = "https://example.test/ops-api.tn";

    private static final Map<String, String> DOCUMENTS = Map.of(LIB, """
            !!id:"https://example.test/ops.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              operation => { path: text  request: kind<record>  response: type_kind }
            }
            """, API, """
            !!id:"https://example.test/ops-api.tn"
            !!import:"https://example.test/ops.tn"
            !!import:"https://tson.io/2026/39/m/core.tn"
            !schema {
              order        => { sku: text }
              create_order => !operation { path: "/orders"  request: order  response: order }
            }
            """);

    /** The consumer's class for {@code operation}, bound by name through the configured context. */
    public record Op(String path, TypeRef request, TypeRef response) {
    }

    private static Tson tson(DataBindContext context) {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(context)
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(DOCUMENTS))));
    }

    private static Tson bound() {
        return tson(DataBindContext.builder().nameBinder(DataNameBinder.ofMap(Map.of("operation", Op.class))).build());
    }

    @Test
    void aValueOfAnImportedRecordTypeIsAValueEntryBoundToTheConsumersClass() {
        TsonLinkedSchema linked = bound().bindRegistry().core().resolveLinked(API);

        ValueEntry entry = assertInstanceOf(ValueEntry.class, linked.schema().entries().get("create_order"));
        assertEquals("operation", entry.type());
        assertEquals(new Op("/orders", TypeRef.of("order"), TypeRef.of("order")), entry.value());
        assertEquals(EntryKind.VALUE, linked.kindOf("create_order"));
        assertEquals(Optional.of("operation"), linked.schema().constructorOf("create_order"));
    }

    /** One context: the class that binds the schema's value entry binds a data document's value of the same type. */
    @Test
    void oneBinderServesTheSchemaAndItsData() {
        Tson tson = bound();
        assertNotNull(tson.bindRegistry().get(API));

        Object read = tson.objectReader().read("""
                !!import:"https://example.test/ops.tn"
                !operation { path: "/x"  request: operation  response: text }
                """, Object.class);

        assertEquals(new Op("/x", TypeRef.of("operation"), TypeRef.of("text")), read);
    }

    /**
     * No class binds {@code operation}: the value is held as a tree, so the schema loads and validates, and a tree read
     * under it works; obtained to bind, it is refused at the entry.
     */
    @Test
    void anUnboundValueEntryIsHeldAsATreeAndRefusedWhereTheSchemaIsBound() {
        Tson tson = tson(DataBindContext.builder().build());
        assertEquals(List.of(), tson.validateSchema(DOCUMENTS.get(API)));

        ValueEntry entry = assertInstanceOf(ValueEntry.class,
                tson.bindRegistry().core().resolveLinked(API).schema().entries().get("create_order"));
        TsonRecord tree = assertInstanceOf(TsonRecord.class, entry.value());
        assertEquals("/orders", tree.get("path").asString().orElseThrow());
        assertNotNull(tson.treeRegistry().get(API));

        MissingBindingException thrown = assertThrows(MissingBindingException.class,
                () -> tson.bindRegistry().get(API));
        assertTrue(thrown.getMessage().contains("'create_order' is a value of the type 'operation'"),
                thrown.getMessage());

        List<Diagnostic> problems = new ArrayList<>();
        tson.bindRegistry().get(API, problems::add);
        assertEquals(1, problems.size(), problems::toString);
        assertEquals(Diagnostic.Code.BIND_MISMATCH, problems.getFirst().code());
        assertEquals("/create_order", problems.getFirst().schemaPointer().orElseThrow());
    }

    /** A tree-held value's names are judged at link as a bound one's are: a kind slot naming no record is refused. */
    @Test
    void aTreeHeldValueIsJudgedByItsSlots() {
        Tson tson = tson(DataBindContext.builder().build());
        List<Diagnostic> problems = tson.validateSchema(DOCUMENTS.get(API).replace("request: order", "request: text"));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("'create_order' at /request"), problems::toString);
    }
}
