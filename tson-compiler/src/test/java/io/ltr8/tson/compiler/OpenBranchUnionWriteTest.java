package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.union.Badge;
import io.ltr8.tson.compiler.union.Dot;
import io.ltr8.tson.compiler.union.Holder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code TsonObjectWriter.writeUnion} over a sealed union with a <b>non-sealed branch</b>.
 *
 * <p>An implementation of that branch is a member of the union in every sense that matters, but is not one
 * by exact class -- the member standing for it is the branch itself, since its implementations are unknown
 * when the union is analysed. Everything the writer does after the membership test is already generic over
 * whatever class comes back (the member's own descriptor, its own {@code @Typename}), so admitting the
 * implementation is the whole of what this needs.
 */
class OpenBranchUnionWriteTest {

    private static String written(Object value) {
        return new TsonObjectWriter().toTson(value).replaceAll("\\s+", " ").trim();
    }

    /** The closed leaf was never in question -- it is a member by exact class. */
    @Test
    void aClosedLeafWritesUnderItsOwnTypeName() {
        assertEquals("{ shape: !dot { x: 1 y: 2 } }", written(new Holder(new Dot(1, 2))));
    }

    /**
     * The regression: this threw {@code "value of type Badge is not a member of union interface Shape"}.
     * It writes as <em>itself</em> -- {@code !badge}, with its own component -- not as the branch it
     * arrived under, which has no components at all.
     */
    @Test
    void anImplementationOfTheOpenBranchWritesUnderItsOwnTypeName() {
        assertEquals("{ shape: !badge { label: \"gold\" } }", written(new Holder(new Badge("gold"))));
    }

    /** Two writes through one writer: the first registers the member, the second finds it already there. */
    @Test
    void writingTheSameImplementationTwiceIsStable() {
        TsonObjectWriter writer = new TsonObjectWriter();
        String first = writer.toTson(new Holder(new Badge("gold"))).replaceAll("\\s+", " ").trim();
        String second = writer.toTson(new Holder(new Badge("gold"))).replaceAll("\\s+", " ").trim();

        assertEquals(first, second);
    }
}
