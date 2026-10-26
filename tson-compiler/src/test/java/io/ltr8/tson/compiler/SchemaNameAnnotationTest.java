package io.ltr8.tson.compiler;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documentation on a schema declaration, which is where the bundled schemas actually put it: every one of
 * their {@code @doc} annotations precedes the declared name, so §6 binds it to the <em>name</em> and forbids
 * hoisting it onto the definition. A resolved schema is a <code>{type_name =&gt; top}</code>, so the
 * name is that map's key -- and {@link AnnotatedMap} is what keeps a key's annotations reachable while the
 * map still presents its plain {@code String} keys.
 *
 * <p>The two sets stay separate, as §6 requires: an annotation written after {@code =>} lands on the entry's
 * own {@link Top}, one written before the name lands here.
 */
class SchemaNameAnnotationTest {

    private static Namespace<Top> entriesOf(String id) {
        return linked(id).entries();
    }

    private static TsonSchema.EntryFacts factsOf(String id, String name) {
        return linked(id).factsOf(name);
    }

    private static TsonSchema linked(String id) {
        TsonCompiledMetaRegistry core = TsonCompiledMetaRegistry.withStandardLibrary(
                SchemaMetaNameBinder.defaultContext(), uri -> {
                    throw new IllegalStateException("unexpected fetch: " + uri);
                });
        return core.resolveLinked(id).schema();
    }

    /** Bound through the type §6 says the name refers to -- {@code doc => documentation => text}, so a String. */
    @Test
    void aDeclarationsDocArrivesAsAString() {
        Namespace<Top> core = entriesOf(TsonBundledSchemas.CORE_ID);

        assertEquals("Two-value boolean enumeration.",
                core.getAnnotations("boolean").value("doc", String.class).orElseThrow());
    }

    /** The plain-key interface is untouched, which is the whole point of the facade. */
    @Test
    void theMapStillBehavesAsAPlainStringKeyedMap() {
        Namespace<Top> core = entriesOf(TsonBundledSchemas.CORE_ID);

        assertTrue(core.containsKey("boolean"));
        assertEquals(core.get("boolean"), core.entrySet().stream()
                .filter(e -> e.getKey().equals("boolean")).findFirst().orElseThrow().getValue());
    }

    /**
     * Every entry core.tn declares is documented, and the annotations survive resolution *and* linking --
     * the linker rebuilds its entry map several times over, so this is the pass that would silently lose
     * them.
     */
    @Test
    void everyCoreDeclarationKeepsItsDocumentationThroughLinking() {
        Namespace<Top> core = entriesOf(TsonBundledSchemas.CORE_ID);

        long documented = core.keySet().stream().filter(k -> core.getAnnotations(k).has("doc")).count();
        assertEquals(core.size(), documented, "core.tn documents every declaration it makes");
    }

    /** An imported name keeps the documentation its own schema resolved, not the importer's. */
    @Test
    void animportedNameKeepsItsOwnDocumentation() {
        Namespace<Top> meta = entriesOf(TsonBundledSchemas.META_ID);
        Namespace<Top> kernel = entriesOf(TsonBundledSchemas.META_KERNEL_ID);

        // schema is the meta-kernel's own declaration, reached by meta.tn through its !!import.
        assertEquals(kernel.getAnnotations("schema").value("doc", String.class),
                meta.getAnnotations("schema").value("doc", String.class));
        assertTrue(meta.getAnnotations("schema").has("doc"));
    }

    /**
     * §6's split, held: {@code annotation => @annotation void} writes its marker after {@code =>}, so it is
     * the definition's, while the {@code @doc} above the name is the name's. Neither is hoisted onto the
     * other.
     */
    @Test
    void nameAndDefinitionAnnotationsStaySeparate() {
        Namespace<Top> kernel = entriesOf(TsonBundledSchemas.META_KERNEL_ID);

        Annotations onValue = factsOf(TsonBundledSchemas.META_KERNEL_ID, "annotation").annotations();
        assertTrue(onValue.has("annotation"), "the definition's own marker");
        assertTrue(kernel.getAnnotations("annotation").has("doc"), "the name's own documentation");
        assertTrue(onValue.value("doc", String.class).isEmpty(), "not hoisted onto the definition");
    }
}
