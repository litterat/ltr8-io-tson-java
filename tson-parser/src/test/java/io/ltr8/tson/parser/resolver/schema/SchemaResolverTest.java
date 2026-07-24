package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.parser.mapper.TsonMapperWriter;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes resolved values through plain {@code TsonMapperWriter.toTson} -- no hand-written
 * schema-model writer at all -- deliberately, to validate the {@code io.ltr8.tson.schema.meta}
 * model is built from ordinary, idiomatic Java (records, sealed interfaces, enums, {@code
 * Optional}) that {@code tson-bind}'s generic introspection already knows how to bind, rather than
 * a shape that happens to work only because a bespoke writer papered over it.
 *
 * <p>What this confirms works with zero extra code: {@code TypeBody}'s sealed-interface variants
 * each get their own {@code !record}/{@code !reference}/{@code !unit}/{@code !enum}/{@code
 * !choice}/{@code !array}/{@code !map}/{@code !tuple} type-ref purely from {@code
 * DataClassUnion} auto-detection plus a {@code @Typename} on each variant -- exactly the "body:
 * top" polymorphism the kernel itself describes. {@code BigInteger} fields, {@code
 * Optional}-wrapped scalar/record fields, and nested records all bind and round-trip correctly too.
 *
 * <p>What it also surfaces, honestly: generic binding produces output that is structurally
 * equivalent to, but textually more verbose than, {@code meta-kernel-resolved.tn1}'s own
 * hand-authored style -- no outer {@code !type_definition} tag (plain records, unlike union
 * members, never self-announce a type-ref), quoted strings where the fixture writes bare tokens
 * (an enum's bridge produces a {@code String}, and {@code TsonMapperWriter} always quotes strings --
 * already true, and already documented, for every other enum this codebase binds), every
 * empty-list/false/{@code REQUIRED}-at-default field written out rather than omitted ({@code
 * Optional.empty()}/{@code null} are the only things generic binding omits), and {@code TypeRef}
 * always in its full {@code { name: ... arguments: [...] } } form, never Part 2 §5.6's positional
 * bare-token spelling (a schema-specific encoding convention plain {@code tson-parser.mapper}, a
 * Part-1-only binder, has no reason to know about). None of these are wrong -- same value, just a
 * different spelling -- so the assertions below check the real, current {@code toTson} output
 * exactly, not a hand-massaged approximation of the fixture's own terser conventions.
 */
class SchemaResolverTest {

    private static final String EXPECTED_INTEGER_SIZE =
            "{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                    + "body: !record { supertypes: [] fields: [ "
                    + "{ name: \"bits\" type: { name: \"integer\" arguments: [] } state: \"REQUIRED\" } "
                    + "{ name: \"signed\" type: { name: \"boolean\" arguments: [] } state: \"REQUIRED\" } "
                    + "] groups: [] } }";

    private final SchemaResolver resolver = new SchemaResolver();
    private final TsonMapperWriter mapper = new TsonMapperWriter();

    private String write(TypeDefinition value) throws DataBindException {
        return mapper.toTson(value);
    }

    // ── SchemaResolver: the one construct it resolves so far ──────────────

    @Test
    void resolvesAFreshRecordWithPlainRequiredFields() throws DataBindException {
        SchemaDocument doc = new SchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { integer_size => { bits: integer  signed: boolean } }""").parseSchemaDocument();
        SchemaMap.Declaration declaration = doc.body().declarations().get("integer_size");

        TypeDefinition resolved = resolver.resolve(declaration);

        assertEquals(EXPECTED_INTEGER_SIZE, write(resolved));
    }

    @Test
    void resolvesIntegerSizeFromTheRealMetaKernelFixture() throws IOException, DataBindException {
        SchemaDocument doc = new SchemaParser(readFixture()).parseSchemaDocument();
        SchemaMap.Declaration declaration = doc.body().declarations().get("integer_size");

        TypeDefinition resolved = resolver.resolve(declaration);

        assertEquals(EXPECTED_INTEGER_SIZE, write(resolved));
    }

    @Test
    void resolveAllResolvesEveryDeclarationInSourceOrder() throws DataBindException {
        SchemaDocument doc = new SchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  integer_size => { bits: integer  signed: boolean }
                  point => { x: integer  y: integer }
                }""").parseSchemaDocument();

        TsonSchema schema = resolver.resolveAll(doc);

        assertEquals(2, schema.entries().size());
        assertEquals(EXPECTED_INTEGER_SIZE, write(schema.entries().get("integer_size")));
    }

    // ── TypeBody variants SchemaResolver doesn't produce yet: hand-built,
    //    checked against the real toTson output (see class Javadoc for why it
    //    diverges, structurally faithfully, from meta-kernel-resolved.tn1's own text) ──

    @Test
    void writesAUnitBody() throws DataBindException {
        // Structurally: value => !type_definition { kind: ATOM source: unit body: !unit {} }
        TypeDefinition value = new TypeDefinition(Optional.of(TypeRef.of("unit")), TypeKind.ATOM, List.of(), false,
                List.of(), List.of(), Optional.empty(), new Unit());

        assertEquals("{ source: { name: \"unit\" arguments: [] } kind: \"ATOM\" parameters: [] constructor: false "
                + "supertypes: [] subtypes: [] body: !unit {} }", write(value));
    }

    @Test
    void writesAnEnumBody() throws DataBindException {
        // Structurally: boolean => !type_definition { kind: ATOM source: enum body: !enum { members: [true false] } }
        TypeDefinition booleanDef = new TypeDefinition(Optional.of(TypeRef.of("enum")), TypeKind.ATOM, List.of(),
                false, List.of(), List.of(), Optional.empty(), new EnumBody(List.of("true", "false")));

        assertEquals("{ source: { name: \"enum\" arguments: [] } kind: \"ATOM\" parameters: [] constructor: false "
                        + "supertypes: [] subtypes: [] body: !enum { members: [ \"true\" \"false\" ] } }",
                write(booleanDef));
    }

    @Test
    void writesAChoiceBody() throws DataBindException {
        // No user choice type is declared in meta-kernel itself; this checks binding mechanics only.
        TypeDefinition choice = TypeDefinition.product(
                new ChoiceBody(List.of(TypeRef.of("email"), TypeRef.of("phone"))));

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !choice { variants: [ { name: \"email\" arguments: [] } { name: \"phone\" arguments: [] } ] } }",
                write(choice));
    }

    @Test
    void writesAnArrayBody() throws DataBindException {
        TypeDefinition intList = TypeDefinition.product(ArrayBody.of(TypeRef.of("integer")));

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !array { element_type: { name: \"integer\" arguments: [] } state: \"REQUIRED\" "
                        + "unordered: false unique_items: false } }",
                write(intList));
    }

    @Test
    void writesAMapBody() throws DataBindException {
        TypeDefinition translations = TypeDefinition.product(MapBody.of(TypeRef.of("text"), TypeRef.of("text")));

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !map { key_type: { name: \"text\" arguments: [] } value_type: { name: \"text\" arguments: [] } } }",
                write(translations));
    }

    @Test
    void writesATupleBody() throws DataBindException {
        TypeDefinition point = TypeDefinition.product(new TupleBody(List.of(
                TupleElement.required(TypeRef.of("number")), TupleElement.required(TypeRef.of("number")))));

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !tuple { elements: [ "
                        + "{ element_type: { name: \"number\" arguments: [] } state: \"REQUIRED\" } "
                        + "{ element_type: { name: \"number\" arguments: [] } state: \"REQUIRED\" } ] } }",
                write(point));
    }

    // ── Composition (§5.8): top, atom, product, sum, reference ────────────
    //    All five compose with (or, for top, are) the kernel's own base kinds --
    //    resolved straight from the real fixture, in the dependency order the
    //    schema map itself declares them, since forward references aren't
    //    supported yet (see SchemaResolver's own Javadoc).

    @Test
    void resolvesTopAsAFreshEmptyRecord() throws IOException, DataBindException {
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();

        TypeDefinition top = resolver.resolve(schemaMap.declarations().get("top"));

        assertEquals(TypeKind.PRODUCT, top.kind());
        assertEquals(List.of(), top.supertypes());
        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                + "body: !record { supertypes: [] fields: [] groups: [] } }", write(top));
    }

    @Test
    void resolvesAtomProductSumAndReferenceByComposingWithTop() throws IOException, DataBindException {
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));

        TypeDefinition atom = resolver.resolve(schemaMap.declarations().get("atom"), resolved);
        resolved.put("atom", atom);
        TypeDefinition product = resolver.resolve(schemaMap.declarations().get("product"), resolved);
        resolved.put("product", product);
        TypeDefinition sum = resolver.resolve(schemaMap.declarations().get("sum"), resolved);
        resolved.put("sum", sum);
        TypeDefinition reference = resolver.resolve(schemaMap.declarations().get("reference"), resolved);
        resolved.put("reference", reference);

        // §4.1: atom/product/sum are each kind PRODUCT -- their own transitive chain is just
        // [top], which contains none of the three literal base-kind names, so the structural
        // default applies even to the base kinds' own entries.
        assertEquals(TypeKind.PRODUCT, atom.kind());
        assertEquals(TypeKind.PRODUCT, product.kind());
        assertEquals(TypeKind.PRODUCT, sum.kind());
        assertEquals(TypeKind.PRODUCT, reference.kind());
        assertEquals(List.of("top"), atom.supertypes());
        assertEquals(List.of("top"), product.supertypes());
        assertEquals(List.of("top"), sum.supertypes());
        assertEquals(List.of("top"), reference.supertypes());

        // atom, sum: empty trailing body, no fields inherited from top (which has none) -- just the composition itself.
        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [ \"top\" ] subtypes: [] "
                + "body: !record { supertypes: [ \"top\" ] fields: [] groups: [] } }", write(atom));
        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [ \"top\" ] subtypes: [] "
                + "body: !record { supertypes: [ \"top\" ] fields: [] groups: [] } }", write(sum));

        // product: two brand-new fields added by the trailing body (top contributes none).
        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [ \"top\" ] subtypes: [] "
                + "body: !record { supertypes: [ \"top\" ] fields: [ "
                + "{ name: \"access_pattern\" type: { name: \"product_access_type\" arguments: [] } state: \"REQUIRED\" } "
                + "{ name: \"size_type\" type: { name: \"product_size_type\" arguments: [] } state: \"REQUIRED\" } "
                + "] groups: [] } }", write(product));

        // reference: one brand-new field.
        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [ \"top\" ] subtypes: [] "
                + "body: !record { supertypes: [ \"top\" ] fields: [ "
                + "{ name: \"target\" type: { name: \"type_name\" arguments: [] } state: \"REQUIRED\" } "
                + "] groups: [] } }", write(reference));
    }

    // ── Field groups (§5.11) + constructor flag + OPTIONAL fields: integer_type ──

    @Test
    void resolvesIntegerTypeWithFieldGroupsAndOptionalFields() throws IOException, DataBindException {
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom"), resolved));

        TypeDefinition integerType = resolver.resolve(schemaMap.declarations().get("integer_type"), resolved);

        // ~atom & {...} -- constructor: true propagates straight from the "~" marker; kind: ATOM
        // because "atom" (the literal base-kind name) is in integer_type's own transitive chain.
        assertTrue(integerType.constructor());
        assertEquals(TypeKind.ATOM, integerType.kind());
        assertEquals(List.of("atom", "top"), integerType.supertypes());

        assertEquals("{ kind: \"ATOM\" parameters: [] constructor: true supertypes: [ \"atom\" \"top\" ] subtypes: [] "
                        + "body: !record { supertypes: [ \"atom\" ] fields: [ "
                        + "{ name: \"size\" type: { name: \"integer_size\" arguments: [] } state: \"OPTIONAL\" } "
                        + "{ name: \"min\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "{ name: \"exclusive_min\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "{ name: \"max\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "{ name: \"exclusive_max\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "{ name: \"multiple_of\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } ] "
                        + "groups: [ "
                        + "{ members: [ \"min\" \"exclusive_min\" ] state: \"OPTIONAL\" } "
                        + "{ members: [ \"max\" \"exclusive_max\" ] state: \"OPTIONAL\" } "
                        + "] } }",
                write(integerType));
    }

    // ── Bare type references (§8.3): type_name, field_name, param_name, and
    //    the annotation markers -- all resolve to a REFERENCE-kind entry
    //    regardless of what the referenced name itself resolves to.

    @Test
    void resolvesBareTypeReferencesToAReferenceKindEntry() throws IOException, DataBindException {
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();

        TypeDefinition typeName = resolver.resolve(schemaMap.declarations().get("type_name"));
        TypeDefinition fieldName = resolver.resolve(schemaMap.declarations().get("field_name"));
        TypeDefinition paramName = resolver.resolve(schemaMap.declarations().get("param_name"));
        TypeDefinition annotation = resolver.resolve(schemaMap.declarations().get("annotation"));
        TypeDefinition documentation = resolver.resolve(schemaMap.declarations().get("documentation"));
        TypeDefinition doc = resolver.resolve(schemaMap.declarations().get("doc"));
        TypeDefinition alias = resolver.resolve(schemaMap.declarations().get("alias"));

        // type_name/field_name/param_name => token; each is its own fresh REFERENCE entry, not
        // three views of the same one -- source/target both name "token" for all three.
        assertEquals(TypeKind.REFERENCE, typeName.kind());
        assertEquals(TypeKind.REFERENCE, fieldName.kind());
        assertEquals(TypeKind.REFERENCE, paramName.kind());
        assertEquals(TypeKind.REFERENCE, annotation.kind());
        assertEquals(TypeKind.REFERENCE, documentation.kind());
        assertEquals(TypeKind.REFERENCE, doc.kind());
        assertEquals(TypeKind.REFERENCE, alias.kind());

        assertEquals("{ source: { name: \"token\" arguments: [] } kind: \"REFERENCE\" parameters: [] "
                + "constructor: false supertypes: [] subtypes: [] "
                + "body: !reference { target: { name: \"token\" arguments: [] } } }", write(typeName));
        assertEquals(write(typeName), write(fieldName));
        assertEquals(write(typeName), write(paramName));

        // annotation => @annotation void -- the @annotation marker is metadata on the type-def
        // (SchemaMap.Declaration.typeDefAnnotations), not part of the TypeDef this resolves, so it
        // plays no role here; resolution is identical to any other bare reference.
        assertEquals("{ source: { name: \"void\" arguments: [] } kind: \"REFERENCE\" parameters: [] "
                + "constructor: false supertypes: [] subtypes: [] "
                + "body: !reference { target: { name: \"void\" arguments: [] } } }", write(annotation));

        // doc => @annotation documentation => @annotation text -- a chain of references, each
        // resolved independently (no following the chain here, just the immediate target).
        assertEquals("{ source: { name: \"text\" arguments: [] } kind: \"REFERENCE\" parameters: [] "
                + "constructor: false supertypes: [] subtypes: [] "
                + "body: !reference { target: { name: \"text\" arguments: [] } } }", write(documentation));
        assertEquals("{ source: { name: \"documentation\" arguments: [] } kind: \"REFERENCE\" parameters: [] "
                + "constructor: false supertypes: [] subtypes: [] "
                + "body: !reference { target: { name: \"documentation\" arguments: [] } } }", write(doc));

        // alias => @annotation text -- same shape as documentation (both target "text").
        assertEquals(write(documentation), write(alias));
    }

    // ── A field's inline array sugar [T] (§5.3): type_ref.arguments ───────

    @Test
    void resolvesAFieldsInlineArraySugarFromTheRealMetaKernelFixture() throws IOException, DataBindException {
        // type_ref => { name: type_name  arguments: [type_argument]? }
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();

        TypeDefinition typeRefDef = resolver.resolve(schemaMap.declarations().get("type_ref"));

        // "!ref"/"!value" wrapping each type_argument is a known toTson divergence from the
        // kernel's own tag-less field-group shape -- see TypeArgument's own Javadoc for why.
        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"name\" type: { name: \"type_name\" arguments: [] } state: \"REQUIRED\" } "
                        + "{ name: \"arguments\" type: { name: \"array\" "
                        + "arguments: [ !ref { ref: { name: \"type_argument\" arguments: [] } } ] } "
                        + "state: \"OPTIONAL\" } ] groups: [] } }",
                write(typeRefDef));
    }

    // ── Declaration-level sized-array sugar (§5.3, §5.10): array_min/array_max/array_ranged ──
    //    No real meta-kernel.tn1 declaration uses this sugar; these mirror §5.3's own worked
    //    examples (score_list/order_batch/matrix9) and §5.10's string_triple example directly.

    @Test
    void resolvesAtLeastNSugarToArrayMin() throws DataBindException {
        TypeDefinition scoreList = resolveSnippet("score_list => [integer; 1..]");

        assertEquals(TypeKind.REFERENCE, scoreList.kind());
        // "!ref"/"!value" wrapping each type_argument is a known toTson divergence -- see
        // TypeArgument's own Javadoc for why (mutually-recursive types + tson-bind's record
        // resolution has no cycle protection, only union resolution does).
        assertEquals("{ source: { name: \"array_min\" arguments: [ "
                        + "!ref { ref: { name: \"integer\" arguments: [] } } "
                        + "!value { value: { text: \"1\" form: \"UNQUOTED\" } } ] } "
                        + "kind: \"REFERENCE\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !reference { target: { name: \"array_min\" arguments: [ "
                        + "!ref { ref: { name: \"integer\" arguments: [] } } "
                        + "!value { value: { text: \"1\" form: \"UNQUOTED\" } } ] } } }",
                write(scoreList));
    }

    @Test
    void resolvesAtMostMSugarToArrayMax() throws DataBindException {
        TypeDefinition recent = resolveSnippet("recent => [text; ..5]");

        assertEquals("{ source: { name: \"array_max\" arguments: [ "
                        + "!ref { ref: { name: \"text\" arguments: [] } } "
                        + "!value { value: { text: \"5\" form: \"UNQUOTED\" } } ] } "
                        + "kind: \"REFERENCE\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !reference { target: { name: \"array_max\" arguments: [ "
                        + "!ref { ref: { name: \"text\" arguments: [] } } "
                        + "!value { value: { text: \"5\" form: \"UNQUOTED\" } } ] } } }",
                write(recent));
    }

    @Test
    void resolvesABoundedRangeToArrayRanged() throws DataBindException {
        TypeDefinition orderBatch = resolveSnippet("order_batch => [order; 1..100]");

        assertEquals("{ source: { name: \"array_ranged\" arguments: [ "
                        + "!ref { ref: { name: \"order\" arguments: [] } } "
                        + "!value { value: { text: \"1\" form: \"UNQUOTED\" } } "
                        + "!value { value: { text: \"100\" form: \"UNQUOTED\" } } ] } "
                        + "kind: \"REFERENCE\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !reference { target: { name: \"array_ranged\" arguments: [ "
                        + "!ref { ref: { name: \"order\" arguments: [] } } "
                        + "!value { value: { text: \"1\" form: \"UNQUOTED\" } } "
                        + "!value { value: { text: \"100\" form: \"UNQUOTED\" } } ] } } }",
                write(orderBatch));
    }

    @Test
    void resolvesAnExactSizeToArrayRangedWithTheSameBoundTwice() throws DataBindException {
        // matrix9 => [number; 9] -- "two spellings of the same application" as [number; 9..9] (§5.3).
        TypeDefinition matrix9 = resolveSnippet("matrix9 => [number; 9]");
        TypeDefinition stringTriple = resolveSnippet("string_triple => [text; 3..3]");

        assertEquals("{ source: { name: \"array_ranged\" arguments: [ "
                        + "!ref { ref: { name: \"number\" arguments: [] } } "
                        + "!value { value: { text: \"9\" form: \"UNQUOTED\" } } "
                        + "!value { value: { text: \"9\" form: \"UNQUOTED\" } } ] } "
                        + "kind: \"REFERENCE\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !reference { target: { name: \"array_ranged\" arguments: [ "
                        + "!ref { ref: { name: \"number\" arguments: [] } } "
                        + "!value { value: { text: \"9\" form: \"UNQUOTED\" } } "
                        + "!value { value: { text: \"9\" form: \"UNQUOTED\" } } ] } } }",
                write(matrix9));
        // §5.10's own worked example: array_ranged<text, 3, 3>, equivalently [text; 3].
        assertEquals("{ source: { name: \"array_ranged\" arguments: [ "
                        + "!ref { ref: { name: \"text\" arguments: [] } } "
                        + "!value { value: { text: \"3\" form: \"UNQUOTED\" } } "
                        + "!value { value: { text: \"3\" form: \"UNQUOTED\" } } ] } "
                        + "kind: \"REFERENCE\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !reference { target: { name: \"array_ranged\" arguments: [ "
                        + "!ref { ref: { name: \"text\" arguments: [] } } "
                        + "!value { value: { text: \"3\" form: \"UNQUOTED\" } } "
                        + "!value { value: { text: \"3\" form: \"UNQUOTED\" } } ] } } }",
                write(stringTriple));
    }

    @Test
    void rejectsASizeLessDeclarationLevelArrayAsAConstructorApplicationNotYetResolved() {
        assertThrows(UnsupportedOperationException.class, () -> resolveSnippet("id_list => [text]"));
    }

    // ── Top-level constructor application (§5.6): map<K, V> ───────────────
    //    schema => map<type_name, type_definition> -- a fully-bound application of the map
    //    constructor resolves as a construction (kind PRODUCT, no supertypes), not a reference.

    @Test
    void resolvesSchemasOwnMapApplicationFromTheRealMetaKernelFixture() throws IOException, DataBindException {
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();

        TypeDefinition schema = resolver.resolve(schemaMap.declarations().get("schema"));

        assertEquals(TypeKind.PRODUCT, schema.kind());
        assertEquals(List.of(), schema.supertypes());
        assertEquals("{ source: { name: \"map\" arguments: [ "
                        + "!ref { ref: { name: \"type_name\" arguments: [] } } "
                        + "!ref { ref: { name: \"type_definition\" arguments: [] } } ] } "
                        + "kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !map { key_type: { name: \"type_name\" arguments: [] } "
                        + "value_type: { name: \"type_definition\" arguments: [] } } }",
                write(schema));
    }

    // ── Type parameters (§5.10): a template's own <T, ...> list ───────────
    //    Threaded straight into TypeDefinition.parameters, with no substitution or
    //    validation that a field actually uses each parameter.

    @Test
    void resolvesAFreshRecordsTypeParameters() throws DataBindException {
        TypeDefinition pair = resolveSnippet("pair => <A, B> { first: A  second: B }");

        assertEquals(List.of("A", "B"), pair.parameters());
        assertEquals("{ kind: \"PRODUCT\" parameters: [ \"A\" \"B\" ] constructor: false supertypes: [] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"first\" type: { name: \"A\" arguments: [] } state: \"REQUIRED\" } "
                        + "{ name: \"second\" type: { name: \"B\" arguments: [] } state: \"REQUIRED\" } "
                        + "] groups: [] } }",
                write(pair));
    }

    @Test
    void resolvesACompositionsTypeParameters() throws DataBindException {
        SchemaMap schemaMap = new SchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  base => {}
                  box => <T> ~base & { value: T }
                }""").parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("base", resolver.resolve(schemaMap.declarations().get("base")));

        TypeDefinition box = resolver.resolve(schemaMap.declarations().get("box"), resolved);

        assertEquals(List.of("T"), box.parameters());
        assertTrue(box.constructor());
        assertEquals(List.of("base"), box.supertypes());
        assertEquals("{ kind: \"PRODUCT\" parameters: [ \"T\" ] constructor: true supertypes: [ \"base\" ] subtypes: [] "
                        + "body: !record { supertypes: [ \"base\" ] fields: [ "
                        + "{ name: \"value\" type: { name: \"T\" arguments: [] } state: \"REQUIRED\" } "
                        + "] groups: [] } }",
                write(box));
    }

    // ── Field modifiers (§5.2, §5.10): default (~) and fixed (=) values ───

    @Test
    void resolvesTupleElementFromTheRealMetaKernelFixture() throws IOException, DataBindException {
        // tuple_element => { element_type: type_ref  state: element_state ~ REQUIRED } -- a fresh
        // record (no supertypes, so no tightening involved), exercising an ordinary literal default.
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();

        TypeDefinition tupleElement = resolver.resolve(schemaMap.declarations().get("tuple_element"));

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"element_type\" type: { name: \"type_ref\" arguments: [] } state: \"REQUIRED\" } "
                        + "{ name: \"state\" type: { name: \"element_state\" arguments: [] } state: \"REQUIRED_DEFAULT\" "
                        + "value: { text: \"REQUIRED\" form: \"UNQUOTED\" } } "
                        + "] groups: [] } }",
                write(tupleElement));
    }

    @Test
    void resolvesFieldGroupFromTheRealMetaKernelFixture() throws IOException, DataBindException {
        // field_group => { members: [field_name]  state: element_state ~ REQUIRED } -- a fresh
        // record combining the inline array sugar with an ordinary literal default modifier.
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();

        TypeDefinition fieldGroup = resolver.resolve(schemaMap.declarations().get("field_group"));

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"members\" type: { name: \"array\" "
                        + "arguments: [ !ref { ref: { name: \"field_name\" arguments: [] } } ] } state: \"REQUIRED\" } "
                        + "{ name: \"state\" type: { name: \"element_state\" arguments: [] } state: \"REQUIRED_DEFAULT\" "
                        + "value: { text: \"REQUIRED\" form: \"UNQUOTED\" } } "
                        + "] groups: [] } }",
                write(fieldGroup));
    }

    @Test
    void resolvesAnOrdinaryLiteralFixedValue() throws DataBindException {
        // Mirrors array's own "access_pattern: product_access_type = INDEX" without the surrounding
        // composition, so it isn't also blocked by tightening -- an ordinary (non-parameter) fixed value.
        TypeDefinition pinned = resolveSnippet("pinned => { access_pattern: product_access_type = INDEX }");

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"access_pattern\" type: { name: \"product_access_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"INDEX\" form: \"UNQUOTED\" } } "
                        + "] groups: [] } }",
                write(pinned));
    }

    @Test
    void resolvesAParametricFixedValueAsAValueParamNotALiteral() throws DataBindException {
        // Mirrors array's own "element_type: type_ref = T" without the surrounding composition --
        // T is one of the declaration's own type parameters, so it's a parameter reference (routed,
        // not fixed): state stays at its unmarked REQUIRED, and the modifier's token is recorded as
        // value_param, not value.
        TypeDefinition sized = resolveSnippet("sized => <T> { value: type_ref = T }");

        assertEquals(List.of("T"), sized.parameters());
        assertEquals("{ kind: \"PRODUCT\" parameters: [ \"T\" ] constructor: false supertypes: [] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"value\" type: { name: \"type_ref\" arguments: [] } state: \"REQUIRED\" "
                        + "value_param: \"T\" } "
                        + "] groups: [] } }",
                write(sized));
    }

    @Test
    void resolvesAParametricDefaultValueAsAValueParamPromotedToRequiredDefault() throws DataBindException {
        // "~ P" (default routed by parameter) still promotes to REQUIRED_DEFAULT, identically to a
        // literal default (§5.10) -- only the value/value_param label differs.
        TypeDefinition retry = resolveSnippet("retry_policy => <N> { attempts: integer ~ N }");

        assertEquals("{ kind: \"PRODUCT\" parameters: [ \"N\" ] constructor: false supertypes: [] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"attempts\" type: { name: \"integer\" arguments: [] } state: \"REQUIRED_DEFAULT\" "
                        + "value_param: \"N\" } "
                        + "] groups: [] } }",
                write(retry));
    }

    // ── Tightening (§5.7), via composition bodies: array, map ─────────────
    //    array/map both compose with "product" and re-declare its access_pattern/size_type
    //    fields with fixed values -- a genuine tightening entry, replacing the inherited
    //    field in place rather than being rejected as a duplicate name.

    @Test
    void resolvesArrayFromTheRealMetaKernelFixtureTighteningProductsInheritedFields() throws IOException, DataBindException {
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom"), resolved));
        resolved.put("product", resolver.resolve(schemaMap.declarations().get("product"), resolved));

        TypeDefinition array = resolver.resolve(schemaMap.declarations().get("array"), resolved);

        assertEquals(TypeKind.PRODUCT, array.kind());
        assertEquals(List.of("T"), array.parameters());
        assertTrue(array.constructor());
        assertEquals(List.of("product", "top"), array.supertypes());
        assertEquals("{ kind: \"PRODUCT\" parameters: [ \"T\" ] constructor: true "
                        + "supertypes: [ \"product\" \"top\" ] subtypes: [] "
                        + "body: !record { supertypes: [ \"product\" ] fields: [ "
                        + "{ name: \"access_pattern\" type: { name: \"product_access_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"INDEX\" form: \"UNQUOTED\" } } "
                        + "{ name: \"size_type\" type: { name: \"product_size_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"VARIABLE\" form: \"UNQUOTED\" } } "
                        + "{ name: \"element_type\" type: { name: \"type_ref\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"T\" } "
                        + "{ name: \"state\" type: { name: \"element_state\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"REQUIRED\" form: \"UNQUOTED\" } } "
                        + "{ name: \"unordered\" type: { name: \"boolean\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"false\" form: \"UNQUOTED\" } } "
                        + "{ name: \"unique_items\" type: { name: \"boolean\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"false\" form: \"UNQUOTED\" } } "
                        + "{ name: \"min_items\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "{ name: \"max_items\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "] groups: [] } }",
                write(array));
    }

    @Test
    void resolvesMapFromTheRealMetaKernelFixtureTighteningProductsInheritedFields() throws IOException, DataBindException {
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom"), resolved));
        resolved.put("product", resolver.resolve(schemaMap.declarations().get("product"), resolved));

        TypeDefinition map = resolver.resolve(schemaMap.declarations().get("map"), resolved);

        assertEquals(TypeKind.PRODUCT, map.kind());
        assertEquals(List.of("K", "V"), map.parameters());
        assertTrue(map.constructor());
        assertEquals(List.of("product", "top"), map.supertypes());
        assertEquals("{ kind: \"PRODUCT\" parameters: [ \"K\" \"V\" ] constructor: true "
                        + "supertypes: [ \"product\" \"top\" ] subtypes: [] "
                        + "body: !record { supertypes: [ \"product\" ] fields: [ "
                        + "{ name: \"access_pattern\" type: { name: \"product_access_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"NAMED\" form: \"UNQUOTED\" } } "
                        + "{ name: \"size_type\" type: { name: \"product_size_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"VARIABLE\" form: \"UNQUOTED\" } } "
                        + "{ name: \"key_type\" type: { name: \"type_ref\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"K\" } "
                        + "{ name: \"value_type\" type: { name: \"type_ref\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"V\" } "
                        + "{ name: \"min_items\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "{ name: \"max_items\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "] groups: [] } }",
                write(map));
    }

    @Test
    void tighteningRejectsAnInvalidStateTransition() {
        // "count" is inherited REQUIRED; tightening it to OPTIONAL is not a permitted transition
        // (§5.7's table: REQUIRED -> OPTIONAL is an error).
        SchemaMap schemaMap = new SchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  base => { count: integer }
                  loosened => base & { count: integer? }
                }""").parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("base", resolver.resolve(schemaMap.declarations().get("base")));

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> resolver.resolve(schemaMap.declarations().get("loosened"), resolved));
        assertTrue(thrown.getMessage().contains("permitted"), thrown.getMessage());
    }

    @Test
    void resolvesAnElidedTypeRefInATighteningEntryByInheritingTheSourcesType() throws DataBindException {
        // "field: = value" with no type-ref restated inherits the source declaration's type
        // (§5.7's "Elided type-refs"), tightening only the value/state.
        SchemaMap schemaMap = new SchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  config => { host: text  port: integer }
                  production => config & { host: = "prod.example.com" }
                }""").parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("config", resolver.resolve(schemaMap.declarations().get("config")));

        TypeDefinition production = resolver.resolve(schemaMap.declarations().get("production"), resolved);

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [ \"config\" ] subtypes: [] "
                        + "body: !record { supertypes: [ \"config\" ] fields: [ "
                        + "{ name: \"host\" type: { name: \"text\" arguments: [] } state: \"REQUIRED_FIXED\" "
                        + "value: { text: \"prod.example.com\" form: \"SINGLE_LINE_QUOTED\" } } "
                        + "{ name: \"port\" type: { name: \"integer\" arguments: [] } state: \"REQUIRED\" } "
                        + "] groups: [] } }",
                write(production));
    }

    // ── The ^ refinement operator (§5.7): set, array_min, array_max, array_ranged ──
    //    A refinement re-emits the ENTIRE inherited field set (no new fields), tightening
    //    only the fields the body actually names -- verified end-to-end against the real
    //    fixture, resolving "array" first so each refinement's own source is visible.

    @Test
    void resolvesSetFromTheRealMetaKernelFixtureRefiningArray() throws IOException, DataBindException {
        // set => <T> ~array<T> ^ { state: = REQUIRED  unordered: = true  unique_items: = true } --
        // array's own state/unordered/unique_items were REQUIRED_DEFAULT; set's body fixes them,
        // an allowed REQUIRED_DEFAULT -> REQUIRED_FIXED transition (§5.7's table).
        Map<String, TypeDefinition> resolved = resolveUpToArray();

        TypeDefinition set = resolver.resolve(schemaMapFromFixture().declarations().get("set"), resolved);

        assertEquals(TypeKind.PRODUCT, set.kind());
        assertEquals(List.of("T"), set.parameters());
        assertTrue(set.constructor());
        assertEquals(List.of("array", "product", "top"), set.supertypes());
        assertEquals("{ source: { name: \"array\" arguments: [ !ref { ref: { name: \"T\" arguments: [] } } ] } "
                        + "kind: \"PRODUCT\" parameters: [ \"T\" ] constructor: true "
                        + "supertypes: [ \"array\" \"product\" \"top\" ] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"access_pattern\" type: { name: \"product_access_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"INDEX\" form: \"UNQUOTED\" } } "
                        + "{ name: \"size_type\" type: { name: \"product_size_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"VARIABLE\" form: \"UNQUOTED\" } } "
                        + "{ name: \"element_type\" type: { name: \"type_ref\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"T\" } "
                        + "{ name: \"state\" type: { name: \"element_state\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"REQUIRED\" form: \"UNQUOTED\" } } "
                        + "{ name: \"unordered\" type: { name: \"boolean\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"true\" form: \"UNQUOTED\" } } "
                        + "{ name: \"unique_items\" type: { name: \"boolean\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"true\" form: \"UNQUOTED\" } } "
                        + "{ name: \"min_items\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "{ name: \"max_items\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "] groups: [] } }",
                write(set));
    }

    @Test
    void resolvesArrayMinFromTheRealMetaKernelFixtureRoutingMinItemsByParameter() throws IOException, DataBindException {
        // array_min => <T, MIN> array<T> ^ { min_items: = MIN } -- array's own min_items was
        // OPTIONAL; MIN is array_min's own parameter, so this is an OPTIONAL -> REQUIRED
        // (value_param) tightening, not a literal fixed value.
        Map<String, TypeDefinition> resolved = resolveUpToArray();

        TypeDefinition arrayMin = resolver.resolve(schemaMapFromFixture().declarations().get("array_min"), resolved);

        assertEquals(List.of("T", "MIN"), arrayMin.parameters());
        assertFalse(arrayMin.constructor());
        assertEquals(List.of("array", "product", "top"), arrayMin.supertypes());
        assertEquals("{ source: { name: \"array\" arguments: [ !ref { ref: { name: \"T\" arguments: [] } } ] } "
                        + "kind: \"PRODUCT\" parameters: [ \"T\" \"MIN\" ] constructor: false "
                        + "supertypes: [ \"array\" \"product\" \"top\" ] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"access_pattern\" type: { name: \"product_access_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"INDEX\" form: \"UNQUOTED\" } } "
                        + "{ name: \"size_type\" type: { name: \"product_size_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"VARIABLE\" form: \"UNQUOTED\" } } "
                        + "{ name: \"element_type\" type: { name: \"type_ref\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"T\" } "
                        + "{ name: \"state\" type: { name: \"element_state\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"REQUIRED\" form: \"UNQUOTED\" } } "
                        + "{ name: \"unordered\" type: { name: \"boolean\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"false\" form: \"UNQUOTED\" } } "
                        + "{ name: \"unique_items\" type: { name: \"boolean\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"false\" form: \"UNQUOTED\" } } "
                        + "{ name: \"min_items\" type: { name: \"integer\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"MIN\" } "
                        + "{ name: \"max_items\" type: { name: \"integer\" arguments: [] } state: \"OPTIONAL\" } "
                        + "] groups: [] } }",
                write(arrayMin));
    }

    @Test
    void resolvesArrayRangedFromTheRealMetaKernelFixtureRoutingBothBoundsByParameter() throws IOException, DataBindException {
        // array_ranged => <T, MIN, MAX> array<T> ^ { min_items: = MIN  max_items: = MAX } -- both
        // OPTIONAL fields tighten to REQUIRED via parameter routing.
        Map<String, TypeDefinition> resolved = resolveUpToArray();

        TypeDefinition arrayRanged = resolver.resolve(schemaMapFromFixture().declarations().get("array_ranged"), resolved);

        assertEquals(List.of("T", "MIN", "MAX"), arrayRanged.parameters());
        assertEquals("{ source: { name: \"array\" arguments: [ !ref { ref: { name: \"T\" arguments: [] } } ] } "
                        + "kind: \"PRODUCT\" parameters: [ \"T\" \"MIN\" \"MAX\" ] constructor: false "
                        + "supertypes: [ \"array\" \"product\" \"top\" ] subtypes: [] "
                        + "body: !record { supertypes: [] fields: [ "
                        + "{ name: \"access_pattern\" type: { name: \"product_access_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"INDEX\" form: \"UNQUOTED\" } } "
                        + "{ name: \"size_type\" type: { name: \"product_size_type\" arguments: [] } "
                        + "state: \"REQUIRED_FIXED\" value: { text: \"VARIABLE\" form: \"UNQUOTED\" } } "
                        + "{ name: \"element_type\" type: { name: \"type_ref\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"T\" } "
                        + "{ name: \"state\" type: { name: \"element_state\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"REQUIRED\" form: \"UNQUOTED\" } } "
                        + "{ name: \"unordered\" type: { name: \"boolean\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"false\" form: \"UNQUOTED\" } } "
                        + "{ name: \"unique_items\" type: { name: \"boolean\" arguments: [] } "
                        + "state: \"REQUIRED_DEFAULT\" value: { text: \"false\" form: \"UNQUOTED\" } } "
                        + "{ name: \"min_items\" type: { name: \"integer\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"MIN\" } "
                        + "{ name: \"max_items\" type: { name: \"integer\" arguments: [] } "
                        + "state: \"REQUIRED\" value_param: \"MAX\" } "
                        + "] groups: [] } }",
                write(arrayRanged));
    }

    @Test
    void refinementRejectsABodyFieldThatAddsRatherThanTightens() throws IOException {
        // A refinement body field naming nothing inherited is a resolver error (§5.7: "adding
        // fields is a resolver error") -- distinct from composition, where a non-matching name is
        // simply a new field.
        SchemaMap schemaMap = new SchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  base => { count: integer }
                  refined => base ^ { extra: text }
                }""").parseSchemaDocument().body();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("base", resolver.resolve(schemaMap.declarations().get("base")));

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> resolver.resolve(schemaMap.declarations().get("refined"), resolved));
        assertTrue(thrown.getMessage().contains("adding a field"), thrown.getMessage());
    }

    // ── A field's generic type-ref (e.g. `set<token>`) ────────────────────

    @Test
    void resolvesEnumFromTheRealMetaKernelFixtureWithAGenericFieldType() throws IOException, DataBindException {
        // enum => ~atom & { members: set<token> } -- "members" is typed with a generic
        // application, not a bare reference or the [T] array sugar.
        SchemaMap schemaMap = schemaMapFromFixture();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom"), resolved));

        TypeDefinition enumDef = resolver.resolve(schemaMap.declarations().get("enum"), resolved);

        assertEquals(TypeKind.ATOM, enumDef.kind());
        assertTrue(enumDef.constructor());
        assertEquals(List.of("atom", "top"), enumDef.supertypes());
        assertEquals("{ kind: \"ATOM\" parameters: [] constructor: true supertypes: [ \"atom\" \"top\" ] subtypes: [] "
                        + "body: !record { supertypes: [ \"atom\" ] fields: [ "
                        + "{ name: \"members\" type: { name: \"set\" "
                        + "arguments: [ !ref { ref: { name: \"token\" arguments: [] } } ] } state: \"REQUIRED\" } "
                        + "] groups: [] } }",
                write(enumDef));
    }

    private Map<String, TypeDefinition> resolveUpToArray() throws IOException {
        SchemaMap schemaMap = schemaMapFromFixture();
        Map<String, TypeDefinition> resolved = new LinkedHashMap<>();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom"), resolved));
        resolved.put("product", resolver.resolve(schemaMap.declarations().get("product"), resolved));
        resolved.put("array", resolver.resolve(schemaMap.declarations().get("array"), resolved));
        return resolved;
    }

    private SchemaMap schemaMapFromFixture() throws IOException {
        return new SchemaParser(readFixture()).parseSchemaDocument().body();
    }

    private TypeDefinition resolveSnippet(String declaration) {
        SchemaMap schemaMap = new SchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { %s }""".formatted(declaration)).parseSchemaDocument().body();
        return resolver.resolve(schemaMap.declarations().values().iterator().next());
    }

    @Test
    void compositionRejectsAnUnresolvedSupertype() throws IOException {
        SchemaMap schemaMap = new SchemaParser(readFixture()).parseSchemaDocument().body();
        // "top" deliberately left out of the resolved map -- atom's supertype isn't visible yet.
        assertThrows(UnsupportedOperationException.class,
                () -> resolver.resolve(schemaMap.declarations().get("atom"), Map.of()));
    }

    private static String readFixture() throws IOException {
        return Files.readString(Path.of("").toAbsolutePath().resolve("../spec/m/meta-kernel.tn1").normalize());
    }
}
