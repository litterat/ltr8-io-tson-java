package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.RecordStart;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DefaultTsonReadContext.lookingAhead} -- reading further than {@link TsonReadContext#peek()} reaches
 * and then putting back everything read, so the readers that follow see a stream nothing has touched.
 *
 * <p>The rewind is the whole contract, and what depends on it is not obvious from the call site: a reader
 * builds a value out of the events it is handed, so a lookahead that kept what it read would silently drop
 * that part of the document from the result rather than fail.
 */
class ReadContextLookaheadTest {

    private static final String DOCUMENT = """
            @doc:"why"
            @owner:{ team: "platform" }
            !api { name: "orders" }""";

    /** Everything the cursor yields from here to the end, as class names -- what a reader would see. */
    private static List<String> drain(TsonReadContext ctx) {
        List<String> seen = new ArrayList<>();
        while (!(ctx.peek() instanceof io.ltr8.tson.compiler.stream.DocumentEnd)) {
            seen.add(ctx.next().getClass().getSimpleName());
        }
        return seen;
    }

    @Test
    void whatALookaheadReadsIsPutBackInOrder() {
        TsonReadContext ctx = TestDocuments.document(DOCUMENT);
        List<String> untouched = drain(TestDocuments.document(DOCUMENT));

        String found = DefaultTsonReadContext.lookingAhead(ctx, lookahead -> {
            for (int i = 0; i < 6; i++) { // an arbitrary distance: the rewind is not told how far it went
                lookahead.next();
            }
            return "looked";
        });

        assertEquals("looked", found);
        assertEquals(untouched, drain(ctx), "the stream a reader sees is the one it would have seen");
    }

    /** The point of it: a caller can decide on something no single peek can reach. */
    @Test
    void aLookaheadReachesPastAWholeAnnotationRun() {
        TsonReadContext ctx = TestDocuments.document(DOCUMENT);

        assertInstanceOf(AnnotationStart.class, ctx.peek(), "one peek finds only the first annotation");
        assertEquals("api", RootTypeRef.find(ctx).orElseThrow());
        assertInstanceOf(AnnotationStart.class, ctx.peek(), "and the annotations are still there afterwards");
    }

    /** A run with nothing behind it answers so, rather than reading past the value it was looking at. */
    @Test
    void aRootWithNoTypeRefIsReportedAsSuchWithoutConsumingIt() {
        TsonReadContext ctx = TestDocuments.document("""
                @doc:"why"
                { name: "orders" }""");

        assertTrue(RootTypeRef.find(ctx).isEmpty());
        assertInstanceOf(AnnotationStart.class, ctx.peek());
    }

    /**
     * A nested lookahead rewinds into the enclosing one rather than past it. The failure this guards is a
     * second copy: an inner rewind that also handed its events to the outer recording would put them back
     * twice, and the events would then be read twice.
     */
    @Test
    void aNestedLookaheadPutsBackExactlyOneCopy() {
        TsonReadContext ctx = TestDocuments.document(DOCUMENT);
        List<String> untouched = drain(TestDocuments.document(DOCUMENT));

        DefaultTsonReadContext.lookingAhead(ctx, outer -> {
            outer.next(); // into the first annotation
            return DefaultTsonReadContext.lookingAhead(outer, inner -> inner.next());
        });

        assertEquals(untouched, drain(ctx));
    }

    /** A lookahead that throws still rewinds -- the caller reports and reads on from where it was. */
    @Test
    void aFailedLookaheadRewindsAnyway() {
        TsonReadContext ctx = TestDocuments.document(DOCUMENT);
        List<String> untouched = drain(TestDocuments.document(DOCUMENT));

        assertThrows(IllegalStateException.class, () -> DefaultTsonReadContext.lookingAhead(ctx, lookahead -> {
            lookahead.next();
            throw new IllegalStateException("boom");
        }));

        assertEquals(untouched, drain(ctx));
    }

    /**
     * {@link TsonReadContext#position()} is left where the lookahead reached rather than restored: a caller
     * looks ahead in order to say something about what it found, and a diagnostic about the type-ref belongs
     * at the type-ref.
     */
    @Test
    void thePositionFollowsWhatTheLookaheadFound() {
        TsonReadContext ctx = TestDocuments.document(DOCUMENT);

        RootTypeRef.find(ctx);

        assertEquals(3, ctx.position().orElseThrow().line(), "the !api line, not the first annotation's");
    }

    /** Rewound events are the same objects, so nothing is re-lexed and no position is recomputed. */
    @Test
    void rewindingReplaysRatherThanReparses() {
        TsonReadContext ctx = TestDocuments.document(DOCUMENT);

        TsonEvent first = DefaultTsonReadContext.lookingAhead(ctx, TsonReadContext::next);

        assertEquals(first, ctx.next());
    }

    /** And the type-ref a rewound stream yields is still the one the lookahead reported. */
    @Test
    void theRewoundStreamStillCarriesTheTypeRef() {
        TsonReadContext ctx = TestDocuments.document(DOCUMENT);

        String name = RootTypeRef.find(ctx).orElseThrow();
        while (!(ctx.peek() instanceof TypeRef)) {
            ctx.next();
        }

        assertEquals(name, ((TypeRef) ctx.next()).name());
        assertInstanceOf(RecordStart.class, ctx.peek());
    }
}
