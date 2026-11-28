package io.ltr8.tson;

import io.ltr8.annotation.DataBridge;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class of the wrong shape for the schema type bound to it is the deployment's wiring, never this library's gap:
 * the compile refuses it ({@link BindMismatchException}), before any document, in every container factory.
 */
class WrongShapedHostTest {

    private static final String ID = "https://example.test/wrong-shape.tn";

    private static final String SCHEMA = """
            !!id:"https://example.test/wrong-shape.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              plain  => { n: int32 }
              base   => { n: int32 }
              sub    => base & { m: int32 }
              amount => { ( cents: integer | words: text ) }
              pair   => [int32, text]
              holder => { xs: [int32]  m: { text => int32 } }
            }
            """;

    public sealed interface Amount permits Cents, Words {
    }

    public record Cents(BigInteger cents) implements Amount {
    }

    /** A near-miss: its one component labels no field of {@code amount}. */
    public record Words(String wrds) implements Amount {
    }

    public record Holder(List<Integer> xs, Map<String, Integer> m) {
    }

    public static final class ListAsText implements DataBridge<String, List<?>> {

        @Override
        public String toData(List<?> object) {
            return object.toString();
        }

        @Override
        public List<?> toObject(String data) {
            return List.of(data);
        }
    }

    public static final class MapAsText implements DataBridge<String, Map<?, ?>> {

        @Override
        public String toData(Map<?, ?> object) {
            return object.toString();
        }

        @Override
        public Map<?, ?> toObject(String data) {
            return Map.of();
        }
    }

    private static BindMismatchException compileRefuses(Map<String, Class<?>> bound,
                                                        UnaryOperator<DataBindContext.Builder> more) {
        DataNameBinder binder = name -> bound.containsKey(name) ? bound.get(name)
                : SchemaModelNameBinder.INSTANCE.resolve(name);
        DataBindContext context = more.apply(DataBindContext.builder().nameBinder(binder)
                .registerAtoms(AtomContext.hostTypes())).build();
        Tson tson = Tson.of(ProcessorConfig.defaults().withDataBindContext(context)
                .withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(Map.of(ID, SCHEMA)))));
        return assertThrows(BindMismatchException.class, () -> tson.bindRegistry().get(ID));
    }

    private static BindMismatchException compileRefuses(Map<String, Class<?>> bound) {
        return compileRefuses(bound, UnaryOperator.identity());
    }

    private static void says(BindMismatchException thrown, String... parts) {
        for (String part : parts) {
            assertTrue(thrown.getMessage().contains(part), thrown.getMessage());
        }
    }

    @Test
    void aRecordBoundToAnAtomClassFailsTheCompile() {
        says(compileRefuses(Map.of("plain", String.class)), "'plain' is a record", "java.lang.String",
                "not record-shaped");
    }

    @Test
    void aRecordWithSubtypesBoundToAnAtomClassFailsTheCompile() {
        says(compileRefuses(Map.of("base", String.class)), "'base' is a record", "not record-shaped");
    }

    @Test
    void aLabelledChoiceNearMissFailsTheCompileNamingTheMember() {
        says(compileRefuses(Map.of("amount", Amount.class)), "labelled choice",
                Words.class.getName() + "'s component 'wrds' labels no field of the record");
    }

    @Test
    void aRecordWithNoGroupBoundToAUnionFailsTheCompile() {
        says(compileRefuses(Map.of("plain", Amount.class)), "'plain' is a record", "labelled choice",
                "the record has 0 groups");
    }

    @Test
    void aTupleBoundToAnAtomClassFailsTheCompile() {
        says(compileRefuses(Map.of("pair", String.class)), "'pair' is a tuple", "not tuple-shaped");
    }

    /** A {@code List} the context binds as an atom is no array an array type can fill. */
    @Test
    void anArrayOverAListTheContextBindsAsAnAtomFailsTheCompile() {
        says(compileRefuses(Map.of("holder", Holder.class), b -> b.registerAtom(List.class, new ListAsText())),
                "is an array", "java.util.List", "not array-shaped");
    }

    /** A {@code Map} the context binds as an atom is no map a map type can fill. */
    @Test
    void aMapOverAMapTheContextBindsAsAnAtomFailsTheCompile() {
        says(compileRefuses(Map.of("holder", Holder.class), b -> b.registerAtom(Map.class, new MapAsText())),
                "is a map", "java.util.Map", "not map-shaped");
    }
}
