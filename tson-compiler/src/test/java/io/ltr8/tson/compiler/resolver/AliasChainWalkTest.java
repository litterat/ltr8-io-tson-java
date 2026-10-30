package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AliasChain}'s three stopping rules, which four passes used to decide for themselves.
 *
 * <p>The walk exists because resolved output states the chain the author wrote: nothing rewrites a use site
 * past a reference, so anything that wants the type at the end walks there. What that walk stops at is one
 * decision, and these are the cases that make it one -- an argument-bearing target especially, since it looks
 * like a hop and is an application.
 */
class AliasChainWalkTest {

    private static final Map<String, Top> ENTRIES = entries();

    private static Map<String, Top> entries() {
        Map<String, Top> map = new LinkedHashMap<>();
        map.put("text", TextType.UNCONSTRAINED);
        map.put("hop", alias(TypeRef.of("text")));
        map.put("alias", alias(TypeRef.of("hop")));
        map.put("applied", alias(new TypeRef("box", List.of(new TypeArgument.Ref(TypeRef.of("text"))))));
        map.put("loop_a", alias(TypeRef.of("loop_b")));
        map.put("loop_b", alias(TypeRef.of("loop_a")));
        return map;
    }

    private static Top alias(TypeRef target) {
        return new Alias(target);
    }

    /** The ordinary case, and the one that has to be transitive: two hops reach the type at the end. */
    @Test
    void aChainWalksToTheTypeAtItsEnd() {
        assertEquals("text", AliasChain.terminal("alias", ENTRIES));
        assertEquals("text", AliasChain.terminal("hop", ENTRIES));
        assertEquals("text", AliasChain.terminal("text", ENTRIES), "a name that starts no chain is its own end");
    }

    /**
     * <b>An argument-bearing target is an application, not a hop.</b> There is no entry at the end of one
     * until materialisation mints it, so the walk stops <em>at</em> the entry holding the application rather
     * than following it into a template.
     */
    @Test
    void anArgumentBearingTargetStopsTheWalk() {
        assertEquals("applied", AliasChain.terminal("applied", ENTRIES));
        assertTrue(AliasChain.terminalDefinition("applied", ENTRIES).isPresent(),
                "it stopped on an entry, so there is one to hand back");
    }

    /**
     * A name the namespace does not declare is its own answer -- to the linker it is a type parameter, which
     * {@code validateTypeRef} has already accepted or reported.
     */
    @Test
    void anUndeclaredNameIsItsOwnTerminal() {
        assertEquals("nowhere", AliasChain.terminal("nowhere", ENTRIES));
        assertFalse(AliasChain.terminalDefinition("nowhere", ENTRIES).isPresent());
    }

    /**
     * A cycle stops at the name it re-enters rather than spinning. {@code terminal} answers with that name
     * -- which depends on where the walk began, so no two starts falsely agree -- and {@code
     * terminalDefinition} answers with nothing, having been asked for a type and reached none.
     */
    @Test
    void aCycleStopsAndYieldsNoType() {
        assertEquals("loop_a", AliasChain.terminal("loop_a", ENTRIES));
        assertEquals("loop_b", AliasChain.terminal("loop_b", ENTRIES));
        assertFalse(AliasChain.terminalDefinition("loop_a", ENTRIES).isPresent());
    }

    /** The two entry points agree wherever the walk reaches a type. */
    @Test
    void theTwoEntryPointsAgree() {
        assertEquals(ENTRIES.get("text"), AliasChain.terminalDefinition("alias", ENTRIES).orElseThrow());
    }
}
