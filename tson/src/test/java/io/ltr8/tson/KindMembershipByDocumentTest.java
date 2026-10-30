package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a name at a kind-typed position is judged ([TSON-SCHEMA] §4.2, §8.1). A data document refers into its imports'
 * closure and nowhere else, so the name is a member of the kind there or refused, whichever schema it imports. A schema
 * document's names resolve in that schema, which the reading closure does not hold: a resolved form read as data reads
 * the name's grammar, and membership is left to the schema's link, or to ingest.
 */
class KindMembershipByDocumentTest {

    private static List<Diagnostic> validate(String document) {
        return Tson.standard().validate(document);
    }

    @Test
    void aDataDocumentUnderMetaJudgesMembershipInMetasClosure() {
        assertEquals(List.of(), validate("""
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !record_field { type: integer }
                """));

        List<Diagnostic> problems = validate("""
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !record_field { type: int32 }
                """);
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("'int32' is not a member"), problems::toString);
    }

    @Test
    void theSameNameIsAMemberWhereTheDocumentImportsItsDeclaration() {
        assertEquals(List.of(), validate("""
                !!import:"https://tson.io/2026/39/m/meta.tn"
                !!import:"https://tson.io/2026/39/m/core.tn"
                !record_field { type: int32 }
                """));
    }

    /** A value of the kernel's {@code schema} names its own entries, which no closure the read holds declares. */
    @Test
    void aResolvedFormReadsItsOwnNamesByGrammar() {
        List<Diagnostic> problems = new ArrayList<>();
        Tson.standard().treeReader().withImports(List.of(TsonBundledSchemas.META_ID)).withDiagnostics(problems::add)
                .readAs("""
                        !schema {
                          point => !record { fields: { x => !record_field { type: coordinate } } }
                          coordinate => !alias { target: integer }
                        }
                        """, "schema");

        assertEquals(List.of(), problems);
    }
}
