package io.ltr8.tson.compiler;

import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire annotations (§3.1) reaching a bound Java object through a <b>schema-driven</b> read -- the path that
 * dropped them until now, and the one where §6 gives an annotation a declared type to bind against.
 *
 * <p>Two things distinguish this from the schemaless object path ({@code TsonObjectReaderTest}). The
 * annotation's <em>name</em> resolves one hop against the governing schema, so its value is read by that
 * type's own compiled reader and arrives as an ordinary bound Java object rather than a structural node. And
 * the carrier occupies a constructor slot the schema knows nothing about, so the reader has to fill it
 * itself -- see {@code arityIsHandledInBothDirections} for what goes wrong if it doesn't.
 */
class SchemaDrivenBindAnnotationTest {

    private static final String ID = "https://example.test/annotated.tn";

    /**
     * {@code note} is an annotation type, not a field. The document annotates the record value with
     * {@code @note:"..."}, which §6 resolves against this schema's own namespace -- so it is declared here
     * like any other type.
     */
    private static final String SCHEMA = """
            !!id:"https://example.test/annotated.tn"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              note => text
              rank => int32
              marker => void
              widget => { name: text }
            }
            """;

    /** Declaring an {@link Annotations} component is the whole opt-in; it is not a schema field. */
    public record Widget(Annotations annotations, String name) {
    }

    /** The same record without a carrier -- the overwhelmingly common shape, which must be unaffected. */
    public record PlainWidget(String name) {
    }

    private static TsonCompiledSchema compile(Class<?> widgetClass) {
        DataNameBinder binder = schemaTypeName -> "widget".equals(schemaTypeName)
                ? widgetClass
                : SchemaMetaNameBinder.INSTANCE.resolve(schemaTypeName);
        DataBindContext context =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        TsonSchemaSource source = uri -> {
            if (TsonSchemaRegistry.canonicalIdentity(uri).equals(TsonSchemaRegistry.canonicalIdentity(ID))) {
                return SCHEMA;
            }
            throw new IllegalStateException("unexpected fetch: " + uri);
        };
        TsonCompiledMetaRegistry core =
                TsonCompiledMetaRegistry.withStandardLibrary(SchemaMetaNameBinder.defaultContext(), source);
        return TsonCompiledSchemaRegistry.bind(core, context).get(ID);
    }

    private static Widget read(String document) {
        return (Widget) compile(Widget.class).get("widget").read(document);
    }

    /**
     * The whole point of the stage: an annotation's value arrives <b>bound</b>, by the reader for the type
     * §6 says the name refers to. {@code note => text} makes it a {@code String}, not a node.
     */
    @Test
    void anAnnotationValueArrivesBoundByItsOwnDeclaredType() {
        Widget widget = read("@note:\"a widget\" { name: \"Widget\" }");

        assertEquals("Widget", widget.name());
        Annotation note = widget.annotations().get("note").orElseThrow();
        assertEquals("a widget", assertInstanceOf(String.class, note.value().orElseThrow()));
    }

    /** A second annotation type, to show the binding follows the name rather than one hard-wired shape. */
    @Test
    void eachAnnotationBindsThroughTheTypeItsOwnNameResolvesTo() {
        Widget widget = read("@note:\"first\" @rank:7 { name: \"Widget\" }");

        assertEquals("first", widget.annotations().get("note").orElseThrow().value().orElseThrow());
        assertEquals(7, assertInstanceOf(Integer.class, widget.annotations().get("rank").orElseThrow()
                .value().orElseThrow()));
    }

    @Test
    void repeatedAnnotationsArePreservedInSourceOrder() {
        // §3.1: "An annotation name MAY appear any number of times on a single value; all occurrences are
        // preserved in source order."
        Widget widget = read("@note:\"one\" @note:\"two\" { name: \"Widget\" }");

        List<Annotation> notes = widget.annotations().getAll("note");
        assertEquals(2, notes.size());
        assertEquals("one", notes.get(0).value().orElseThrow());
        assertEquals("two", notes.get(1).value().orElseThrow());
    }

    /**
     * The carrier is filled even when nothing was annotated. {@code arguments} is sized by the Java class and
     * written only through matched schema fields, so a component the schema never mentions would otherwise
     * reach the constructor as {@code null} -- silently, since nothing checks it.
     */
    @Test
    void anUnannotatedValueStillGetsAnEmptyCarrierNotNull() {
        Widget widget = read("{ name: \"Widget\" }");

        assertEquals(Annotations.empty(), widget.annotations());
        assertTrue(widget.annotations().isEmpty());
    }

    /**
     * The carrier takes no part in field matching, in either direction: the schema's {@code widget} has one
     * field and the Java record has two components, and a document cannot reach the carrier by naming it.
     */
    @Test
    void arityIsHandledInBothDirections() {
        Widget widget = read("@note:\"kept\" { name: \"Widget\" }");

        assertEquals("kept", widget.annotations().get("note").orElseThrow().value().orElseThrow());
        assertEquals("Widget", widget.name());
    }

    /** No carrier declared: annotations are consumed and dropped, and the record binds as it always did. */
    @Test
    void aClassWithoutACarrierIsUnaffected() {
        PlainWidget widget =
                (PlainWidget) compile(PlainWidget.class).get("widget").read("@note:\"ignored\" { name: \"Widget\" }");

        assertEquals("Widget", widget.name());
    }

    // ── Round trip (write side) ──────────────────────────────────────────

    /**
     * Read, then written back with the annotations in place -- the loop that was open until the writer
     * learned to emit them. §7.4 orders a data-value {@code *annotation [type-ref] core-value}, so they
     * precede the record.
     */
    @Test
    void annotationsSurviveARoundTrip() {
        Widget widget = read("@note:\"a widget\" @rank:7 { name: \"Widget\" }");

        String written = new TsonObjectWriter().toTson(widget);

        assertEquals("@note:\"a widget\" @rank:7 { name: \"Widget\" }", written);
    }

    @Test
    void aValuelessAnnotationRoundTrips() {
        // The trailing space after a valueless annotation is load-bearing, not cosmetic: §3.1 makes the
        // character after the name the whole boundary rule.
        Widget widget = read("@marker { name: \"Widget\" }");

        assertEquals("@marker { name: \"Widget\" }", new TsonObjectWriter().toTson(widget));
    }

    @Test
    void anUnannotatedValueWritesNoAnnotations() {
        assertEquals("{ name: \"Widget\" }", new TsonObjectWriter().toTson(read("{ name: \"Widget\" }")));
    }

    /**
     * [TSON-DATA] §1.5 requires preserving what a processor does not act on, so an annotation naming no
     * declared type is still delivered -- read structurally, since there is no type to bind it through.
     * A diagnostic is reported for the unresolved name, which fail-fast mode surfaces as the throw.
     */
    @Test
    void anAnnotationNamingNoDeclaredTypeIsReportedNotSilentlyDropped() {
        TsonReadException thrown = org.junit.jupiter.api.Assertions.assertThrows(TsonReadException.class,
                () -> read("@nosuchtype:\"x\" { name: \"Widget\" }"));

        assertTrue(thrown.getMessage().contains("nosuchtype"), thrown.getMessage());
    }
}
