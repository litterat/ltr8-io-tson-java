package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.SourcePosition;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
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

    /** An entry the resolver minted: no position, because there is no declaration to have one. */
    private static TypeDefinition synthetic(io.ltr8.tson.schema.meta.Top body) {
        return new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), false, List.of(), List.of(),
                Optional.empty(), body, Optional.empty());
    }

    private static TypeDefinition synthetic(io.ltr8.tson.schema.meta.Top body, TypeRef source) {
        return new TypeDefinition(Optional.of(source), TypeKind.PRODUCT, List.of(), false, List.of(), List.of(),
                Optional.empty(), body, Optional.empty());
    }

    private static ArrayBody array(Optional<BigInteger> min, Optional<BigInteger> max) {
        return new ArrayBody(TypeRef.of("order"), ElementState.REQUIRED, false, false, min, max);
    }

    @Test
    void aDeclarationKeepsItsOwnNameWhateverItsShape() {
        TypeDefinition declared = new TypeDefinition(Optional.of(TypeRef.of("array")), TypeKind.PRODUCT, List.of(),
                false, List.of(), List.of(), Optional.empty(),
                array(Optional.of(BigInteger.ONE), Optional.empty()), Optional.of(SOMEWHERE));

        assertEquals("tag_list", EntryDisplayName.of("tag_list", declared));
    }

    @Test
    void anArrayRendersAsItsSugarWithEverySizeForm() {
        assertEquals("[order]",
                EntryDisplayName.of("x", synthetic(array(Optional.empty(), Optional.empty()))));
        assertEquals("[order; 1..]",
                EntryDisplayName.of("x", synthetic(array(Optional.of(BigInteger.ONE), Optional.empty()))));
        assertEquals("[order; ..5]",
                EntryDisplayName.of("x", synthetic(array(Optional.empty(), Optional.of(BigInteger.valueOf(5))))));
        assertEquals("[order; 1..2]", EntryDisplayName.of("x",
                synthetic(array(Optional.of(BigInteger.ONE), Optional.of(BigInteger.TWO)))));
        assertEquals("[order; 3]", EntryDisplayName.of("x",
                synthetic(array(Optional.of(BigInteger.valueOf(3)), Optional.of(BigInteger.valueOf(3))))));
    }

    @Test
    void aMapRendersAsItsSugar() {
        MapBody body = new MapBody(TypeRef.of("text"), TypeRef.of("order"), ElementState.REQUIRED, Optional.of(BigInteger.ONE), Optional.empty());

        assertEquals("{text => order; 1..}", EntryDisplayName.of("x", synthetic(body)));
    }

    @Test
    void aTupleAndAChoiceRenderAsTheirs() {
        TupleBody tuple = new TupleBody(List.of(TupleElement.required(TypeRef.of("text")),
                TupleElement.required(TypeRef.of("int32"))));
        ChoiceBody choice = new ChoiceBody(List.of(TypeRef.of("text"), TypeRef.of("int32")));

        assertEquals("[text, int32]", EntryDisplayName.of("x", synthetic(tuple)));
        assertEquals("(text | int32)", EntryDisplayName.of("x", synthetic(choice)));
    }

    /**
     * An instantiation entry renders as the application it closes, from its own {@code source} -- which is
     * what §8.2 keys its identity on, so it is exactly what the author wrote at the site.
     */
    @Test
    void anInstantiationEntryRendersAsTheApplication() {
        TypeRef application = new TypeRef("paged", List.of(new TypeArgument.Ref(TypeRef.of("order")),
                new TypeArgument.Value(new Token("3", Token.Form.UNQUOTED))));

        assertEquals("paged<order, 3>",
                EntryDisplayName.of("paged_order_3_abc", synthetic(new RecordBody(List.of(), List.of(), List.of()), application)));
    }

    /** A construction's source is a bare constructor name, which is not an application and renders as the body. */
    @Test
    void aConstructionIsNotAnApplication() {
        assertEquals("[order]", EntryDisplayName.of("x",
                synthetic(array(Optional.empty(), Optional.empty()), TypeRef.of("array"))));
    }

    /** No sugar spelling and no application: the name, honestly, rather than something invented. */
    @Test
    void anythingElseFallsBackToTheName() {
        assertEquals("mystery", EntryDisplayName.of("mystery", synthetic(new RecordBody(List.of(), List.of(), List.of()))));
    }
}
