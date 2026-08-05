package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonObjectReader;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.tree.TsonNode;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Tson#treeReader()} / {@link Tson#objectReader()} -- the value-returning counterparts to {@link
 * Tson#validate}: a self-describing document read into a {@link TsonNode} tree (or a bound Java object),
 * schema-validated when it declares a {@code !!schema} and schemaless otherwise, fail-fast (a bad value or
 * a document-selection failure throws {@link TsonReadException}).
 */
class TsonReadTest {

    private static final String POINT_ID = "https://example.test/point-1.tn";
    private static final String POINT_SCHEMA = """
            !!id:"https://example.test/point-1.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            { point => { x: int32  y: int32 } }
            """;

    private static Tson tsonWithPoint() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(POINT_ID)) {
                return POINT_SCHEMA;
            }
            throw new IllegalStateException("no schema for " + uri);
        };
        return Tson.builder().schemaSource(source).build();
    }

    private static long asLong(TsonNode node) {
        return node.as(Number.class).orElseThrow().longValue();
    }

    @Test
    void schemaDrivenReadReturnsTheValidatedTree() {
        TsonNode node = tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""");

        assertTrue(node.isRecord());
        assertEquals(Optional.of("point"), node.typeRef());
        assertEquals(3, asLong(node.at("/x")));
        assertEquals(4, asLong(node.at("/y")));
    }

    @Test
    void schemalessReadReturnsATree() {
        TsonNode node = tsonWithPoint().treeReader().read("{ id: !uuid 9f1c8e2a-4b7d-4e6f-9a3b-2c5d8e7f1a09  n: !int32 5 }");

        assertTrue(node.isRecord());
        assertTrue(node.get("id").as(java.util.UUID.class).isPresent());
        assertEquals(5, asLong(node.get("n")));
    }

    @Test
    void aBadValueThrowsFailFast() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 99999999999999 }"""));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, thrown.diagnostic().code());
    }

    @Test
    void aSchemaDrivenDocumentWithNoRootTypeRefThrows() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/point-1.tn"
                { x: 3  y: 4 }"""));
        assertEquals(Diagnostic.Code.VALIDATION_ERROR, thrown.diagnostic().code());
        assertTrue(thrown.diagnostic().message().contains("root type-ref"), thrown::getMessage);
    }

    @Test
    void aSchemaTheSourceCannotProvideThrows() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/not-there.tn"
                !point { x: 3  y: 4 }"""));
        assertEquals(Diagnostic.Code.SCHEMA_ERROR, thrown.diagnostic().code());
    }

    @Test
    void aRootTypeRefTheSchemaDoesNotDeclareThrows() {
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPoint().treeReader().read("""
                !!schema:"https://example.test/point-1.tn"
                !no_such_type { x: 3  y: 4 }"""));
        assertEquals(Diagnostic.Code.UNKNOWN_TYPE, thrown.diagnostic().code());
    }

    // ── readObject: schema-driven bind to a Java object ──

    public record Point(int x, int y) {
    }

    private static Tson tsonWithPointBinding() {
        TsonSchemaSource source = uri -> {
            String base = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            if (base.equals(POINT_ID)) {
                return POINT_SCHEMA;
            }
            throw new IllegalStateException("no schema for " + uri);
        };
        DataNameBinder binder = name -> "point".equals(name) ? Point.class : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext context = TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        return Tson.builder().schemaSource(source).dataBindContext(context).build();
    }

    @Test
    void objectReaderReturnsTheSchemaBoundObject() {
        Point value = tsonWithPointBinding().objectReader().read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""", Point.class);

        assertEquals(new Point(3, 4), value);
    }

    @Test
    void objectReaderWithoutASchemaBindsSchemalessly() {
        // No !!schema -> bind straight into the given class, driven by its descriptor.
        Point value = tsonWithPointBinding().objectReader().read("{ x: 3  y: 4 }", Point.class);

        assertEquals(new Point(3, 4), value);
    }

    @Test
    void objectReaderWithTheWrongClassThrowsBeforeReading() {
        // The schema's root type `point` binds to Point, not String -- caught up front, before any value read.
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPointBinding().objectReader()
                .read("""
                        !!schema:"https://example.test/point-1.tn"
                        !point { x: 3  y: 4 }""", String.class));
        assertEquals(Diagnostic.Code.TYPE_MISMATCH, thrown.diagnostic().code());
    }

    @Test
    void objectReaderValidatesAsItBinds() {
        // y is out of int32 range -- fail-fast, same validation the tree read applies.
        TsonReadException thrown = assertThrows(TsonReadException.class, () -> tsonWithPointBinding().objectReader()
                .read("""
                        !!schema:"https://example.test/point-1.tn"
                        !point { x: 3  y: 99999999999999 }""", Point.class));
        assertEquals(Diagnostic.Code.ATOM_CONSTRAINT_VIOLATION, thrown.diagnostic().code());
    }

    @Test
    void readWithoutSchemaBindsEvenWhenTheSchemaIsUnavailable() {
        // read() would SCHEMA_ERROR (the source can't provide this URI); readWithoutSchema binds the class anyway.
        Point value = tsonWithPointBinding().objectReader().readWithoutSchema("""
                !!schema:"https://example.test/not-there.tn"
                !point { x: 3  y: 4 }""", Point.class);

        assertEquals(new Point(3, 4), value);
    }

    @Test
    void aStandaloneObjectReaderIgnoresADeclaredSchema() {
        // Built without a schema environment -> schemaless: any !!schema is ignored, binds to the class
        // (the Jackson-style "target class is the contract" case), even a !!schema the reader couldn't resolve.
        Point value = new TsonObjectReader(tsonWithPointBinding().dataBindContext()).read("""
                !!schema:"https://example.test/point-1.tn"
                !point { x: 3  y: 4 }""", Point.class);

        assertEquals(new Point(3, 4), value);
    }
}
