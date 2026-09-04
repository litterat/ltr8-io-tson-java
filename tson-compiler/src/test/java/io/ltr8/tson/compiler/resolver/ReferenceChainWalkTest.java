package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReferenceChain}'s three stopping rules, which four passes used to decide for themselves.
 *
 * <p>The walk exists because resolved output states the chain the author wrote: nothing rewrites a use site
 * past a reference, so anything that wants the type at the end walks there. What that walk stops at is one
 * decision, and these are the cases that make it one -- an argument-bearing target especially, since it looks
 * like a hop and is an application.
 */
class ReferenceChainWalkTest {

    private static final Map<String, TypeDefinition> ENTRIES = entries();

    private static Map<String, TypeDefinition> entries() {
        Map<String, TypeDefinition> map = new LinkedHashMap<>();
        map.put("text", TypeDefinition.product(TextType.UNCONSTRAINED));
        map.put("hop", reference(TypeRef.of("text")));
        map.put("alias", reference(TypeRef.of("hop")));
        map.put("applied", reference(new TypeRef("box", List.of(new TypeArgument.Ref(TypeRef.of("text"))))));
        map.put("loop_a", reference(TypeRef.of("loop_b")));
        map.put("loop_b", reference(TypeRef.of("loop_a")));
        return map;
    }

    private static TypeDefinition reference(TypeRef target) {
        return new TypeDefinition(Optional.of(target), TypeKind.REFERENCE, List.of(), 
                List.of(), List.of(), Optional.empty(), new Reference(target));
    }

    /** The ordinary case, and the one that has to be transitive: two hops reach the type at the end. */
    @Test
    void aChainWalksToTheTypeAtItsEnd() {
        assertEquals("text", ReferenceChain.terminal("alias", ENTRIES));
        assertEquals("text", ReferenceChain.terminal("hop", ENTRIES));
        assertEquals("text", ReferenceChain.terminal("text", ENTRIES), "a name that starts no chain is its own end");
    }

    /**
     * <b>An argument-bearing target is an application, not a hop.</b> There is no entry at the end of one
     * until materialisation mints it, so the walk stops <em>at</em> the entry holding the application rather
     * than following it into a template.
     */
    @Test
    void anArgumentBearingTargetStopsTheWalk() {
        assertEquals("applied", ReferenceChain.terminal("applied", ENTRIES));
        assertTrue(ReferenceChain.terminalDefinition("applied", ENTRIES).isPresent(),
                "it stopped on an entry, so there is one to hand back");
    }

    /**
     * A name the namespace does not declare is its own answer -- to the linker it is a type parameter, which
     * {@code validateTypeRef} has already accepted or reported.
     */
    @Test
    void anUndeclaredNameIsItsOwnTerminal() {
        assertEquals("nowhere", ReferenceChain.terminal("nowhere", ENTRIES));
        assertFalse(ReferenceChain.terminalDefinition("nowhere", ENTRIES).isPresent());
    }

    /**
     * A cycle stops at the name it re-enters rather than spinning. {@code terminal} answers with that name
     * -- which depends on where the walk began, so no two starts falsely agree -- and {@code
     * terminalDefinition} answers with nothing, having been asked for a type and reached none.
     */
    @Test
    void aCycleStopsAndYieldsNoType() {
        assertEquals("loop_a", ReferenceChain.terminal("loop_a", ENTRIES));
        assertEquals("loop_b", ReferenceChain.terminal("loop_b", ENTRIES));
        assertFalse(ReferenceChain.terminalDefinition("loop_a", ENTRIES).isPresent());
    }

    /** The two entry points agree wherever the walk reaches a type. */
    @Test
    void theTwoEntryPointsAgree() {
        assertEquals(ENTRIES.get("text"), ReferenceChain.terminalDefinition("alias", ENTRIES).orElseThrow());
    }
}
