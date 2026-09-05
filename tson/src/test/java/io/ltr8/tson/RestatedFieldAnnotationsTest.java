package io.ltr8.tson;

import io.ltr8.annotation.Annotation;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [TSON-SCHEMA] §5.7/§5.8: what happens to a field's annotations when a refinement or composition body
 * restates it.
 *
 * <p><b>They merge over, restatement first.</b> An inherited field is absorbed whole and keeps everything;
 * a restated one keeps its own annotations and the inherited ones after them. The rule is one rule for both
 * bodies, since one resolver path serves them.
 *
 * <p>The spelling that makes the rule necessary is §5.7's modifier-only entry: {@code legacy_id: = _} names
 * no type, tightens presence and nothing else, and has no annotation position at all -- so an entry that
 * mentions nothing must not be able to erase what it does not mention.
 *
 * <p>Concatenation rather than replacement by name because [TSON-DATA] §3.1 makes a name repeatable on one
 * value with all occurrences preserved; restatement first because order is what every first-occurrence
 * lookup reads as precedence.
 *
 * <p><b>The ordering half has no read-side witness, and that is a gap in the tests rather than in the
 * rule.</b> No annotation the meta layer declares changes how a value reads -- everything that decides a
 * spelling belongs to the type, the alphabet a {@code bytes} value is written in included ({@code
 * bytes_type.encoding}, [TSON-SCHEMA] §5.5) -- so ordering can only be asserted over resolved output until
 * some annotation carries read-side force. §5.8 gives the rule read-side force wherever one does.
 */
class RestatedFieldAnnotationsTest {

    private static final String ID = "https://example.test/restated-annotations.tn";

    private static final String SCHEMA = """
            !!id:"%s"
            !!meta:"https://tson.io/2026/35/m/meta.tn"
            !!import:"https://tson.io/2026/35/m/core.tn"
            {
              account => {
                @doc:"Pre-2020 registry identifier."
                @deprecated
                legacy_id: text?
                @doc:"Display name."
                name: text
              }

              premium_account => account & {
                legacy_id: = _
              }

              archived_account => account & {
                @todo:"drop in v3"
                legacy_id: = _
              }

              documented_account => account & {
                @doc:"Never issued after the 2020 migration."
                legacy_id: = _
              }

              refined_account => account ^ {
                legacy_id: = _
              }
            }
            """.formatted(ID);

    private static RecordField field(String type, String name) {
        TsonLinkedSchema linked = Tson.builder().build().resolve(SCHEMA);
        RecordBody body = (RecordBody) linked.schema().entries().get(type).body();
        return body.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static List<String> names(String type, String field) {
        return field(type, field).annotations().values().stream().map(Annotation::name).toList();
    }

    /** The source of every case below: two annotations on the field the subtypes restate, one on the field they do not. */
    @Test
    void theSourceFieldCarriesWhatTheAuthorWrote() {
        assertEquals(List.of("doc", "deprecated"), names("account", "legacy_id"));
        assertEquals(List.of("doc"), names("account", "name"));
    }

    /**
     * The case the rule exists for. A modifier-only entry has nowhere to write an annotation, so a
     * restatement that writes none keeps every inherited one -- where it used to keep none at all.
     */
    @Test
    void aModifierOnlyRestatementKeepsEveryInheritedAnnotation() {
        assertEquals(List.of("doc", "deprecated"), names("premium_account", "legacy_id"));
    }

    /** An inherited field is absorbed whole and was never the failing half; it stays that way. */
    @Test
    void anInheritedFieldKeepsItsAnnotations() {
        assertEquals(List.of("doc"), names("premium_account", "name"));
    }

    /** Merge, not replace: the restatement's own leads and the inherited ones follow. */
    @Test
    void aRestatementsOwnAnnotationsLeadTheInheritedOnes() {
        assertEquals(List.of("todo", "doc", "deprecated"), names("archived_account", "legacy_id"));
    }

    /**
     * A repeated name is kept twice, per [TSON-DATA] §3.1, and the restatement's is first -- so every
     * first-occurrence lookup reads the nearer declaration.
     */
    @Test
    void aRepeatedNameIsKeptTwiceWithTheNearerOneFirst() {
        assertEquals(List.of("doc", "doc", "deprecated"), names("documented_account", "legacy_id"));
        assertEquals(Optional.of("Never issued after the 2020 migration."),
                field("documented_account", "legacy_id").annotations().value("doc", String.class));
    }

    /** One rule for both bodies: §5.7's refinement gets the same answer as §5.8's composition. */
    @Test
    void refinementMergesTheSameWayComposalDoes() {
        assertEquals(List.of("doc", "deprecated"), names("refined_account", "legacy_id"));
    }

    /** Nothing is removable: every inherited annotation survives every restatement above. */
    @Test
    void noRestatementDropsAnInheritedAnnotation() {
        for (String subtype : List.of("premium_account", "archived_account", "documented_account", "refined_account")) {
            List<String> annotations = names(subtype, "legacy_id");
            assertTrue(annotations.contains("doc"), subtype + " lost @doc");
            assertTrue(annotations.contains("deprecated"), subtype + " lost @deprecated");
        }
    }
}
