package io.ltr8.tson;

import io.ltr8.annotation.Tuple;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonTreeWriter;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.tree.TsonTuple;
import io.ltr8.tson.tree.TsonValue;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.3's tuple, written as an author writes it -- the {@code [T, U]} bracket form -- from schema source
 * through to a read value, in both read modes.
 *
 * <p>Where {@code TupleTreeReaderTest} exercises the reader over a hand-built {@code TupleBody}, these run
 * the whole pipeline: the sugar desugars to {@code !tuple { elements: [...] } }, resolves to a real entry,
 * links and compiles. Both spellings are covered -- at declaration position the bracket form <em>is</em> the
 * construction, at a field position it is hoisted into its own declaration and referenced -- since they
 * reach the resolver by different routes.
 *
 * <p>The counterpart to {@code ChoiceReadTest} for the other half of §5.3's variadic pair.
 */
class TupleReadTest {

    private static final String ID = "https://example.test/tuple-read.tn";

    /** {@code pair} declared as {@code declaration}, plus an inline tuple at a field position alongside it. */
    private static String schema(String declaration) {
        return """
                !!id:"%s"
                !!meta:"https://tson.io/2026/33/m/meta.tn"
                !!import:"https://tson.io/2026/33/m/core.tn"
                {
                  pair => %s
                  holder => {
                    p: pair
                    inline: [text, boolean]
                  }
                }""".formatted(ID, declaration);
    }

    private static TsonTypeReader<?> treeReader(String declaration, String type) {
        Tson tson = Tson.builder().build();
        return tson.treeRegistry().compile(tson.resolve(schema(declaration))).get(type);
    }

    private static TsonValue read(String declaration, String type, String data) {
        return (TsonValue) treeReader(declaration, type).read(TestDocuments.document(data));
    }

    // ── Tree mode ──

    /** A declaration-position tuple: the bracket form is the construction, so `pair` is the tuple entry itself. */
    @Test
    void aDeclarationPositionTupleReadsAsATuple() {
        TsonValue pair = read("[integer, text]", "pair", "[42 \"hello\"]");

        TsonTuple tuple = assertInstanceOf(TsonTuple.class, pair);
        assertEquals(Optional.of("pair"), tuple.typeRef());
        assertEquals(BigInteger.valueOf(42), tuple.get(0).asBigInteger().orElseThrow());
        assertEquals(Optional.of("hello"), tuple.get(1).asString());
    }

    /**
     * A tuple stays a tuple, not an array -- the distinction TSON's own record/map and array/tuple splits
     * exist to preserve, and one a schemaless read could never recover.
     */
    @Test
    void aTupleIsADistinctKindFromAnArray() {
        TsonValue pair = read("[integer, text]", "pair", "[42 \"hello\"]");

        assertInstanceOf(TsonTuple.class, pair);
        assertEquals(Optional.of("integer"), pair.get(0).typeRef(), "each position keeps its own type");
        assertEquals(Optional.of("text"), pair.get(1).typeRef());
    }

    /** An inline tuple at a field position is hoisted, so the field's value carries the injected entry's name. */
    @Test
    void anInlineTupleAtAFieldPositionReadsAsATupleToo() {
        TsonValue holder = read("[integer, text]", "holder", "{ p: [1 \"a\"]  inline: [\"x\" true] }");

        TsonTuple inline = assertInstanceOf(TsonTuple.class, holder.get("inline"));
        assertEquals(Optional.of("x"), inline.get(0).asString());
        assertEquals(Optional.of(Boolean.TRUE), inline.get(1).as(Boolean.class));
        assertTrue(inline.typeRef().orElseThrow().startsWith("tuple_text_boolean_"),
                "the injected declaration's derived name: " + inline.typeRef());
    }

    /** Arity is fixed and exact (§5.3) -- too few positions is a WRONG_ARITY, not a partial read. */
    @Test
    void tooFewPositionsIsAnArityError() {
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("[integer, text]", "pair", "[42]"));

        assertEquals(Diagnostic.Code.WRONG_ARITY, thrown.diagnostic().code());
    }

    @Test
    void tooManyPositionsIsAnArityErrorToo() {
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("[integer, text]", "pair", "[42 \"hello\" \"extra\"]"));

        assertEquals(Diagnostic.Code.WRONG_ARITY, thrown.diagnostic().code());
    }

    /** Each position is validated against its own declared type, not against a single shared element type. */
    @Test
    void eachPositionIsCheckedAgainstItsOwnType() {
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("[integer, text]", "pair", "[\"not a number\" \"hello\"]"));

        assertEquals(Optional.of("/0"), thrown.diagnostic().path(), "reported at the offending position");
    }

    /** A declaration-position `?` makes that position OPTIONAL, which is what admits the absent sentinel. */
    @Test
    void anOptionalPositionAdmitsTheAbsentSentinel() {
        TsonValue pair = read("[integer?, text]", "pair", "[_ \"hello\"]");

        TsonTuple tuple = assertInstanceOf(TsonTuple.class, pair);
        assertEquals(Optional.of("hello"), tuple.get(1).asString());
    }

    /**
     * A tuple is the one shape where an absent position is <em>visible</em> in the tree -- a record omits an
     * absent field and an array has no per-element state, so neither has a slot to misrepresent. It holds a
     * {@code TsonAbsent}, so {@code _} round-trips through {@link TsonTreeWriter} as {@code _}.
     */
    @Test
    void anAbsentPositionIsATsonAbsentAndRoundTripsAsTheSentinel() {
        TsonValue pair = read("[integer?, text]", "pair", "[_ \"hello\"]");

        assertTrue(pair.get(0).isAbsent());
        assertEquals("!pair [ _ !text \"hello\" ]", new TsonTreeWriter().toTson(pair));
    }

    /** And a REQUIRED one does not -- the default state, since a tuple element's `state` defaults to REQUIRED. */
    @Test
    void aRequiredPositionRejectsTheAbsentSentinel() {
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> read("[integer, text]", "pair", "[_ \"hello\"]"));

        assertEquals(Diagnostic.Code.FIELD_REQUIRED, thrown.diagnostic().code());
    }

    // ── Object-binding mode ──

    /**
     * A Java record opted into positional binding. {@code @Tuple} is the whole opt-in -- a plain record with
     * the same components would bind as a named-field TSON {@code record} instead.
     */
    @Tuple
    public record Pair(BigInteger first, String second) {
    }

    private static Tson tsonBindingPair() {
        DataNameBinder binder = name -> "pair".equals(name) ? Pair.class : SchemaMetaNameBinder.INSTANCE.resolve(name);
        DataBindContext context =
                TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
        return Tson.builder().dataBindContext(context).build();
    }

    /** The same schema through {@code TupleBindReader}: a real, positionally-bound Java object, not a node. */
    @Test
    void aTupleBindsPositionallyIntoAJavaRecord() {
        Tson tson = tsonBindingPair();
        TsonTypeReader<?> reader = tson.bindRegistry().compile(tson.resolve(schema("[integer, text]"))).get("pair");

        Object value = reader.read(TestDocuments.document("[42 \"hello\"]"));

        assertEquals(new Pair(BigInteger.valueOf(42), "hello"), value);
    }

    /**
     * Bind mode is all-or-nothing ({@code ConstructionGuard}): a tuple whose read reported anything is not
     * assembled at all, rather than handed back with a null position in it.
     */
    @Test
    void aReportedTupleIsNotAssembled() {
        Tson tson = tsonBindingPair();
        TsonTypeReader<?> reader = tson.bindRegistry().compile(tson.resolve(schema("[integer, text]"))).get("pair");

        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> reader.read(TestDocuments.document("[42]")));

        assertEquals(Diagnostic.Code.WRONG_ARITY, thrown.diagnostic().code());
    }
}
