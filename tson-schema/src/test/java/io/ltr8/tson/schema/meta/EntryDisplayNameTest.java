package io.ltr8.tson.schema.meta;

import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.TsonSchema;
import org.junit.jupiter.api.Test;

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

    /** An entry no source was read for, rendered: one the kernel's bootstrap builds, or one read from a resolved form. */
    private static String unpositioned(String name, Top value) {
        return EntryDisplayName.of(name, value, TsonSchema.EntryFacts.NONE, Optional.empty());
    }

    private static ArrayBody array(Optional<Long> min, Optional<Long> max) {
        return new ArrayBody(TypeRef.of("order"), false, true, false, min, max);
    }

    @Test
    void aDeclarationKeepsItsOwnNameWhateverItsShape() {
        Top declared = array(Optional.of(1L), Optional.empty());

        assertEquals("tag_list", EntryDisplayName.of("tag_list", declared,
                TsonSchema.EntryFacts.at(Optional.of(SOMEWHERE)), Optional.empty()));
    }

    @Test
    void anArrayRendersAsItsSugarWithEverySizeForm() {
        assertEquals("[order]", unpositioned("x", array(Optional.empty(), Optional.empty())));
        assertEquals("[order; 1..]", unpositioned("x", array(Optional.of(1L), Optional.empty())));
        assertEquals("[order; ..5]", unpositioned("x", array(Optional.empty(), Optional.of(5L))));
        assertEquals("[order; 1..2]", unpositioned("x", array(Optional.of(1L), Optional.of(2L))));
        assertEquals("[order; 3]",
                unpositioned("x", array(Optional.of(3L), Optional.of(3L))));
    }

    @Test
    void aMapRendersAsItsSugar() {
        MapBody body = new MapBody(TypeRef.of("text"), TypeRef.of("order"), false, false,
                Optional.of(1L), Optional.empty());

        assertEquals("{text => order; 1..}", unpositioned("x", body));
    }

    @Test
    void aTupleAndAChoiceRenderAsTheirs() {
        TupleBody tuple = new TupleBody(List.of(TupleElement.required(TypeRef.of("text")),
                TupleElement.required(TypeRef.of("int32"))));
        ChoiceBody choice = new ChoiceBody(List.of(TypeRef.of("text"), TypeRef.of("int32")));

        assertEquals("[text, int32]", unpositioned("x", tuple));
        assertEquals("(text | int32)", unpositioned("x", choice));
    }

    /**
     * An entry with no position that closes an application renders as the application, from its key's
     * {@code @meta.application} -- the application as written ([TSON-SCHEMA] §8.2).
     */
    @Test
    void anEntryClosingAnApplicationRendersAsTheApplication() {
        TypeRef application = TypeRef.of("paged", List.of(TypeRef.of("order"), TypeRef.of("text")));

        assertEquals("paged<order, text>", EntryDisplayName.of("paged_order_text", RecordBody.of(RecordBody.fieldMap()),
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
        assertEquals("mystery", unpositioned("mystery", RecordBody.of(RecordBody.fieldMap())));
    }
}
