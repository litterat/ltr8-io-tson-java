package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.TsonCompiledVocabularyRegistry;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.meta.Top;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which declaration not held may be the member a value's pins select ([TSON-SCHEMA] §3.4.1, §5.2), judged from what
 * the declarations write: the walk through their operands, and the nearest pin written along it.
 */
class FamilyJoinersTest {

    private static final String LIB = """
            !!id:"https://example.test/family.tn"
            !!import:"https://tson.io/2026/40/m/meta.tn"
            !schema {
              route => { kind: text =?  path: text }
              get   => route & { kind?: = "get" }
            }
            """;

    /** The library's entries, ready: the base and a member that pins {@code get}. */
    private static final Map<String, Top> READY = ready();

    private static Map<String, Top> ready() {
        TsonCompiledVocabularyRegistry core = TsonCompiledVocabularyRegistry.withStandardLibrary(
                SchemaModelNameBinder.defaultContext(), SchemaSource.ofMap(Map.of("https://example.test/family.tn", LIB)));
        return new LinkedHashMap<>(core.resolveLinked("https://example.test/family.tn").schema().entries().entries());
    }

    private static Map<String, SchemaMap.Declaration> declarations(String body) {
        return SchemaDesugarer.desugar(new TsonSchemaParser("!!id:\"https://example.test/doc.tn\"\n"
                + "!!import:\"https://example.test/family.tn\"\n!schema {\n" + body + "}\n").parseSchemaDocument())
                .body().declarations();
    }

    /** Each declaration that may be the {@code route} member whose {@code kind} is {@code pin}, none held. */
    private static List<String> joiners(String body, String pin) {
        BiPredicate<String, String> samePin = (selector, written) -> selector.equals("kind") && written.equals(pin);
        return FamilyJoiners.of("route", Set.of("kind"), samePin, declarations(body), READY, name -> true);
    }

    @Test
    void aDeclarationComposingTheBaseAndPinningTheValueJoins() {
        assertEquals(List.of("put"), joiners("  put => route & { kind?: = \"put\" }\n", "put"));
    }

    @Test
    void aDifferingPinIsNeverWaitedOn() {
        assertEquals(List.of(), joiners("  post => route & { kind?: = \"post\" }\n", "put"));
    }

    @Test
    void anOperandReachesTheBaseThroughADeclarationNotYetReady() {
        assertEquals(List.of("mid", "leaf"), joiners("""
                  mid  => route & { note?: text }
                  leaf => mid & { kind?: = "put" }
                """, "put"));
    }

    /** A declaration writing no pin on its walk may be the member as well as one whose pin matches. */
    @Test
    void aDeclarationWritingNoPinMayBeTheMember() {
        assertEquals(List.of("loose", "put"), joiners("""
                  loose => route & { note?: text }
                  put   => route & { kind?: = "put" }
                  post  => route & { kind?: = "post" }
                """, "put"));
    }

    @Test
    void aReadyMemberIsAnOperandAndItsPinIsInherited() {
        assertEquals(List.of("deeper"), joiners("  deeper => get & { note?: text }\n", "get"));
        assertEquals(List.of(), joiners("  deeper => get & { note?: text }\n", "put"));
    }

    @Test
    void theNearestPinIsTheDeclarationsOwnFirst() {
        String body = """
                  mid  => route & { kind?: = "mid" }
                  leaf => mid & { kind?: = "leaf" }
                """;
        assertEquals(List.of("leaf"), joiners(body, "leaf"));
        assertEquals(List.of("mid"), joiners(body, "mid"));
    }

    @Test
    void aRefinementSourceIsAnOperand() {
        assertEquals(List.of("narrow"), joiners("  narrow => get ^ { path?: = \"/\" }\n", "get"));
    }

    @Test
    void anAliasTargetIsAnOperand() {
        String body = """
                  also => put
                  put  => route & { kind?: = "put" }
                """;
        assertEquals(List.of("also", "put"), joiners(body, "put"));
        assertEquals(List.of(), joiners(body, "post"));
    }

    /** An open record is no member, being no type; an application closing it is one. */
    @Test
    void anApplicationsHeadIsAnOperand() {
        String body = """
                  box   => <T> route & { kind?: = "box"  v: T }
                  boxed => box<text>
                """;
        assertEquals(List.of("boxed"), joiners(body, "box"));
        assertEquals(List.of(), joiners(body, "put"));
    }

    @Test
    void aSubtractionJoinsNothing() {
        assertEquals(List.of(), joiners("  cut => route - { path }\n", "put"));
    }

    /** A declaration the readers hold is no joiner: it is a member they have, or none. */
    @Test
    void onlyADeclarationNotHeldJoins() {
        assertEquals(List.of(), FamilyJoiners.of("route", Set.of("kind"), (selector, pin) -> true,
                declarations("  put => route & { kind?: = \"put\" }\n"), READY, name -> false));
    }

    /** With no selectors every declaration that may be IS-A the base joins. */
    @Test
    void withNoSelectorsEveryDeclarationThatMayBeASubtypeJoins() {
        assertEquals(List.of("loose"), FamilyJoiners.of("route", Set.of(), (selector, pin) -> true,
                declarations("  other => { a: text }\n  loose => route & { note?: text }\n"), READY, name -> true));
    }
}
