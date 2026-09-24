package io.ltr8.tson;

import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §8.2 names a materialised instantiation by a function of its <b>resolved form alone</b>, which is what lets
 * §2.2.3 unify two schemas that each closed the same application: they mint one name because they mint one
 * entry.
 *
 * <p><b>So the content of a minted entry may not depend on which schema minted it.</b> That is the property
 * under test, and it is stronger than "dedup works". The collision check in {@code TsonSchemaLinker} compares
 * two same-named entries <em>by value</em> and unifies only when they are equal -- deliberately, since sameness
 * is what makes them one entry -- so any component that varies with the closing schema turns a diamond into a
 * name collision, at the importer, with a message about {@code !!import} that names nothing an author wrote.
 *
 * <p>The component that can vary is {@code subtypes}, because it is the one the <em>linker</em> derives after
 * the fact: a template closed in its own schema is closed before anything has linked, while the same template
 * reaching an importer through {@code !!import} arrives already carrying whatever its home schema credited to
 * it. An instantiation minted from the template's own list would therefore differ between the two routes --
 * and, once an instantiation indexes under its template, would list itself. Hence {@code
 * TemplateMaterialiser} mints with none and leaves {@code subtypes} to linking, where every other entry's
 * comes from.
 */
class MintedEntryUnificationTest {

    private static final String LEFT = """
            !!id:"https://example.test/left.tn"
            !!meta:"%s"
            !!import:"%s"
            {
              box      => <V> { item: V }
              left_use => { x: box<text> }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    private static final String RIGHT = """
            !!id:"https://example.test/right.tn"
            !!meta:"%s"
            !!import:"https://example.test/left.tn"
            {
              right_use => { y: box<text> }
            }""".formatted(TsonBundledSchemas.META_ID);

    /** Both schemas resolved through one processor, {@code left} first so {@code right}'s import is registered. */
    private static Map<String, TsonLinkedSchema> both() {
        SchemaSource source = uri -> uri.contains("left.tn") ? LEFT : RIGHT;
        Tson tson = Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(source)));
        return Map.of("left", tson.resolve(LEFT), "right", tson.resolve(RIGHT));
    }

    /** The one minted name both schemas reach by closing {@code box<text>}. */
    private static String mintedName(TsonLinkedSchema schema) {
        return schema.schema().entries().keySet().stream().filter(name -> name.startsWith("box_text_"))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no box<text> instantiation in " + schema.schema().entries().keySet()));
    }

    /**
     * The failure this exists for: {@code right.tn} imports {@code left.tn} and writes the same application,
     * so the importer sees the name twice and must unify rather than collide.
     */
    @Test
    void anImporterClosingTheSameApplicationUnifiesRatherThanColliding() {
        assertNotNull(both().get("right"), "right.tn links");
    }

    /** Unification is by value, so the two routes' entries must be equal in every component. */
    @Test
    void bothSchemasMintTheSameEntry() {
        Map<String, TsonLinkedSchema> schemas = both();
        String name = mintedName(schemas.get("left"));
        assertEquals(name, mintedName(schemas.get("right")), "one content-addressed name");

        TypeDefinition fromLeft = schemas.get("left").schema().entries().get(name);
        TypeDefinition fromRight = schemas.get("right").schema().entries().get(name);
        assertEquals(fromLeft, fromRight,
                () -> "an entry shaped by which schema closed it is not a function of the form:\n  left:  "
                        + fromLeft + "\n  right: " + fromRight);
    }

    /**
     * And the instantiation is not its own subtype. Minting from the template's own list would put the entry
     * in its own index the moment the template arrived already credited -- the self-edge being exactly what
     * made the two routes differ.
     */
    @Test
    void theInstantiationIsNotItsOwnSubtype() {
        Map<String, TsonLinkedSchema> schemas = both();
        String name = mintedName(schemas.get("right"));

        assertEquals(List.of(), schemas.get("right").schema().entries().get(name).subtypes(),
                "a closed record has no subtypes of its own here");
    }

    /** The index step 4 adds still lands, on the template, in both schemas. */
    @Test
    void theTemplateStillIndexesTheInstantiation() {
        Map<String, TsonLinkedSchema> schemas = both();
        schemas.forEach((which, schema) -> assertTrue(
                schema.schema().entries().get("box").subtypes().contains(mintedName(schema)),
                () -> which + ": " + schema.schema().entries().get("box").subtypes()));
    }
}
