package io.ltr8.tson;

import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.meta.Completion;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TypeChain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @meta.completion} ([TSON-SCHEMA] §8.1): derived on the key of a declared completion, naming the incomplete
 * entries it closed, nearest first and past alias hops; absent in place and on a refinement; refused where written;
 * and read as stated when a resolved form is read.
 */
class CompletionAnnotationTest {

    private static final String LIB = "https://example.test/open-lib.tn";

    private static final Map<String, String> DOCUMENTS = Map.of(LIB, """
            !!id:"https://example.test/open-lib.tn"
            !!import:"https://tson.io/2026/40/m/core.tn"
            !schema {
              nonempty => !meta.array ^ { min_items: 1 }
            }
            """);

    private static Tson tson() {
        return Tson.of(ProcessorConfig.defaults().withSchemaAccess(SchemaAccess.of(SchemaSource.ofMap(DOCUMENTS))));
    }

    private static TsonLinkedSchema resolved(String body) {
        return tson().resolve("""
                !!id:"https://example.test/completions.tn"
                !!import:"https://example.test/open-lib.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                """ + body + "\n}\n");
    }

    private static Optional<List<String>> closed(TsonLinkedSchema schema, String name) {
        return schema.schema().completionOf(name).map(Completion::of);
    }

    @Test
    void eachSpellingOfADeclaredCompletionStatesWhatItClosedNearestFirst() {
        TsonLinkedSchema schema = resolved("""
                  capped     => !nonempty ^ { max_items: 5 }
                  braced     => !capped { element_type: text }
                  positional => !capped text
                  applied    => capped<text>""");
        for (String name : List.of("braced", "positional", "applied")) {
            assertEquals(Optional.of(List.of("capped", "nonempty")), closed(schema, name), name);
        }
    }

    @Test
    void anAliasHopIsNotListed() {
        TsonLinkedSchema schema = resolved("""
                  open_list => nonempty
                  tags      => open_list<text>
                  labels    => set<text>
                  status    => !enum [OPEN DONE]""");
        assertEquals(Optional.of(List.of("nonempty")), closed(schema, "tags"));
        assertEquals(Optional.of(List.of("meta.set_type")), closed(schema, "labels"));
        assertEquals(Optional.of(List.of("enum")), closed(schema, "status"));
    }

    @Test
    void noneIsStatedInPlaceOnARefinementOrOnAConstruction() {
        TsonLinkedSchema schema = resolved("""
                  tags     => nonempty<text>
                  few_tags => !tags ^ { max_items: 3 }
                  holder   => { a: nonempty<text> }
                  written  => !meta.array { element_type: text  min_items: 1 }""");
        assertEquals(Optional.of(List.of("nonempty")), closed(schema, "tags"));
        assertEquals(Optional.empty(), closed(schema, "few_tags"));
        assertEquals(Optional.empty(), closed(schema, "holder"));
        assertEquals(Optional.empty(), closed(schema, "written"));
        assertEquals(schema.schema().entries().get("written"),
                TypeChain.withoutChain(schema.schema().entries().get("tags")),
                "the value is untouched: a completion equals the construction written directly");
        assertTrue(schema.schema().entries().get("holder") instanceof RecordBody);
    }

    @Test
    void anAuthorWrittenCompletionIsRefused() {
        List<Diagnostic> problems = tson().validateSchema("""
                !!id:"https://example.test/written.tn"
                !!import:"https://tson.io/2026/40/m/core.tn"
                !schema {
                  @meta.completion:{ of: [enum] }
                  status => !enum [OPEN DONE]
                }
                """);
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.getFirst().message().contains("'@meta.completion' is written"), problems::toString);
    }

    /** A resolved form stating one is read under the kernel's {@code meta.completion}, and a malformed one refused. */
    @Test
    void aResolvedFormStatingOneReadsAsStated() {
        Tson tson = Tson.of(ProcessorConfig.defaults().withDataBindContext(SchemaModelNameBinder.defaultContext()));
        String form = """
                !schema {
                  @meta.completion:{ of: [enum] }
                  status => !meta.enum_type { name_type: meta.identifier  members: [OPEN DONE] }
                }
                """;
        assertEquals(List.of(), ResolvedForm.validateResolved(tson, form));
        assertEquals(List.of("enum"), ResolvedForm.markedCompletions(form).get("status"));
        assertTrue(!ResolvedForm.validateResolved(tson, form.replace("of: [enum]", "of: []")).isEmpty(),
                "a completion closes at least one entry");
    }
}
