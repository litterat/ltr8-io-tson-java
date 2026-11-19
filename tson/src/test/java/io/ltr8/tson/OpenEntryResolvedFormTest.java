package io.ltr8.tson;
import io.ltr8.tson.base.ProcessorConfig;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Param;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What [TSON-SCHEMA] §8.1 says about an <b>open</b> entry's resolved form, asked of real documents.
 *
 * <p>An open entry is a parametric record: a {@code !meta.record} carrying {@code parameters}, each a type written where it
 * stands as its name, a reference value, so the record is read against {@code record}'s own vocabulary as any record
 * is. The tests below hold that against real documents: the published fixtures validate, a parametric record validates
 * and reads back with its parameters, and every entry with parameters is one.
 */
class OpenEntryResolvedFormTest {

    private static Tson tson() {
        return Tson.standard();
    }

    /**
     * The same front door with the internal {@code schema.meta} vocabulary bound, for the one test that reads
     * a resolved document <em>back</em> rather than validating it -- the binding is what makes the misreading
     * visible as a value rather than as a clean verdict.
     */
    private static Tson metaBoundTson() {
        return Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaModelNameBinder.defaultContext()));
    }

    /** {@code spec/fixtures}, found by walking up rather than assumed relative to a working directory. */
    private static Path specDirectory() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("spec/fixtures"))) {
            directory = directory.getParent();
        }
        assertNotNull(directory, "no spec/fixtures above " + Path.of("").toAbsolutePath());
        return directory.resolve("spec/fixtures");
    }

    // ── What holds today, and must go on holding ─────────────────────────

    /**
     * <b>The published resolver-output fixtures are valid data under the schema each names.</b> §1.3 makes
     * producing a resolved schema value a MUST and §8 fixes its serialization, so this is the baseline claim
     * of §8's whole output form -- and the fixtures are the spec's own statement of it, not this
     * implementation's.
     *
     * <p>The kernel declares no parametric record, so this is a regression guard over closed entries.
     */
    @Test
    void everyPublishedResolvedFixtureValidatesAgainstItsGoverningSchema() throws Exception {
        Tson tson = tson();
        // A library's form holds instances of constructors its source declares, and a resolved form is read under its
        // source's imports, not over its own entries as [TSON-SCHEMA] §8.1 reads one: each is read beside its source.
        Map<String, String[]> fixtures = new LinkedHashMap<>();
        fixtures.put("meta-resolved.tn", new String[0]);
        fixtures.put("numeric-resolved.tn", new String[] {TsonBundledSchemas.NUMERIC_ID});
        fixtures.put("temporal-resolved.tn", new String[] {TsonBundledSchemas.TEMPORAL_ID});
        fixtures.put("net-resolved.tn", new String[] {TsonBundledSchemas.NET_ID});
        fixtures.put("core-resolved.tn", new String[] {TsonBundledSchemas.CORE_ID});
        for (Map.Entry<String, String[]> fixture : fixtures.entrySet()) {
            List<Diagnostic> diagnostics = ResolvedForm.validateResolved(tson,
                    Files.readString(specDirectory().resolve(fixture.getKey())), fixture.getValue());
            assertEquals(List.of(), diagnostics,
                    () -> fixture.getKey() + " does not validate against the schema it names");
        }
    }

    // ── A parametric record's resolved form ───────────────────────────────────

    /**
     * <b>A parametric record validates in §8.1's form</b>: a {@code !meta.record} carrying {@code parameters}, each standing
     * where it is written as its name, a reference value -- at a field's type, and at a slot of a type written in place.
     * Nothing about it needs a vocabulary of its own: it is read against {@code record}'s, as any record is.
     *
     * <p>The document is hand-authored rather than produced by this library's writer, deliberately: the claim under
     * test is about the <em>spec's</em> prescribed serialization.
     */
    @Test
    void aParametricRecordValidatesAsARecord() {
        assertEquals(List.of(), ResolvedForm.validateResolved(tson(), """
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  page => !meta.record {
                    parameters: [ !meta.param { name: T } ]
                    fields: {
                      item  => !meta.record_field { type: T }
                      items => !meta.record_field { type: { of: !meta.array { element_type: T } } }
                    }
                  }
                }
                """), "a parameter is a reference value, admitted by the slot it stands at");
    }

    /**
     * <b>And it reads back as the parametric record it is</b>: the record's parameters are looked up before the namespace,
     * so its field types are parameters ({@link ParamRef}), whole and inside a type written in place, and no reference
     * names an entry spelled {@code T}.
     */
    @Test
    void aParametricRecordReadsBackWithItsParameters() {
        Map<String, Object> read = ResolvedForm.readResolved(metaBoundTson(), """
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !schema {
                  page => !meta.record {
                    parameters: [ !meta.param { name: T } ]
                    fields: {
                      item  => !meta.record_field { type: T }
                      items => !meta.record_field { type: { of: !meta.array { element_type: T } } }
                    }
                  }
                }
                """);

        RecordBody page = assertInstanceOf(RecordBody.class, read.get("page"));
        assertEquals(List.of(new Param("T")), page.parameters());
        assertEquals(ParamRef.of("T"), page.fields().get("item").type());
        assertEquals(new TypeInline(ArrayBody.of(ParamRef.of("T"))), page.fields().get("items").type());
    }

    // ── The biconditional an entry's parameters rest on ─────────────────

    /**
     * <b>An entry declares parameters exactly when it is a parametric record</b>, asked of every entry of every bundled
     * schema and of a schema exercising each parametric record shape: a fresh record body, a composition adding a field and
     * one restating a field. The kernel declares none.
     */
    @Test
    void anEntryWithParametersIsAParametricRecord() {
        Tson tson = tson();
        tson.resolve("""
                !!id:"https://example.com/shapes.tn"
                !!import:"https://tson.io/2026/40/m/meta.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  base     => { id: text }
                  pair     => <A, B> { first: A  second: B }
                  composed => <T> base & { value: T }
                  restated => <T> pair<T, text> & { second: = hi }
                  composed_int => composed<int32>
                  holder   => { p: pair<text, int32>  q: composed_int }
                }
                """);
        List<String> ids = List.of(TsonBundledSchemas.META_ID, TsonBundledSchemas.NUMERIC_ID,
                TsonBundledSchemas.TEMPORAL_ID, TsonBundledSchemas.NET_ID, TsonBundledSchemas.CORE_ID,
                "https://example.com/shapes.tn");

        List<String> broken = new ArrayList<>();
        List<String> open = new ArrayList<>();
        for (String id : ids) {
            for (Map.Entry<String, Top> entry
                    : tson.bindRegistry().core().resolveLinked(id).schema().entries().entrySet()) {
                Top definition = entry.getValue();
                boolean declares = !OpenEntry.parameterNames(definition).isEmpty();
                if (declares) {
                    open.add(entry.getKey());
                }
                if (declares != (definition instanceof RecordBody record && record.isOpen())) {
                    broken.add(id + "#" + entry.getKey() + ": parameters " + OpenEntry.parameterNames(definition)
                            + " with a " + definition.getClass().getSimpleName() + " body");
                }
            }
        }

        assertEquals(List.of(), broken, "§5.10: an entry with parameters is a parametric record");
        assertEquals(List.of("pair", "composed", "restated"), open, "the kernel declares no parametric record");
    }
}
