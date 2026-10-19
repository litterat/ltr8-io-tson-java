package io.ltr8.tson.schema.meta;

import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link EntryDisplayName}'s two questions: did the author write this name, and if not, what did they write? */
class EntryDisplayNameTest {

    private static final SourcePosition SOMEWHERE = new SourcePosition() {
        @Override
        public int line() {
            return 4;
        }

        @Override
        public int column() {
            return 3;
        }

        @Override
        public int byteOffset() {
            return 42;
        }
    };

    /** An entry the resolver minted, rendered: no position, because there is no declaration to have one. */
    private static String minted(String name, Top value) {
        return EntryDisplayName.of(name, value, TsonSchema.EntryFacts.NONE, Optional.empty());
    }

    private static ArrayBody array(Optional<BigInteger> min, Optional<BigInteger> max) {
        return new ArrayBody(TypeRef.of("order"), false, true, false, min, max);
    }

    @Test
    void aDeclarationKeepsItsOwnNameWhateverItsShape() {
        Top declared = array(Optional.of(BigInteger.ONE), Optional.empty());

        assertEquals("tag_list", EntryDisplayName.of("tag_list", declared,
                TsonSchema.EntryFacts.at(Optional.of(SOMEWHERE)), Optional.empty()));
    }

    @Test
    void anArrayRendersAsItsSugarWithEverySizeForm() {
        assertEquals("[order]", minted("x", array(Optional.empty(), Optional.empty())));
        assertEquals("[order; 1..]", minted("x", array(Optional.of(BigInteger.ONE), Optional.empty())));
        assertEquals("[order; ..5]", minted("x", array(Optional.empty(), Optional.of(BigInteger.valueOf(5)))));
        assertEquals("[order; 1..2]", minted("x", array(Optional.of(BigInteger.ONE), Optional.of(BigInteger.TWO))));
        assertEquals("[order; 3]",
                minted("x", array(Optional.of(BigInteger.valueOf(3)), Optional.of(BigInteger.valueOf(3)))));
    }

    @Test
    void aMapRendersAsItsSugar() {
        MapBody body = new MapBody(TypeRef.of("text"), TypeRef.of("order"), false, false,
                Optional.of(BigInteger.ONE), Optional.empty());

        assertEquals("{text => order; 1..}", minted("x", body));
    }

    @Test
    void aTupleAndAChoiceRenderAsTheirs() {
        TupleBody tuple = new TupleBody(List.of(TupleElement.required(TypeRef.of("text")),
                TupleElement.required(TypeRef.of("int32"))));
        ChoiceBody choice = new ChoiceBody(List.of(TypeRef.of("text"), TypeRef.of("int32")));

        assertEquals("[text, int32]", minted("x", tuple));
        assertEquals("(text | int32)", minted("x", choice));
    }

    /**
     * An instantiation entry renders as the application it closes, from its key -- which is what §8.2 keys
     * its identity on, so it is exactly what the author wrote at the site.
     */
    @Test
    void anInstantiationEntryRendersAsTheApplication() {
        TypeRef application = new TypeRef("paged", List.of(new TypeArgument.Ref(TypeRef.of("order")),
                new TypeArgument.Value(new Token("3", Token.Form.UNQUOTED))));

        assertEquals("paged<order, 3>", EntryDisplayName.of("paged_order_3_abc", RecordBody.of(RecordBody.fieldMap()),
                TsonSchema.EntryFacts.NONE, Optional.of(application)));
    }

    /** A construction's tag is a bare constructor name, which is not an application and renders as the body. */
    @Test
    void aConstructionIsNotAnApplication() {
        assertEquals("[order]", EntryDisplayName.of("x", array(Optional.empty(), Optional.empty()),
                TsonSchema.EntryFacts.constructedBy("array"), Optional.empty()));
    }

    /** No sugar spelling and no application: the name, honestly, rather than something invented. */
    @Test
    void anythingElseFallsBackToTheName() {
        assertEquals("mystery", minted("mystery", RecordBody.of(RecordBody.fieldMap())));
    }
}
