package io.ltr8.tson.schema;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.VoidType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link TsonLinkedSchema#bindingCandidates}: which entries a binder is asked for a type a schema names, and in what
 * order, and the identity each is asked within.
 */
class BindingCandidatesTest {

    private static final String ID = "https://example.test/candidates.tn";

    private static final String IDENTITY = "example.test/candidates.tn";

    private record At(int line) implements SourcePosition {

        @Override
        public int column() {
            return 1;
        }

        @Override
        public int byteOffset() {
            return 0;
        }
    }

    private static Alias aliasOf(String target) {
        return new Alias(TypeRef.of(target));
    }

    /** {@code entries} in declaration order, those named in {@code declared} carrying a source position. */
    private static TsonLinkedSchema linked(Map<String, Top> entries, List<String> declared) {
        Map<String, TsonSchema.EntryFacts> facts = new LinkedHashMap<>();
        declared.forEach(name -> facts.put(name, new TsonSchema.EntryFacts(Optional.of(new At(facts.size() + 1)),
                Annotations.empty(), Optional.empty())));
        return new TsonLinkedSchema(new TsonSchema(ID, List.of(), AnnotatedMap.of(entries), false, Map.of(), facts));
    }

    private static Map<String, Top> chain() {
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("far", aliasOf("near"));
        entries.put("t", new VoidType());
        entries.put("near", aliasOf("t"));
        entries.put("other", aliasOf("t"));
        entries.put("u", new VoidType());
        return entries;
    }

    private static List<EntryKey> keys(String... names) {
        return List.of(names).stream().map(name -> new EntryKey(IDENTITY, name)).toList();
    }

    @Test
    void aDeclaredEntryIsAskedUnderItsOwnNameAloneThroughEveryAliasOfIt() {
        TsonLinkedSchema linked = linked(chain(), List.of("far", "t", "near", "other", "u"));

        assertEquals(keys("t"), linked.bindingCandidates("t"));
        assertEquals(keys("t"), linked.bindingCandidates("near"));
        assertEquals(keys("t"), linked.bindingCandidates("far"));
    }

    /** Every name whose chain ends at a positionless entry means it (§8.3), two hops as one, in declaration order. */
    @Test
    void aPositionlessEntryIsAskedUnderEveryNameReachingItThenItsOwn() {
        TsonLinkedSchema linked = linked(chain(), List.of());

        assertEquals(keys("far", "near", "other", "t"), linked.bindingCandidates("t"));
        assertEquals(keys("far", "near", "other", "t"), linked.bindingCandidates("far"));
        assertEquals(keys("u"), linked.bindingCandidates("u"));
    }

    @Test
    void aNameThatIsNoEntryIsAskedAsItStands() {
        assertEquals(keys("[text]"), linked(chain(), List.of()).bindingCandidates("[text]"));
    }

    @Test
    void anImportedEntryIsAskedInTheIdentityOfTheSchemaDeclaringIt() {
        Map<String, Top> entries = new LinkedHashMap<>(chain());
        TsonLinkedSchema linked = new TsonLinkedSchema(new TsonSchema(ID, List.of(), AnnotatedMap.of(entries), false,
                Map.of(), Map.of()), Map.of("u", "example.test/lib.tn"));

        assertEquals(List.of(new EntryKey("example.test/lib.tn", "u")), linked.bindingCandidates("u"));
    }

    /** An extern is the entry it lands on, asked in the namespace holding it, by its canonical identity (§5.13). */
    @Test
    void anExternIsAskedAsTheEntryItLandsOnByItsCanonicalIdentity() {
        Map<String, Top> foreignEntries = new LinkedHashMap<>();
        foreignEntries.put("x", new VoidType());
        TsonLinkedSchema foreign = new TsonLinkedSchema(new TsonSchema("https://example.test/foreign.tn", List.of(),
                AnnotatedMap.of(foreignEntries), false, Map.of(), Map.of()));
        Map<String, Top> entries = new LinkedHashMap<>();
        entries.put("y", new Extern("https://example.test/foreign.tn", "x"));
        entries.put("z", aliasOf("y"));
        TsonLinkedSchema linked = new TsonLinkedSchema(new TsonSchema(ID, List.of(), AnnotatedMap.of(entries), false,
                Map.of(), Map.of()), Map.of(), Map.of(), Map.of(), Map.of("y", new ExternTarget(foreign, "x")));

        assertEquals("example.test/foreign.tn", linked.landing("y").orElseThrow().declaringSchema());
        assertEquals(List.of(new EntryKey("example.test/foreign.tn", "x")), linked.bindingCandidates("z"));
    }

    @Test
    void anEntryKeyIsTheEntryAtTheEndOfTheChainInItsDeclaringSchema() {
        Map<String, Top> foreignEntries = new LinkedHashMap<>();
        foreignEntries.put("x", new VoidType());
        TsonLinkedSchema foreign = new TsonLinkedSchema(new TsonSchema("https://example.test/foreign.tn", List.of(),
                AnnotatedMap.of(foreignEntries), false, Map.of(), Map.of()));
        Map<String, Top> entries = new LinkedHashMap<>(chain());
        entries.put("y", new Extern("https://example.test/foreign.tn", "x"));
        entries.put("z", aliasOf("y"));
        entries.put("lost", aliasOf("nowhere"));
        TsonLinkedSchema linked = new TsonLinkedSchema(new TsonSchema(ID, List.of(), AnnotatedMap.of(entries), false,
                Map.of(), Map.of()), Map.of("u", "example.test/lib.tn"), Map.of(), Map.of(),
                Map.of("y", new ExternTarget(foreign, "x")));

        assertEquals(Optional.of(new EntryKey(IDENTITY, "t")), linked.entryKey("far"));
        assertEquals(Optional.of(new EntryKey("example.test/lib.tn", "u")), linked.entryKey("u"));
        assertEquals(Optional.of(new EntryKey("example.test/foreign.tn", "x")), linked.entryKey("z"));
        assertEquals(Optional.empty(), linked.entryKey("lost"));
        assertEquals(Optional.empty(), linked.entryKey("[text]"));
    }

    @Test
    void theTerminalIsTheEntryAtTheEndOfTheAliasHops() {
        TsonLinkedSchema linked = linked(chain(), List.of());

        assertEquals("t", linked.terminal("far"));
        assertEquals("u", linked.terminal("u"));
        assertEquals("nowhere", linked.terminal("nowhere"));
    }
}
