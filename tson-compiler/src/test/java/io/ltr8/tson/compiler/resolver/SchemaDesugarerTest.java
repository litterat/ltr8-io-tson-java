package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.schema.TsonBundledSchemas;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SchemaDesugarer}: the AST rewrite that expands the sugar forms before resolution.
 *
 * <p>At this stage the rewrite is deliberately an <b>identity transform</b> -- the walk is complete but
 * expands nothing -- because the walk's completeness is the entire risk of the phase and it can be proven
 * before any semantics move. The rewrite surface is easy to under-cover: fourteen distinct positions admit a
 * {@code TypeRef}, several only reachable through a nested container or a type argument, and the grammar
 * nests them without bound in both directions ({@code [map<text, text>]} and {@code map<text, [int32]>} both
 * parse).
 *
 * <p>Every assertion here is {@link org.junit.jupiter.api.Assertions#assertSame}, not {@code assertEquals},
 * and that is the point. The nodes are records, so an equal-but-rebuilt tree would satisfy {@code equals}
 * while having silently dropped every entry in {@code TsonSchemaParser.declarationPositions()} -- an
 * {@code IdentityHashMap}, so a rebuilt {@code Declaration} no longer matches its own position. Reference
 * equality is what actually proves the structural sharing that keeps positions intact, and it is what will
 * keep later stages honest: once expansion lands, only the declarations that genuinely contain sugar may
 * come back rebuilt.
 */
class SchemaDesugarerTest {

    private static SchemaDocument parse(String source) {
        return new TsonSchemaParser(source).parseSchemaDocument();
    }

    private static SchemaDocument parseBundled(String id) {
        return parse(TsonBundledSchemas.fetch(id));
    }

    @Test
    void returnsTheRealBundledSchemasUntouched() {
        // The three schemas that must survive this phase byte for byte -- between them they exercise
        // constructors, templates, refinements, compositions, field groups, every container form, and the
        // array sugar the later stages will start expanding.
        for (String id : new String[] {TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID,
                TsonBundledSchemas.CORE_ID}) {
            SchemaDocument document = parseBundled(id);
            assertSame(document, SchemaDesugarer.desugar(document), id);
        }
    }

    @Test
    void returnsEveryTypeRefBearingPositionUntouched() {
        // One declaration per position the walk visits, so a missed branch shows up as a rebuild rather than
        // going unnoticed: reference type-def, refinement source and body, composition supertypes and body,
        // field type, group member type, type argument, choice variant, inline array, inline tuple,
        // declaration-level array with a size spec, declaration-level tuple, and a nested container.
        SchemaDocument document = parse("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  alias_of        => integer
                  refined         => integer ^ { count: integer }
                  composed        => base & other & { extra: text }
                  with_fields     => { plain: text  optional_field: text? }
                  with_group      => { ( a: text | b: integer ) }
                  with_generic    => { m: map<text, integer> }
                  with_choice     => { c: (text | integer) }
                  with_inline     => { xs: [text]  pair: [text, integer] }
                  nested_arg      => { deep: map<text, [integer]> }
                  nested_sugar    => { deeper: [map<text, integer>] }
                  decl_array      => [text; 2..8]
                  decl_tuple      => [text, integer]
                  decl_nested     => [[text; 3]; 1..4]
                  templated       => <T> { v: T }
                  constructed     => ~product & { f: text }
                }""");

        assertSame(document, SchemaDesugarer.desugar(document));
    }

    @Test
    void returnsAnInstanceAndAtomRefinementUntouched() {
        // Both carry their target as a bare String and their payload as a DataValue, so no sugar can occur
        // inside them and the walk passes them through by reference rather than descending.
        SchemaDocument document = parse("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  an_instance   => !enum [ up down ]
                  a_refinement  => !integer ^ { min: 0  max: 10 }
                  annotated     => @doc:"has annotations" { f: text }
                }""");

        assertSame(document, SchemaDesugarer.desugar(document));
    }
}
