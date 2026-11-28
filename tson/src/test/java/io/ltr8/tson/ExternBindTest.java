package io.ltr8.tson;

import io.ltr8.annotation.Annotations;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.BindByEntryIdentityTest.CardOne;
import io.ltr8.tson.BindByEntryIdentityTest.CardTwo;
import io.ltr8.tson.BindByEntryIdentityTest.Line;
import io.ltr8.tson.BindByEntryIdentityTest.OrderOne;
import io.ltr8.tson.BindByEntryIdentityTest.OrderTwo;
import io.ltr8.tson.BindByEntryIdentityTest.Rush;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.WriteException;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.ValueEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static io.ltr8.tson.ExternWorld.ORDER_1;
import static io.ltr8.tson.ExternWorld.ORDER_2;
import static io.ltr8.tson.ExternWorld.extern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A value bound through an extern ([TSON-SCHEMA] §7.9): its body bound by the foreign schema's readers, each class by
 * its entry's identity, so {@code card} of two foreign schemas binds two classes; and a bound value written back at the
 * boundary under the enclosing scope's names.
 */
public class ExternBindTest {

    static final String SHIP = "https://example.test/ship.tn";
    static final String SAMPLES = "https://example.test/samples.tn";
    static final String PETS = "https://example.test/pets.tn";
    static final String HOME = "https://example.test/home.tn";

    public record Envelope(String id, OrderOne a, OrderTwo b) {
    }

    public record AnnotatedOrder(String id, CardOne payment, Annotations annotations) {
    }

    public record Holder(AnnotatedOrder a) {
    }

    public sealed interface Pet permits Dog, Cat {
    }

    public record Dog(String name, boolean barks) implements Pet {
    }

    public record Cat(String name, boolean purrs) implements Pet {
    }

    public record Home(String owner, Pet pet, List<Pet> pets) {
    }

    private static final String ENVELOPE = "!envelope { id: e  a: { id: x  payment: { number: n } }  "
            + "b: { ref: r  lines: [{ sku: s  qty: 2 }]  payment: { pan: p } } }";

    private final ExternWorld world = new ExternWorld()
            .schema(SHIP, extern("order_v1", ORDER_1, "order") + extern("order_v2", ORDER_2, "order") + """
                    note     => text
                    envelope => { id: text  a: order_v1  b: order_v2 }
                    holder   => { a: order_v1 }
                    """)
            .schema(SAMPLES, """
                    sample => !envelope { id: s  a: { id: x  payment: { number: n } }
                                          b: { ref: r  lines: []  payment: { pan: p } } }
                    """, SHIP)
            .schema(PETS, """
                    pet => abstract { name: text }
                    dog => pet & { barks: boolean }
                    cat => pet & { purrs: boolean }
                    """)
            .schema(HOME, extern("pet_x", PETS, "pet") + extern("dog_x", PETS, "dog")
                    + "home => { owner: text  pet: pet_x  pets: [pet_x] }\n");

    private static String identity(String uri) {
        return CanonicalIdentity.canonicalize(uri);
    }

    private Tson shipping(Map<String, Class<?>> own, Class<?> order) {
        return world.tson(DataNameBinder.byNamespace(Map.of(
                identity(SHIP), DataNameBinder.ofMap(own),
                identity(ORDER_1), DataNameBinder.ofMap(Map.of("order", order, "card", CardOne.class)),
                identity(ORDER_2), DataNameBinder.ofMap(Map.of("order", OrderTwo.class, "card", CardTwo.class,
                        "line", Line.class, "rush", Rush.class))), DataNameBinder.ofMap(Map.of())));
    }

    private Tson shipping() {
        return shipping(Map.of("envelope", Envelope.class), OrderOne.class);
    }

    private Tson home() {
        return world.tson(DataNameBinder.byNamespace(Map.of(
                identity(HOME), DataNameBinder.ofMap(Map.of("home", Home.class)),
                identity(PETS), DataNameBinder.ofMap(Map.of("pet", Pet.class, "dog", Dog.class, "cat", Cat.class))),
                DataNameBinder.ofMap(Map.of())));
    }

    @Test
    void cardOfTwoForeignSchemasBindsToTwoClasses() {
        Envelope read = shipping().objectReader().read("!!import:\"" + SHIP + "\"\n" + ENVELOPE, Envelope.class);
        assertEquals(new Envelope("e", new OrderOne("x", new CardOne("n")),
                new OrderTwo("r", List.of(new Line("s", 2)), new CardTwo("p"))), read);
    }

    @Test
    void theAnnotationsInFrontOfTheBoundaryReachTheBoundRecordsCarrier() {
        Tson tson = shipping(Map.of("holder", Holder.class), AnnotatedOrder.class);
        Holder read = tson.objectReader().read("!!import:\"" + SHIP + "\"\n"
                + "!holder { a: @note:\"hi\" { id: x  payment: { number: n } } }", Holder.class);
        assertEquals(Optional.of("hi"), read.a().annotations().get("note").flatMap(note -> note.value()));
        assertEquals(new CardOne("n"), read.a().payment());
    }

    @Test
    void aSchemaDocumentsValueEntryBindsAForeignValueAtAnExternTypedField() {
        Tson tson = shipping();
        TsonLinkedSchema samples = tson.loader().resolveLinked(SAMPLES);
        ValueEntry sample = assertInstanceOf(ValueEntry.class, samples.schema().entries().get("sample"));
        assertEquals(new Envelope("s", new OrderOne("x", new CardOne("n")),
                new OrderTwo("r", List.of(), new CardTwo("p"))), sample.value());
    }

    @Test
    void aDiagnosticInsideABoundForeignValueCarriesWhatATreeReadsDoes() {
        List<Diagnostic> got = new ArrayList<>();
        shipping().objectReader().withDiagnostics(got::add).read("!!import:\"" + SHIP + "\"\n"
                + ENVELOPE.replace("qty: 2", "qty: x"), Envelope.class);
        assertEquals(1, got.size(), got.toString());
        Diagnostic diagnostic = got.getFirst();
        assertEquals("example.test/order-2.tn", diagnostic.schemaId());
        assertTrue(diagnostic.path().orElseThrow().endsWith("/b/lines/0/qty"), diagnostic.toString());
        assertTrue(diagnostic.schemaPointer().orElseThrow().startsWith("/order"), diagnostic.toString());
        assertTrue(diagnostic.message().endsWith("(read in the scope of example.test/order-2.tn, through the "
                + "extern 'order_v2' of example.test/ship.tn; [TSON-SCHEMA] §7.9)"), diagnostic.message());
    }

    @Test
    void aBoundValueWrittenThroughAnExternReadsBackEqualAndValidates() {
        Tson tson = shipping();
        Envelope envelope = tson.objectReader().read("!!import:\"" + SHIP + "\"\n" + ENVELOPE, Envelope.class);
        String written = tson.objectWriter().describing(tson.loader().resolveLinked(SHIP), "envelope")
                .toTson(envelope);
        assertEquals(List.of(), tson.validate(written), written);
        assertEquals(envelope, tson.objectReader().read(written, Envelope.class), written);
    }

    @Test
    void aSubtypeAtTheBoundaryIsTaggedWithTheEnclosingScopesName() {
        Tson tson = home();
        Home home = new Home("o", new Dog("rex", true), List.of(new Dog("fido", false)));
        String written = tson.objectWriter().describing(tson.loader().resolveLinked(HOME), "home").toTson(home);
        assertTrue(written.contains("pet: !dog_x"), written);
        assertEquals(List.of(), tson.validate(written), written);
        assertEquals(home, tson.objectReader().read(written, Home.class), written);
    }

    public sealed interface Kept permits Kennelled {
    }

    /** {@code java.net.URL} has no conversion in {@code ltr8-bind}, so this class cannot be described. */
    public record Kennelled(java.net.URL licence) implements Kept {
    }

    public record Kennel(String owner, Kept pet, List<Kept> pets) {
    }

    /**
     * A class the binder binds that the context cannot describe has no tag at a boundary, the binding holding no class
     * for its entry; the write is refused as it always was, describing the value's class failing before its tag is
     * asked for.
     */
    @Test
    void aBoundaryValueWhoseClassCannotBeDescribedIsRefusedByItsAnalysis() throws Exception {
        Tson tson = world.tson(DataNameBinder.byNamespace(Map.of(
                identity(HOME), DataNameBinder.ofMap(Map.of("home", Kennel.class)),
                identity(PETS), DataNameBinder.ofMap(Map.of("pet", Kept.class, "dog", Kennelled.class))),
                DataNameBinder.ofMap(Map.of())));
        TsonLinkedSchema linked = tson.loader().resolveLinked(HOME);
        Kennel kennel = new Kennel("o", new Kennelled(java.net.URI.create("https://x.test/l").toURL()), List.of());

        WriteException refused = assertThrows(WriteException.class,
                () -> tson.objectWriter().describing(linked, "home").toTson(kennel));

        assertTrue(refused.getMessage().contains("class java.net.URL"), refused.getMessage());
        assertTrue(!refused.getMessage().contains("the enclosing scope has no name"), refused.getMessage());
    }

    @Test
    void aSubtypeTheEnclosingScopeHasNoNameForIsRefused() {
        Tson tson = home();
        TsonLinkedSchema linked = tson.loader().resolveLinked(HOME);
        WriteException refused = assertThrows(WriteException.class, () -> tson.objectWriter()
                .describing(linked, "home").toTson(new Home("o", new Cat("tom", true), List.of())));
        assertTrue(refused.getMessage().contains("is written at a 'pet_x' position, and the enclosing scope has no "
                + "name reaching the entry it is bound to: a subtype is written at the boundary through a name of the "
                + "enclosing scope (pet_x | dog_x) ([TSON-SCHEMA] §7.9)"), refused.getMessage());
    }
}
