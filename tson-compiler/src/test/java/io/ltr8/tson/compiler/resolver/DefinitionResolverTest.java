package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import io.ltr8.tson.compiler.TsonObjectWriter;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.BinaryType;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.Cidr4Type;
import io.ltr8.tson.schema.meta.Cidr6Type;
import io.ltr8.tson.schema.meta.ComplexType;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.Ipv4Type;
import io.ltr8.tson.schema.meta.Ipv6Type;
import io.ltr8.tson.schema.meta.MacType;
import io.ltr8.tson.schema.meta.IntegerSize;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UnknownType;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes resolved values through plain {@code TsonObjectWriter.toTson} -- no hand-written
 * schema-model writer at all -- deliberately, to validate the {@code io.ltr8.tson.schema.meta}
 * model is built from ordinary, idiomatic Java (records, sealed interfaces, enums, {@code
 * Optional}) that {@code tson-bind}'s generic introspection already knows how to bind, rather than
 * a shape that happens to work only because a bespoke writer papered over it.
 *
 * <p>What this confirms works with zero extra code: {@code Top}'s sealed-interface variants
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
 * (an enum's bridge produces a {@code String}, and {@code TsonObjectWriter} always quotes strings --
 * already true, and already documented, for every other enum this codebase binds), every
 * empty-list/false/{@code REQUIRED}-at-default field written out rather than omitted ({@code
 * Optional.empty()}/{@code null} are the only things generic binding omits), and {@code TypeRef}
 * always in its full {@code { name: ... arguments: [...] } } form, never Part 2 §5.6's positional
 * bare-token spelling (a schema-specific encoding convention plain {@code TsonObjectWriter}, a
 * Part-1-only binder, has no reason to know about). None of these are wrong -- same value, just a
 * different spelling -- so the assertions below check the real, current {@code toTson} output
 * exactly, not a hand-massaged approximation of the fixture's own terser conventions.
 */
class DefinitionResolverTest {

    private static final String EXPECTED_INTEGER_SIZE =
            "{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                    + "body: !record { supertypes: [] fields: [ "
                    + "{ name: \"bits\" type: { name: \"integer\" arguments: [] } state: \"REQUIRED\" } "
                    + "{ name: \"signed\" type: { name: \"boolean\" arguments: [] } state: \"REQUIRED\" } "
                    + "] groups: [] } }";

    /** Throws if ever actually invoked -- most tests below never reach {@code Instance}/{@code AtomRefinement} binding at all; the ones that do build their own {@link DefinitionResolver} wrapping a real compiled reader instead of using this field. */
    private static final DefinitionMetaReader NEVER_CALLED = (type, value) -> {
        throw new UnsupportedOperationException("'" + type + "': not exercised by this test");
    };

    /** A {@link DefinitionGetter} with nothing resolved yet -- for a resolver that only ever resolves declarations with no supertype/refinement-source lookup of their own. */
    private static final DefinitionGetter EMPTY_NAMESPACE = name -> null;

    /**
     * The type-name namespace {@link #resolver} resolves against, accumulated by individual test
     * methods via {@code resolved.put(...)} -- a plain instance field, not a per-test local variable,
     * specifically so {@link #resolver} (itself a field, constructed once per test) can be bound to
     * it at construction time via {@code resolved::get} (see {@link DefinitionGetter}'s own Javadoc)
     * and still see whatever a test method puts into it afterward. Safe because JUnit 5's default
     * {@code PER_METHOD} lifecycle gives every {@code @Test} method its own fresh instance of this
     * class, so this field starts empty at the top of every test.
     */
    private final Map<String, TypeDefinition> resolved = new LinkedHashMap<>();

    private final DefinitionResolver resolver = new DefinitionResolver(NEVER_CALLED, EMPTY_NAMESPACE, resolved::get);
    private final TsonObjectWriter mapper = new TsonObjectWriter();

    private static DefinitionResolver definitionResolverFor(TsonCompiledMetaSchema metaParser, DefinitionGetter definitionGetter) {
        return new DefinitionResolver((type, value) -> (Top) metaParser.reader(type)
                        .read(TsonReadContext.throwing(new ListEventSource(DataValueEvents.of(value)))),
                metaParser.schema().entries()::get, definitionGetter);
    }

    private String write(TypeDefinition value) throws DataBindException {
        return mapper.toTson(value);
    }

    // ── DefinitionResolver: the one construct it resolves so far ──────────────

    @Test
    void resolvesAFreshRecordWithPlainRequiredFields() throws DataBindException {
        SchemaDocument doc = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { integer_size => { bits: integer  signed: boolean } }""").parseSchemaDocument();
        SchemaMap.Declaration declaration = doc.body().declarations().get("integer_size");

        TypeDefinition resolved = resolver.resolve(declaration);

        assertEquals(EXPECTED_INTEGER_SIZE, write(resolved));
    }

    @Test
    void resolvesIntegerSizeFromTheRealMetaKernelFixture() throws IOException, DataBindException {
        SchemaDocument doc = new TsonSchemaParser(readFixture()).parseSchemaDocument();
        SchemaMap.Declaration declaration = doc.body().declarations().get("integer_size");

        TypeDefinition resolved = resolver.resolve(declaration);

        assertEquals(EXPECTED_INTEGER_SIZE, write(resolved));
    }

    @Test
    void resolveSchemaResolvesEveryDeclarationInSourceOrder() throws DataBindException {
        // DefinitionResolver has no resolveSchema(SchemaDocument) batch convenience of its own --
        // resolving a whole document, in source order, is this loop, matching
        // SchemaResolver#resolveSchema's own production loop.
        SchemaDocument doc = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  integer_size => { bits: integer  signed: boolean }
                  point => { x: integer  y: integer }
                }""").parseSchemaDocument();

        for (SchemaMap.Declaration declaration : doc.body().declarations().values()) {
            resolved.put(declaration.name(), resolver.resolve(declaration));
        }

        assertEquals(2, resolved.size());
        assertEquals(EXPECTED_INTEGER_SIZE, write(resolved.get("integer_size")));
    }

    /**
     * The structure namespace is fixed at a resolver's own construction (see {@code
     * DefinitionResolver}'s own "Fixed at construction, not threaded per call" note), not threaded
     * per call -- this just confirms that fixing it doesn't change anything about a construct this
     * class already resolves without ever consulting it ({@code Instance}/{@code AtomRefinement}
     * aren't dispatched by either declaration below): a resolver constructed with a (deliberately
     * irrelevant) non-empty structure namespace produces byte-for-byte the same result as one
     * constructed with {@code EMPTY_NAMESPACE} (the shared {@code resolver} field), for both a single
     * declaration and a whole document.
     */
    @Test
    void structureNamespaceOverloadsAreInertUntilInstanceAtomRefinementDispatchExists() throws DataBindException {
        SchemaDocument doc = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  integer_size => { bits: integer  signed: boolean }
                  point => { x: integer  y: integer }
                }""").parseSchemaDocument();
        SchemaMap.Declaration declaration = doc.body().declarations().get("integer_size");
        Map<String, TypeDefinition> irrelevantStructureNamespace =
                Map.of("unrelated", TypeDefinition.reference("token"));
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        DefinitionResolver resolverWithIrrelevantNamespace =
                new DefinitionResolver(NEVER_CALLED, irrelevantStructureNamespace::get, entries::get);

        TypeDefinition viaResolve = resolverWithIrrelevantNamespace.resolve(declaration);
        assertEquals(EXPECTED_INTEGER_SIZE, write(viaResolve));

        for (SchemaMap.Declaration decl : doc.body().declarations().values()) {
            entries.put(decl.name(), resolverWithIrrelevantNamespace.resolve(decl));
        }
        assertEquals(2, entries.size());
        assertEquals(EXPECTED_INTEGER_SIZE, write(entries.get("integer_size")));
    }

    // ── Top variants DefinitionResolver doesn't produce yet: hand-built,
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
    //    supported yet (see DefinitionResolver's own Javadoc).

    @Test
    void resolvesTopAsAFreshEmptyRecord() throws IOException, DataBindException {
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();

        TypeDefinition top = resolver.resolve(schemaMap.declarations().get("top"));

        assertEquals(TypeKind.PRODUCT, top.kind());
        assertEquals(List.of(), top.supertypes());
        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                + "body: !record { supertypes: [] fields: [] groups: [] } }", write(top));
    }

    @Test
    void resolvesAtomProductSumAndReferenceByComposingWithTop() throws IOException, DataBindException {
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));

        TypeDefinition atom = resolver.resolve(schemaMap.declarations().get("atom"));
        resolved.put("atom", atom);
        TypeDefinition product = resolver.resolve(schemaMap.declarations().get("product"));
        resolved.put("product", product);
        TypeDefinition sum = resolver.resolve(schemaMap.declarations().get("sum"));
        resolved.put("sum", sum);
        TypeDefinition reference = resolver.resolve(schemaMap.declarations().get("reference"));
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
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom")));

        TypeDefinition integerType = resolver.resolve(schemaMap.declarations().get("integer_type"));

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
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();

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

        // annotation => @annotation void -- the @annotation marker is written after "=>", so §6 binds it to
        // the definition and it is now carried on the resolved entry. It writes back as a wire annotation
        // ahead of the record (§7.4's `*annotation [type-ref] core-value`), which is how §8.1 represents one:
        // type_definition has no annotations field, and does not need one.
        assertEquals("@annotation { source: { name: \"void\" arguments: [] } kind: \"REFERENCE\" parameters: [] "
                + "constructor: false supertypes: [] subtypes: [] "
                + "body: !reference { target: { name: \"void\" arguments: [] } } }", write(annotation));

        // doc => @annotation documentation => @annotation text -- a chain of references, each
        // resolved independently (no following the chain here, just the immediate target).
        assertEquals("@annotation { source: { name: \"text\" arguments: [] } kind: \"REFERENCE\" parameters: [] "
                + "constructor: false supertypes: [] subtypes: [] "
                + "body: !reference { target: { name: \"text\" arguments: [] } } }", write(documentation));
        assertEquals("@annotation { source: { name: \"documentation\" arguments: [] } kind: \"REFERENCE\" parameters: [] "
                + "constructor: false supertypes: [] subtypes: [] "
                + "body: !reference { target: { name: \"documentation\" arguments: [] } } }", write(doc));

        // alias => @annotation text -- same shape as documentation (both target "text").
        assertEquals(write(documentation), write(alias));
    }

    // ── A field's inline array sugar [T] (§5.3): type_ref.arguments ───────

    @Test
    void resolvesAFieldsInlineArraySugarFromTheRealMetaKernelFixture() throws IOException, DataBindException {
        // type_ref => { name: type_name  arguments: [type_argument]? }
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();

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

    // ── Declaration-level sized-array sugar (§5.3, §8.2) ──
    //    SchemaDesugarer rewrites the spelling and routes the arguments (SchemaDesugarerTest owns both);
    //    what arrives here is a TemplateInstance, and what this covers is the entry §8.2 requires it to
    //    become. GenericApplicationHeadTest drives the same forms through the real meta.tn/core.tn chain.

    /**
     * §8.2's own worked example, one bound apart: {@code [pixel; 1920]} is {@code array_ranged<pixel, 1920,
     * 1920>}, materialising to a closed PRODUCT whose {@code source} is the flattened application, whose
     * supertypes are the template's unchanged, and whose body is headed at the nearest {@code ~} constructor.
     */
    @Test
    void resolvesASizedArrayToTheInstantiationEntryEightTwoSpecifies() {
        TypeDefinition frame = resolveSnippet("frame => [text; 1920]");

        assertEquals(TypeKind.PRODUCT, frame.kind());
        assertEquals(List.of(), frame.parameters());
        assertEquals(List.of("array", "product", "top"), frame.supertypes());
        assertEquals(new TypeRef("array_ranged", List.of(
                        new TypeArgument.Ref(TypeRef.of("text")),
                        new TypeArgument.Value(new Token("1920", Token.Form.UNQUOTED)),
                        new TypeArgument.Value(new Token("1920", Token.Form.UNQUOTED)))),
                frame.source().orElseThrow());

        ArrayBody body = assertInstanceOf(ArrayBody.class, frame.body());
        assertEquals(TypeRef.of("text"), body.elementType());
        assertEquals(Optional.of(new BigInteger("1920")), body.minItems());
        assertEquals(Optional.of(new BigInteger("1920")), body.maxItems());
    }

    /** The three spellings all land on the same shape -- only which bound each supplies differs (§5.3). */
    @Test
    void theOpenEndedSpellingsRouteToArrayMinAndArrayMax() {
        ArrayBody atLeast = assertInstanceOf(ArrayBody.class, resolveSnippet("score_list => [integer; 1..]").body());
        assertEquals(Optional.of(BigInteger.ONE), atLeast.minItems());
        assertEquals(Optional.empty(), atLeast.maxItems());

        ArrayBody atMost = assertInstanceOf(ArrayBody.class, resolveSnippet("recent => [text; ..5]").body());
        assertEquals(Optional.empty(), atMost.minItems());
        assertEquals(Optional.of(new BigInteger("5")), atMost.maxItems());
    }

    /**
     * A <em>size-less</em> declaration-level array takes the other path: {@code [text]} is a plain {@code
     * array<text>}, a constructor application, which §8.2 says never materialises an entry and §5.6 says
     * resolves in place as a construction. So it lands on the same {@code ArrayBody} as its sized sibling
     * but, per §5.5, carries no supertypes -- {@code id_list} is not IS-A {@code array} while {@code
     * [text; 1..2]} is. That asymmetry is the spec's, not this implementation's; see {@code
     * SPEC-FEEDBACK.md}.
     */
    @Test
    void aSizeLessDeclarationLevelArrayIsAConstructionWithNoSupertypes() {
        TypeDefinition idList = resolveSnippet("id_list => [text]");

        assertEquals(TypeKind.PRODUCT, idList.kind());
        assertEquals(List.of(), idList.supertypes());
        assertEquals(TypeRef.of("array"), idList.source().orElseThrow());
        assertEquals(TypeRef.of("text"), assertInstanceOf(ArrayBody.class, idList.body()).elementType());
    }

    // ── An application DefinitionResolver still sees (§5.10) ─────────────
    //    Every *constructor* application is rewritten into an `!C value` instance by SchemaDesugarer
    //    before resolution, so what reaches here still carrying arguments is a template application --
    //    resolved to a REFERENCE naming it, since §5.10 substitution is unimplemented. meta-kernel's own
    //    `schema => map<...>` is the one exception, and it is handled by MetaKernelBootstrapResolver,
    //    which bypasses SchemaResolver and so never desugars.

    @Test
    void resolvesAnUndesugaredApplicationToAReferenceNamingIt() throws IOException, DataBindException {
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();

        TypeDefinition schema = resolver.resolve(schemaMap.declarations().get("schema"));

        assertEquals(TypeKind.REFERENCE, schema.kind());
        assertEquals("{ source: { name: \"map\" arguments: [ "
                        + "!ref { ref: { name: \"type_name\" arguments: [] } } "
                        + "!ref { ref: { name: \"type_definition\" arguments: [] } } ] } "
                        + "kind: \"REFERENCE\" parameters: [] constructor: false supertypes: [] subtypes: [] "
                        + "body: !reference { target: { name: \"map\" arguments: [ "
                        + "!ref { ref: { name: \"type_name\" arguments: [] } } "
                        + "!ref { ref: { name: \"type_definition\" arguments: [] } } ] } } }",
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
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  base => {}
                  box => <T> ~base & { value: T }
                }""").parseSchemaDocument().body();
        resolved.put("base", resolver.resolve(schemaMap.declarations().get("base")));

        TypeDefinition box = resolver.resolve(schemaMap.declarations().get("box"));

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
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();

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
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();

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
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom")));
        resolved.put("product", resolver.resolve(schemaMap.declarations().get("product")));

        TypeDefinition array = resolver.resolve(schemaMap.declarations().get("array"));

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
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom")));
        resolved.put("product", resolver.resolve(schemaMap.declarations().get("product")));

        TypeDefinition map = resolver.resolve(schemaMap.declarations().get("map"));

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
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  base => { count: integer }
                  loosened => base & { count: integer? }
                }""").parseSchemaDocument().body();
        resolved.put("base", resolver.resolve(schemaMap.declarations().get("base")));

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolver.resolve(schemaMap.declarations().get("loosened")));
        assertTrue(thrown.getMessage().contains("can only restrict, never expand"), thrown.getMessage());
    }

    @Test
    void resolvesAnElidedTypeRefInATighteningEntryByInheritingTheSourcesType() throws DataBindException {
        // "field: = value" with no type-ref restated inherits the source declaration's type
        // (§5.7's "Elided type-refs"), tightening only the value/state.
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  config => { host: text  port: integer }
                  production => config & { host: = "prod.example.com" }
                }""").parseSchemaDocument().body();
        resolved.put("config", resolver.resolve(schemaMap.declarations().get("config")));

        TypeDefinition production = resolver.resolve(schemaMap.declarations().get("production"));

        assertEquals("{ kind: \"PRODUCT\" parameters: [] constructor: false supertypes: [ \"config\" ] subtypes: [] "
                        + "body: !record { supertypes: [ \"config\" ] fields: [ "
                        + "{ name: \"host\" type: { name: \"text\" arguments: [] } state: \"REQUIRED_FIXED\" "
                        + "value: { text: \"prod.example.com\" form: \"SINGLE_LINE_QUOTED\" } } "
                        + "{ name: \"port\" type: { name: \"integer\" arguments: [] } state: \"REQUIRED\" } "
                        + "] groups: [] } }",
                write(production));
    }

    /**
     * §5.7 makes the three places a modifier-only entry cannot stand <b>the author's</b> error, not a coverage
     * gap: "a modifier-only entry whose name matches no inherited field is a resolver error", and in a fresh
     * record "every field MUST have an explicit type-ref, and the resolver MUST reject modifier-only entries
     * there". So each is a {@link TsonSchemaValidationException} -- an {@code UnsupportedOperationException}
     * would tell an author their correct-but-rejected schema is this library's fault.
     */
    @Test
    void rejectsAModifierOnlyEntryWithNoInheritedFieldToTakeATypeFrom() {
        // (1) a fresh record: nothing to elide toward at all
        TsonSchemaValidationException fresh = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("config => { host: = \"localhost\" }"));
        assertTrue(fresh.getMessage().contains("'host'"), fresh.getMessage());
        assertTrue(fresh.getMessage().contains("§5.7"), fresh.getMessage());
        // the AST's own toString never reaches an author-facing message
        assertFalse(fresh.getMessage().contains("FieldDef["), fresh.getMessage());

        // (2) a composition body naming no inherited field: a source exists, but declares no such field
        TsonSchemaValidationException composed = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("""
                        config => { host: text }
                        production => config & { port: = 8080 }
                        """));
        assertTrue(composed.getMessage().contains("'port'"), composed.getMessage());
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
        resolveUpToArray();

        TypeDefinition set = resolver.resolve(schemaMapFromFixture().declarations().get("set"));

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
        resolveUpToArray();

        TypeDefinition arrayMin = resolver.resolve(schemaMapFromFixture().declarations().get("array_min"));

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
        resolveUpToArray();

        TypeDefinition arrayRanged = resolver.resolve(schemaMapFromFixture().declarations().get("array_ranged"));

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

    /**
     * A refinement body field naming nothing inherited is the author's error (§5.7: a refinement copies its
     * source's whole field set and admits no new fields) -- distinct from composition, where a non-matching
     * name is simply a new field. Hence a {@link TsonSchemaValidationException}: telling an author their
     * schema is unsupported, when it is in fact rejected by the spec, sends them looking for the wrong fix.
     */
    @Test
    void refinementRejectsABodyFieldThatAddsRatherThanTightens() throws IOException {
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  base => { count: integer }
                  refined => base ^ { extra: text }
                }""").parseSchemaDocument().body();
        resolved.put("base", resolver.resolve(schemaMap.declarations().get("base")));

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolver.resolve(schemaMap.declarations().get("refined")));
        assertTrue(thrown.getMessage().contains("'extra'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("admits no new fields"), thrown.getMessage());
        // the AST's own toString never reaches an author-facing message
        assertFalse(thrown.getMessage().contains("FieldDef["), thrown.getMessage());
    }

    // ── A field's generic type-ref (e.g. `set<token>`) ────────────────────

    @Test
    void resolvesEnumFromTheRealMetaKernelFixtureWithAGenericFieldType() throws IOException, DataBindException {
        // enum => ~atom & { members: set<token> } -- "members" is typed with a generic
        // application, not a bare reference or the [T] array sugar.
        SchemaMap schemaMap = schemaMapFromFixture();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom")));

        TypeDefinition enumDef = resolver.resolve(schemaMap.declarations().get("enum"));

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

    // ── Constructor application (§5.5, §5.6, Phase B step 4) ──────────────

    @Test
    void resolvesProductAccessTypeInstanceFromTheRealMetaKernelFixture() throws IOException {
        // product_access_type => !enum [INDEX NAMED] -- enum itself is declared *later* in the
        // real file, so it's resolved into `resolved` by hand here first (DefinitionResolver has no
        // forward-reference support of its own; MetaKernelBootstrapResolver's own two-pass ordering is what
        // handles that for the whole file -- see its class Javadoc).
        SchemaMap schemaMap = schemaMapFromFixture();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom")));
        resolved.put("enum", resolver.resolve(schemaMap.declarations().get("enum")));

        // "enum" (the constructor bindAtomInstance needs to read against) is a real fixture
        // declaration whose own field (`members: set<token>`) is argument-bearing -- compiling a
        // reader against it needs the *materialized* schema (a synthesized array entry for
        // `set<token>`), not this test's own narrow, unmaterialized `resolved` map, which doesn't
        // have one. The full, materialized meta-kernel resolves the identical `enum` declaration,
        // just reached a different way, so bindAtomInstance's own reader is compiled from that.
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        TypeDefinition accessType = definitionResolverFor(metaKernelParser, resolved::get).resolve(
                schemaMap.declarations().get("product_access_type"));

        assertEquals(TypeKind.ATOM, accessType.kind());
        assertFalse(accessType.constructor());
        assertEquals(List.of(), accessType.supertypes());
        assertEquals(Optional.of(io.ltr8.tson.schema.meta.TypeRef.of("enum")), accessType.source());
        assertEquals(new EnumBody(List.of("INDEX", "NAMED")), accessType.body());
    }

    /**
     * {@code boolean => !enum [true false]} is a real risk case for generic enum-member binding: a
     * bare array element carries no type-ref of its own, so an identification-first binder would
     * misidentify {@code "true"}/{@code "false"} as real Java booleans before ever treating them as
     * enum member text. {@code bindAtomInstance}'s own compiled-reader dispatch is schema-driven
     * (looked up by the constructor's own name, {@code enum}, fixed at compile time) rather than
     * token-identification-driven, so {@code "true"}/{@code "false"} are read correctly as the
     * enum's own raw member text, the same as every other real enum instance. This is a separate
     * path from {@link MetaKernelBootstrapResolver}'s own hand-picked {@code boolean}/{@code
     * toEnumBody} handling for meta-kernel's own bootstrap, which never calls {@code resolveInstance}
     * at all.
     */
    @Test
    void booleanInstanceResolvesCorrectlyViaTheCompiledReader() throws IOException {
        SchemaMap schemaMap = schemaMapFromFixture();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom")));
        resolved.put("enum", resolver.resolve(schemaMap.declarations().get("enum")));

        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        TypeDefinition booleanDef = definitionResolverFor(metaKernelParser, resolved::get).resolve(
                schemaMap.declarations().get("boolean"));

        assertEquals(new EnumBody(List.of("true", "false")), booleanDef.body());
    }

    /**
     * The same construct as above, but deliberately exercising the *other* half of §3.3.1's lookup
     * rule: a schema governed by meta-kernel (its {@code !!meta} target) that does NOT import it,
     * so {@code enum} is nowhere in this schema's own type-name namespace at all -- resolution must
     * fall through to the structure namespace, supplied here as meta-kernel's own real,
     * independently-resolved entries (Phase B step 2's threading, exercised for real for the first
     * time by an actual {@code Instance} resolution rather than an inert pass-through).
     */
    @Test
    void instanceResolvesViaTheStructureNamespaceWhenNotLocallyAvailable() {
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { my_bool => !enum [YES NO] }""").parseSchemaDocument().body();

        TypeDefinition myBool = definitionResolverFor(metaKernelParser, EMPTY_NAMESPACE).resolve(
                schemaMap.declarations().get("my_bool"));

        assertEquals(TypeKind.ATOM, myBool.kind());
        assertFalse(myBool.constructor());
        assertEquals(new EnumBody(List.of("YES", "NO")), myBool.body());
    }

    @Test
    void instanceResolutionRejectsATargetThatIsNotAConstructor() {
        // "token" resolves fine (kind ATOM) but constructor: false -- !token {} must be rejected,
        // not silently treated as a valid constructor application (§3.3.1's own suggested
        // diagnostic: "did you mean atom refinement?").
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { bad => !token {} }""").parseSchemaDocument().body();

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> definitionResolverFor(metaKernelParser, EMPTY_NAMESPACE).resolve(
                        schemaMap.declarations().get("bad")));
        assertTrue(thrown.getMessage().contains("does not resolve to a constructor"), thrown.getMessage());
    }

    // ── Atom refinement (§5.5, §5.7, Phase B step 5) ───────────────────────

    @Test
    void resolvesInt32FromTheRealCoreTypeLibraryFixture() throws IOException {
        // int32 => !integer ^ { size: { bits: 32  signed: true } } -- the concrete worked case
        // this whole phase started from. `integer` is core.tn1's own local redeclaration (`integer
        // => !integer_type {}`, an Instance reaching `integer_type` through the structure
        // namespace, since core.tn1 has no !!import of its own -- meta-kernel's entries stand in
        // for core.tn1's real structure namespace here, meta.tn1's own merged namespace, which
        // meta-kernel's entries are a subset of for this specific name; confirmed separately that
        // meta.tn1 doesn't locally redeclare integer_type). `int32`'s own refinement then resolves
        // `integer` purely through the type-name namespace (§3.3.1 -- atom refinement never
        // touches the structure namespace), which is exactly `resolved` here since `integer` was
        // just added to it.
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        DefinitionResolver instanceResolver = definitionResolverFor(metaKernelParser, resolved::get);
        SchemaMap schemaMap = schemaMapFromCoreFixture();
        resolved.put("integer", instanceResolver.resolve(schemaMap.declarations().get("integer")));

        TypeDefinition int32 = instanceResolver.resolve(schemaMap.declarations().get("int32"));

        assertEquals(TypeKind.ATOM, int32.kind());
        assertFalse(int32.constructor());
        assertEquals(List.of("integer"), int32.supertypes());
        assertEquals(Optional.of(TypeRef.of("integer_type")), int32.source());
        assertEquals(new IntegerType(new IntegerSize(32, true)), int32.body());
    }

    @Test
    void resolvesPositiveIntegerFromTheRealCoreTypeLibraryFixture() throws IOException {
        // positive_integer => !integer ^ { min: 1 } -- a scalar (not nested-record) refinement
        // value, confirming the binder isn't only exercised by int32's own nested-IntegerSize case.
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        DefinitionResolver instanceResolver = definitionResolverFor(metaKernelParser, resolved::get);
        SchemaMap schemaMap = schemaMapFromCoreFixture();
        resolved.put("integer", instanceResolver.resolve(schemaMap.declarations().get("integer")));

        TypeDefinition positiveInteger =
                instanceResolver.resolve(schemaMap.declarations().get("positive_integer"));

        assertEquals(IntegerType.ofMin(BigInteger.ONE), positiveInteger.body());
    }

    @Test
    void resolvesHexFromTheRealCoreTypeLibraryFixtureAsAPositionalFormInstance() throws IOException {
        // hex => !binary HEX -- an Instance (constructor application), not an atom refinement:
        // `binary` itself is the constructor, applied positionally. Included alongside the
        // refinement cases above to confirm the positional-form path (step 3) also works against a
        // real core.tn1 declaration, not just meta-kernel's own `enum` case. Unlike `integer_type`,
        // `binary`'s own constructor is declared in meta.tn1, not meta-kernel.tn1 (SPEC-FEEDBACK.md
        // #11), so this needs the fuller meta.tn1-merged namespace, not just meta-kernel's entries.
        SchemaMap schemaMap = schemaMapFromCoreFixture();
        TsonCompiledMetaSchema metaTn1Parser = metaTn1Compiled();

        TypeDefinition hex = definitionResolverFor(metaTn1Parser, EMPTY_NAMESPACE).resolve(
                schemaMap.declarations().get("hex"));

        assertEquals(BinaryType.HEX, hex.body());
    }

    @Test
    void resolvesFloat32AndFloat64FromTheRealCoreTypeLibraryFixture() throws IOException {
        // float32 => !float_type { format: BINARY32 } -- never mentions allow_nan/allow_infinity/
        // allow_subnormal/allow_negative_zero (all `boolean ~ true` in meta.tn1's real float_type),
        // so this only resolves at all because the compiled RecordBindReader fills in REQUIRED_DEFAULT
        // fields from the schema itself; previously failed with "missing required field 'allow_nan'".
        SchemaMap schemaMap = schemaMapFromCoreFixture();
        TsonCompiledMetaSchema metaTn1Parser = metaTn1Compiled();
        DefinitionResolver instanceResolver = definitionResolverFor(metaTn1Parser, EMPTY_NAMESPACE);

        TypeDefinition float32 = instanceResolver.resolve(schemaMap.declarations().get("float32"));
        TypeDefinition float64 = instanceResolver.resolve(schemaMap.declarations().get("float64"));

        assertEquals(FloatType.FLOAT32, float32.body());
        assertEquals(FloatType.FLOAT64, float64.body());
    }

    @Test
    void resolvesCidrEmailAndMacInstancesFromTheRealCoreTypeLibraryFixture() throws IOException {
        // cidr4 => !cidr4_type {}, cidr6 => !cidr6_type {}, email => !email_type {}, mac => !mac_type
        // {} -- all four constructors are record-only additions (Cidr4Type/Cidr6Type/EmailType/
        // MacType), no tson-compiler vocab compiler, added specifically so these real declarations
        // resolve; previously failed with "no member of union ... matches type name 'cidr4_type'"
        // (and friends) since Atom had no member for any of them at all. Each one's own `spec`
        // field is filled in by the compiled RecordBindReader from the schema's REQUIRED_FIXED
        // default, the same mechanism float32/float64 above rely on.
        SchemaMap schemaMap = schemaMapFromCoreFixture();
        TsonCompiledMetaSchema metaTn1Parser = metaTn1Compiled();
        DefinitionResolver instanceResolver = definitionResolverFor(metaTn1Parser, EMPTY_NAMESPACE);

        TypeDefinition cidr4 = instanceResolver.resolve(schemaMap.declarations().get("cidr4"));
        TypeDefinition cidr6 = instanceResolver.resolve(schemaMap.declarations().get("cidr6"));
        TypeDefinition email = instanceResolver.resolve(schemaMap.declarations().get("email"));
        TypeDefinition mac = instanceResolver.resolve(schemaMap.declarations().get("mac"));

        assertEquals(Cidr4Type.UNCONSTRAINED, cidr4.body());
        assertEquals(Cidr6Type.UNCONSTRAINED, cidr6.body());
        assertEquals(EmailType.UNCONSTRAINED, email.body());
        assertEquals(MacType.UNCONSTRAINED, mac.body());
    }

    @Test
    void resolvesIpv4AndIpv6InstancesFromTheRealCoreTypeLibraryFixture() throws IOException {
        // ipv4 => !ipv4_type {}, ipv6 => !ipv6_type {} -- Ipv4Type/Ipv6Type, same treatment as
        // Cidr4Type/Cidr6Type above (record-only, no vocab compiler, flat String spec).
        SchemaMap schemaMap = schemaMapFromCoreFixture();
        TsonCompiledMetaSchema metaTn1Parser = metaTn1Compiled();
        DefinitionResolver instanceResolver = definitionResolverFor(metaTn1Parser, EMPTY_NAMESPACE);

        TypeDefinition ipv4 = instanceResolver.resolve(schemaMap.declarations().get("ipv4"));
        TypeDefinition ipv6 = instanceResolver.resolve(schemaMap.declarations().get("ipv6"));

        assertEquals(Ipv4Type.UNCONSTRAINED, ipv4.body());
        assertEquals(Ipv6Type.UNCONSTRAINED, ipv6.body());
    }

    @Test
    void resolvesComplexAndUnknownInstancesFromTheRealCoreTypeLibraryFixture() throws IOException {
        // complex => !complex_type {} -- ComplexType, same record-only/no-compiler treatment as the
        // other atom families above.
        //
        // unknown => !unknown_type {} -- UnknownType's own constructor (unknown_type => ~sum & {})
        // composes with `sum`, not `atom`; resolves fine since bindAtomInstance binds against Top,
        // not the narrower Atom.
        SchemaMap schemaMap = schemaMapFromCoreFixture();
        TsonCompiledMetaSchema metaTn1Parser = metaTn1Compiled();
        DefinitionResolver instanceResolver = definitionResolverFor(metaTn1Parser, EMPTY_NAMESPACE);

        TypeDefinition complex = instanceResolver.resolve(schemaMap.declarations().get("complex"));
        TypeDefinition unknown = instanceResolver.resolve(schemaMap.declarations().get("unknown"));

        assertEquals(ComplexType.UNCONSTRAINED, complex.body());
        assertEquals(TypeKind.SUM, unknown.kind());
        assertEquals(new UnknownType(), unknown.body());
    }

    @Test
    void atomRefinementRejectsRefiningAConstructorInsteadOfAnInstance() {
        // integer_type itself is a constructor (constructor: true) -- refining it directly
        // ("!integer_type ^ {...}") is a resolver error; the diagnostic should point at
        // constructor application instead (§3.3.1).
        Map<String, TypeDefinition> metaKernelEntries = MetaKernelBootstrapResolver.getMetaKernelSchema().entries();
        DefinitionResolver metaKernelBackedResolver = new DefinitionResolver(NEVER_CALLED, EMPTY_NAMESPACE, metaKernelEntries::get);
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { bad => !integer_type ^ { min: 1 } }""").parseSchemaDocument().body();

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> metaKernelBackedResolver.resolve(schemaMap.declarations().get("bad")));
        assertTrue(thrown.getMessage().contains("refines a constructor, not an instance"), thrown.getMessage());
    }

    @Test
    void atomRefinementRejectsANonAtomFamilySource() {
        // top resolves fine (a fresh record, kind PRODUCT by the structural default) but isn't
        // atom-family -- !top ^ {...} must be rejected (§5.5).
        Map<String, TypeDefinition> metaKernelEntries = MetaKernelBootstrapResolver.getMetaKernelSchema().entries();
        DefinitionResolver metaKernelBackedResolver = new DefinitionResolver(NEVER_CALLED, EMPTY_NAMESPACE, metaKernelEntries::get);
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { bad => !top ^ { x: integer } }""").parseSchemaDocument().body();

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> metaKernelBackedResolver.resolve(schemaMap.declarations().get("bad")));
        assertTrue(thrown.getMessage().contains("is not an atom-family instance"), thrown.getMessage());
    }

    /**
     * §5.7's "Body materialisation" rule, applied to atom refinement (§5.6, {@code
     * SPEC-FEEDBACK.md} #17): a chained refinement (refining an already-refined instance -- not
     * exercised by any real fixture declaration, but not left ambiguous by the spec either -- §5.5's
     * own worked example says an atom refinement's result "can be refined further") MUST merge with
     * the intermediate instance's own already-bound fields, not discard them: {@code bounded}'s own
     * {@code size} (inherited from {@code int8}, untouched by {@code bounded}'s own refinement) MUST
     * survive, and {@code tighter}'s own explicit {@code max} MUST override the {@code max} {@code
     * bounded} itself set, while {@code bounded}'s own {@code min} (untouched by {@code tighter})
     * survives through yet another hop.
     *
     * <p>Every hop here genuinely narrows, which §5.7 requires and {@code
     * atomRefinementRejectsBoundsThatWidenTheSource} below covers from the other side: the bounds
     * stay inside {@code int8}'s own -128..127, and {@code tighter} only lowers a ceiling {@code
     * bounded} already set.
     */
    @Test
    void chainedAtomRefinementMergesWithIntermediateBindingsInsteadOfDiscardingThem() {
        // Atom refinement's own `source` lookup (finding "integer") is a DefinitionGetter lookup
        // only, per §3.3.1 -- never metaParser -- so meta-kernel's entries seed this test's own local
        // `chainNamespace` (shadowing the shared `resolved` field, deliberately: this test needs a
        // namespace pre-seeded with meta-kernel's own entries, not the shared field's empty start);
        // metaParser is now *also* needed, separately, for bindAtomInstance itself to find and read
        // "integer_type" (the constructor int8's own refinement source resolves through), which was
        // never something namespace lookup alone could provide.
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        Map<String, TypeDefinition> metaKernelEntries = metaKernelParser.schema().entries();
        Map<String, TypeDefinition> chainNamespace = new LinkedHashMap<>(metaKernelEntries);
        DefinitionResolver instanceResolver = definitionResolverFor(metaKernelParser, chainNamespace::get);
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  int8    => !integer ^ { size: { bits: 8  signed: true } }
                  bounded => !int8 ^ { min: -100  max: 100 }
                  tighter => !bounded ^ { max: 50 }
                }""").parseSchemaDocument().body();
        chainNamespace.put("int8", instanceResolver.resolve(schemaMap.declarations().get("int8")));
        chainNamespace.put("bounded", instanceResolver.resolve(schemaMap.declarations().get("bounded")));

        TypeDefinition bounded = chainNamespace.get("bounded");
        TypeDefinition tighter = instanceResolver.resolve(schemaMap.declarations().get("tighter"));

        // bounded keeps int8's own size (untouched by bounded's own refinement) alongside its new bounds.
        assertEquals(Optional.of(io.ltr8.tson.schema.meta.TypeRef.of("integer_type")), bounded.source());
        assertEquals(List.of("int8"), bounded.supertypes());
        assertEquals(new IntegerType(Optional.of(new IntegerSize(8, true)),
                Optional.of(java.math.BigInteger.valueOf(-100)), Optional.empty(),
                Optional.of(java.math.BigInteger.valueOf(100)), Optional.empty(), Optional.empty()),
                bounded.body());

        // tighter keeps int8's size AND bounded's min (neither touched by tighter's own refinement),
        // but overrides bounded's own max with its own.
        assertEquals(Optional.of(io.ltr8.tson.schema.meta.TypeRef.of("integer_type")), tighter.source());
        assertEquals(List.of("bounded"), tighter.supertypes());
        assertEquals(new IntegerType(Optional.of(new IntegerSize(8, true)),
                Optional.of(java.math.BigInteger.valueOf(-100)), Optional.empty(),
                Optional.of(java.math.BigInteger.valueOf(50)), Optional.empty(), Optional.empty()),
                tighter.body());
    }

    /**
     * §5.7's tightening rule: a refinement body that loosens a constraint instead of tightening it
     * is a resolver error, not a silently accepted override. The two cases here are the ones a merge
     * that just overwrites field by field cannot tell apart from a real narrowing -- a bound stated
     * outside the range the source's own {@code size} already fixes (nothing in {@code uint8}'s own
     * body states 0..255; its width does), and a bound stated outside one the source stated itself.
     */
    @Test
    void atomRefinementRejectsBoundsThatWidenTheSource() {
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        Map<String, TypeDefinition> chainNamespace = new LinkedHashMap<>(metaKernelParser.schema().entries());
        DefinitionResolver instanceResolver = definitionResolverFor(metaKernelParser, chainNamespace::get);
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  uint8       => !integer ^ { size: { bits: 8  signed: false } }
                  percent     => !integer ^ { min: 0  max: 100 }
                  escapesSize => !uint8 ^ { min: -10  max: 300 }
                  escapesMax  => !percent ^ { max: 1000 }
                }""").parseSchemaDocument().body();
        chainNamespace.put("uint8", instanceResolver.resolve(schemaMap.declarations().get("uint8")));
        chainNamespace.put("percent", instanceResolver.resolve(schemaMap.declarations().get("percent")));

        TsonSchemaValidationException widerThanTheWidth = assertThrows(TsonSchemaValidationException.class,
                () -> instanceResolver.resolve(schemaMap.declarations().get("escapesSize")));
        assertTrue(widerThanTheWidth.getMessage().contains("widens rather than tightens"), widerThanTheWidth.getMessage());
        assertTrue(widerThanTheWidth.getMessage().contains("min -10"), widerThanTheWidth.getMessage());
        assertTrue(widerThanTheWidth.getMessage().contains("max 300"), widerThanTheWidth.getMessage());

        TsonSchemaValidationException widerThanTheBound = assertThrows(TsonSchemaValidationException.class,
                () -> instanceResolver.resolve(schemaMap.declarations().get("escapesMax")));
        assertTrue(widerThanTheBound.getMessage().contains("max 1000 is above the source's own max 100"),
                widerThanTheBound.getMessage());
    }

    /**
     * The other side of {@code atomRefinementRejectsBoundsThatWidenTheSource}: the shapes a
     * tightening check must NOT reject. Restating a bound unchanged, adding a width inside an
     * already-bounded source, and tightening one end while leaving the other inherited all resolve.
     */
    @Test
    void atomRefinementAcceptsBoundsThatGenuinelyTighten() {
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        Map<String, TypeDefinition> chainNamespace = new LinkedHashMap<>(metaKernelParser.schema().entries());
        DefinitionResolver instanceResolver = definitionResolverFor(metaKernelParser, chainNamespace::get);
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  percent  => !integer ^ { min: 0  max: 100 }
                  restated => !percent ^ { max: 100 }
                  sized    => !percent ^ { size: { bits: 8  signed: false } }
                  oneEnd   => !percent ^ { max: 50 }
                }""").parseSchemaDocument().body();
        chainNamespace.put("percent", instanceResolver.resolve(schemaMap.declarations().get("percent")));

        // Restating a bound at exactly the source's own value is a no-op, not a widening.
        assertEquals(new IntegerType(Optional.empty(), Optional.of(java.math.BigInteger.ZERO), Optional.empty(),
                Optional.of(java.math.BigInteger.valueOf(100)), Optional.empty(), Optional.empty()),
                instanceResolver.resolve(schemaMap.declarations().get("restated")).body());

        // A width is compared against the source's own width, not its explicit bounds -- uint8's own
        // 0..255 reaches past percent's ceiling, yet adding it to a 0..100 source still narrows.
        assertEquals(new IntegerType(Optional.of(new IntegerSize(8, false)),
                Optional.of(java.math.BigInteger.ZERO), Optional.empty(),
                Optional.of(java.math.BigInteger.valueOf(100)), Optional.empty(), Optional.empty()),
                instanceResolver.resolve(schemaMap.declarations().get("sized")).body());

        // Tightening one end leaves the other inherited, which must compare equal rather than as a drop.
        assertEquals(new IntegerType(Optional.empty(), Optional.of(java.math.BigInteger.ZERO), Optional.empty(),
                Optional.of(java.math.BigInteger.valueOf(50)), Optional.empty(), Optional.empty()),
                instanceResolver.resolve(schemaMap.declarations().get("oneEnd")).body());
    }

    /**
     * A refinement inherits a field its source holds even when that field is {@code REQUIRED} with
     * no schema default -- {@code float_type.format}, which meta.tn1 declares as a bare {@code
     * format: ieee_format}. The merge supplies it from {@code float32}'s own bound body, so the
     * refinement body never has to restate it.
     *
     * <p>This is also the case that pins the merge's shape: it has to happen on the wire record,
     * before binding, precisely because binding the refinement body on its own would fail
     * {@code FIELD_REQUIRED} on {@code format} with nothing to fall back to. See {@code
     * DefinitionResolver#mergeWithSource}.
     */
    @Test
    void atomRefinementInheritsARequiredFieldItsSourceAlreadyFixed() throws IOException {
        TsonCompiledMetaSchema metaTn1Parser = metaTn1Compiled();
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(metaTn1Parser.schema().entries());
        DefinitionResolver resolver = definitionResolverFor(metaTn1Parser, namespace::get);
        namespace.put("float32", resolver.resolve(schemaMapFromCoreFixture().declarations().get("float32")));
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta.tn1"
                { probability => !float32 ^ { min: 0.0  max: 1.0 } }""").parseSchemaDocument().body();

        TypeDefinition probability = resolver.resolve(schemaMap.declarations().get("probability"));

        assertEquals(new FloatType(FloatType.Format.BINARY32,
                Optional.of(new java.math.BigDecimal("0.0")), Optional.empty(),
                Optional.of(new java.math.BigDecimal("1.0")), Optional.empty(), true, true, true, true),
                probability.body());
    }

    /**
     * Narrowing is decided by the constraint family, not by a generic field comparison, so a
     * non-numeric family enforces its own facets: text lengths here, where {@code min_length} may
     * only rise and {@code max_length} may only fall.
     */
    @Test
    void atomRefinementChecksTextLengthsThroughTheTextFamilysOwnRule() {
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        Map<String, TypeDefinition> chainNamespace = new LinkedHashMap<>(metaKernelParser.schema().entries());
        DefinitionResolver instanceResolver = definitionResolverFor(metaKernelParser, chainNamespace::get);
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                {
                  short_text  => !text ^ { min_length: 1  max_length: 10 }
                  shorter     => !short_text ^ { max_length: 5 }
                  longer      => !short_text ^ { max_length: 50 }
                }""").parseSchemaDocument().body();
        chainNamespace.put("short_text", instanceResolver.resolve(schemaMap.declarations().get("short_text")));

        assertEquals(new TextType(Optional.of(1), Optional.of(5), Optional.empty(), Optional.empty()),
                instanceResolver.resolve(schemaMap.declarations().get("shorter")).body());

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> instanceResolver.resolve(schemaMap.declarations().get("longer")));
        assertTrue(thrown.getMessage().contains("max_length 50 is above the source's own 10"), thrown.getMessage());
    }

    /**
     * A body the constructor's vocabulary rejects is the author's error, so it has to arrive as a {@link
     * TsonSchemaValidationException} -- the only exception type {@code SchemaResolver}'s reporting overload
     * collects into a diagnostic. An {@code UnsupportedOperationException} here (what the blanket
     * {@code catch (RuntimeException)} used to produce) aborts the whole run and prints a "this is a bug in
     * tson" banner over a plain typo, so the assertion is on the exception <em>type</em> first and the
     * message second.
     */
    @Test
    void aWrongTypedMemberInARefinementBodyIsASchemaErrorNotALibraryGap() {
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        Map<String, TypeDefinition> chainNamespace = new LinkedHashMap<>(metaKernelParser.schema().entries());
        DefinitionResolver instanceResolver = definitionResolverFor(metaKernelParser, chainNamespace::get);
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { bad => !integer ^ { min: "abc" } }""").parseSchemaDocument().body();

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> instanceResolver.resolve(schemaMap.declarations().get("bad")));
        assertTrue(thrown.getMessage().contains("not valid data for 'integer_type'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'abc' is not a valid integer"), thrown.getMessage());
    }

    /**
     * The headline case: a JSON-Schema-shaped refinement. {@code minimum}/{@code maximum} are not TSON's
     * facets, and before closure was enforced this compiled clean and then constrained nothing -- a schema
     * that reports success while switching validation off, which is strictly worse than one that fails.
     * A record is closed under its type (§7.2), and a constructor body is a record of that constructor's
     * constraint vocabulary, so the unknown member is caught by the same rule that catches one in data.
     *
     * <p>The diagnostic has to carry the real vocabulary, because that is the whole repair: an author (or a
     * model) reaching for {@code minimum} cannot guess {@code min} from a rejection alone.
     */
    @Test
    void aJsonSchemaShapedFacetInARefinementBodyIsRejectedAndAnsweredWithTheRealVocabulary() {
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        Map<String, TypeDefinition> chainNamespace = new LinkedHashMap<>(metaKernelParser.schema().entries());
        DefinitionResolver instanceResolver = definitionResolverFor(metaKernelParser, chainNamespace::get);
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { quantity_t => !integer ^ { minimum: 1  maximum: 100 } }""").parseSchemaDocument().body();

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> instanceResolver.resolve(schemaMap.declarations().get("quantity_t")));
        assertTrue(thrown.getMessage().contains("unknown field 'minimum' on 'integer_type'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("min | max"), thrown.getMessage());
    }

    /**
     * The other side of the split: a failure that is <em>not</em> the reader reporting on the body keeps the
     * {@code UnsupportedOperationException} gap wrapper. {@link #NEVER_CALLED} stands in for any such
     * mechanical failure -- the classification turns on which exception the meta reader raised, not on which
     * declaration reached it.
     */
    @Test
    void aMetaReaderFailureThatIsNotAReadDiagnosticStaysALibraryGap() {
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { bad => !integer ^ { min: 1 } }""").parseSchemaDocument().body();
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(metaKernelCompiled().schema().entries());
        DefinitionResolver gapResolver = new DefinitionResolver(NEVER_CALLED, namespace::get, namespace::get);

        UnsupportedOperationException thrown = assertThrows(UnsupportedOperationException.class,
                () -> gapResolver.resolve(schemaMap.declarations().get("bad")));
        assertTrue(thrown.getMessage().contains("failed to bind 'integer_type'"), thrown.getMessage());
    }

    @Test
    void atomRefinementNeverConsultsTheStructureNamespace() {
        // integer_type is real and present in `metaKernelEntries` -- an Instance target would find
        // it there via the structure-namespace fallback (§3.3.1, proven by
        // instanceResolvesViaTheStructureNamespaceWhenNotLocallyAvailable above). An atom
        // refinement's source MUST NOT get that same fallback: with `resolved` empty and
        // `integer_type` supplied only as structureNamespace (never consulted for `^`), "!integer_type
        // ^ {...}" still fails to resolve `I` at all -- a different failure from "resolves but isn't
        // an instance" (the constructor-rejection test above), which requires `I` to resolve first.
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();
        SchemaMap schemaMap = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { bad => !integer_type ^ { min: 1 } }""").parseSchemaDocument().body();

        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> definitionResolverFor(metaKernelParser, EMPTY_NAMESPACE).resolve(
                        schemaMap.declarations().get("bad")));
        assertTrue(thrown.getMessage().contains("does not resolve against the type-name namespace"), thrown.getMessage());
    }

    private SchemaMap schemaMapFromCoreFixture() throws IOException {
        String source = Files.readString(Path.of("").toAbsolutePath().resolve("../spec/m/core.tn").normalize());
        return new TsonSchemaParser(source).parseSchemaDocument().body();
    }

    /**
     * Wraps a bare, already-resolved {@code Map<String, TypeDefinition>} into a compiled,
     * object-binding-mode {@link TsonCompiledMetaSchema} -- what {@link #definitionResolverFor} needs to
     * build both a {@link DefinitionMetaReader} that can actually *bind* a constructor-application/
     * atom-refinement value, and the {@code DefinitionResolver} constructor's own structure-namespace
     * parameter (a plain {@code TypeDefinition} lookup is all that one needs). Generic over whatever
     * map a caller already has in hand (a hand-built {@code resolved} map, meta-kernel's own entries,
     * meta.tn1's own registered/merged entries, ...) -- the header fields (id/meta/imports) are
     * inert, {@code TsonCompiledMetaSchema}'s own {@code Compiler} never reads them, only {@code
     * entries()}.
     */
    private static TsonCompiledMetaSchema compileAsMetaParser(Map<String, TypeDefinition> entries) {
        TsonSchema synthetic = new TsonSchema("test", "", List.of(), entries);
        TsonLinkedSchema linkedSynthetic = new TsonLinkedSchema(synthetic);
        return new TsonCompiledMetaRegistry(SchemaMetaNameBinder.defaultContext()).bootstrap(linkedSynthetic);
    }

    /**
     * Meta-kernel, *linked* (via {@link TsonSchemaLinker#linkBootstrap}, purely so object mode's own
     * {@code TsonParserFactoryRegistry} has a real, synthesized-entries-included schema to validate
     * against -- an unlinked meta-kernel would resolve {@code enum}'s own {@code members:
     * set<token>} field to the raw, wrong {@code set} declaration instead of a synthesized "array of
     * token" entry, the same bug {@code TsonCompiledMetaRegistry}'s own bootstrap had), then compiled.
     */
    private static TsonCompiledMetaSchema metaKernelCompiled() {
        TsonSchema metaKernel = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(metaKernel);
        return compileAsMetaParser(linked.schema().entries());
    }

    /**
     * Meta-kernel registered, then meta.tn1 resolved and registered on top -- see {@code
     * MetaSchemaImportTest} for the same, fully-verified pattern (31/31 declarations, validated).
     *
     * <p>Meta-kernel itself is registered via ordinary {@code SchemaResolver.resolveSchema}, not
     * the raw bootstrap output ({@code TsonSchemaRegistry#register} refuses any self-referential
     * schema with {@code bootstrap() == true}, materialized or not -- see that method's own Javadoc)
     * -- mirrors {@code MetaTn1CompiledEndToEndTest#registerMeta}'s own pattern: a throwaway
     * loader, built from the (never-persisted) materialized bootstrap
     * output, resolves meta-kernel's own document the ordinary way (its own bootstrap branch
     * supplies the structure namespace, so even {@code boolean => !enum [...]} resolves correctly
     * despite the forward reference); that result carries no {@code bootstrap} flag, so it can
     * actually be registered.
     */
    private static TsonCompiledMetaSchema metaTn1Compiled() throws IOException {
        io.ltr8.tson.schema.TsonSchemaRegistry registry = new io.ltr8.tson.schema.TsonSchemaRegistry();
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledMetaRegistry throwawayRegistry = new TsonCompiledMetaRegistry(context, TsonBundledSchemas::fetch);
        TsonCompiledSchemaLoader throwawayLoader = throwawayRegistry;

        String metaKernelSource = TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID);
        SchemaDocument metaKernelDocument = new TsonSchemaParser(metaKernelSource).parseSchemaDocument();
        TsonSchema metaKernel = new SchemaResolver(throwawayLoader).resolveSchema(metaKernelDocument);
        registry.register(TsonSchemaLinker.link(metaKernel, registry)); // permanent, so meta.tn1's own !!import finds it below
        TsonCompiledMetaSchema metaKernelParser = metaKernelCompiled();

        String source = Files.readString(Path.of("").toAbsolutePath().resolve("../spec/m/meta.tn").normalize());
        SchemaDocument metaDoc = new TsonSchemaParser(source).parseSchemaDocument();
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(metaKernel.entries());
        DefinitionResolver metaResolver = definitionResolverFor(metaKernelParser, namespace::get);
        Map<String, TypeDefinition> localOnly = new LinkedHashMap<>();
        for (SchemaMap.Declaration declaration : metaDoc.body().declarations().values()) {
            TypeDefinition resolved = metaResolver.resolve(declaration);
            namespace.put(declaration.name(), resolved);
            localOnly.put(declaration.name(), resolved);
        }
        TsonSchema meta = new TsonSchema(metaDoc.id().orElseThrow(), metaDoc.meta(), metaDoc.imports(), localOnly);
        TsonLinkedSchema registeredMeta = registry.register(TsonSchemaLinker.link(meta, registry));
        return compileAsMetaParser(registeredMeta.schema().entries());
    }

    /** Populates the shared {@link #resolved} field up through {@code array} -- callers continue resolving against it via {@link #resolver} directly, no return value needed now that {@link #resolved} is a field, not a per-call local. */
    private void resolveUpToArray() throws IOException {
        SchemaMap schemaMap = schemaMapFromFixture();
        resolved.put("top", resolver.resolve(schemaMap.declarations().get("top")));
        resolved.put("atom", resolver.resolve(schemaMap.declarations().get("atom")));
        resolved.put("product", resolver.resolve(schemaMap.declarations().get("product")));
        resolved.put("array", resolver.resolve(schemaMap.declarations().get("array")));
    }

    private SchemaMap schemaMapFromFixture() throws IOException {
        return new TsonSchemaParser(readFixture()).parseSchemaDocument().body();
    }

    /**
     * Parses one declaration and resolves it, running {@link SchemaDesugarer} in between as {@code
     * SchemaResolver} does. The phase owns the sugar forms now, so a snippet that skipped it would be
     * resolving a shape the pipeline never produces -- and these tests assert the resulting {@link
     * TypeDefinition}, which the move did not change.
     */
    /**
     * Desugars and resolves one declaration against the real meta-kernel as its structure namespace. Both
     * phases get the *same* namespace, as {@code SchemaResolver} gives them in production -- a §8.2
     * instantiation is built by one and completed by the other (the desugarer routes the arguments, the
     * resolver recovers the template's supertypes), so a resolver looking at a different meta than the
     * desugarer did would fail loudly rather than quietly resolve something else. The compiled form is
     * needed because the binding record the desugarer emits is bound through the meta's own reader, the
     * same as any other {@code !C value}.
     */
    private TypeDefinition resolveSnippet(String declaration) {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { %s }""".formatted(declaration)).parseSchemaDocument();
        TsonCompiledMetaSchema metaKernel = metaKernelCompiled();
        SchemaMap schemaMap = SchemaDesugarer.desugar(document, metaKernel.schema().entries(), Set.of()).body();
        return definitionResolverFor(metaKernel, resolved::get)
                .resolve(schemaMap.declarations().get(declaration.split("=>")[0].trim()));
    }

    // ── Restating a field group (§5.11) ───────────────────────────────────
    //    "A body entry may also restate a group: the restated group MUST have the
    //    same member labels in the same order (member type-refs restated verbatim),
    //    and may tighten state OPTIONAL→REQUIRED; REQUIRED→OPTIONAL is a resolver
    //    error, and changing membership is a resolver error."

    private static final String BOUNDS =
            "bounds => { a: text  ( min: integer | exclusive_min: integer )? }";

    /**
     * Only the group's state moves. Members stay OPTIONAL in {@code fields} -- §5.11 flattens them that way
     * whatever the group says.
     */
    @Test
    void restatingAGroupInARefinementBodyTightensItsStateOnly() {
        Map<String, TypeDefinition> entries = resolveAll(BOUNDS
                + "  strict => bounds ^ { ( min: integer | exclusive_min: integer ) }");

        RecordBody body = bodyOf(entries.get("strict"));
        assertEquals(List.of(new FieldGroup(List.of("min", "exclusive_min"), ElementState.REQUIRED)), body.groups());
        assertEquals(List.of("a", "min", "exclusive_min"), fieldNames(entries.get("strict")));
        assertEquals(FieldState.OPTIONAL, body.fields().get(1).state());
        assertEquals(FieldState.OPTIONAL, body.fields().get(2).state());
        // the source keeps its own OPTIONAL group -- the restatement builds a new list, it does not edit it
        assertEquals(ElementState.OPTIONAL, bodyOf(entries.get("bounds")).groups().get(0).state());
    }

    /**
     * §5.11 says "in a refinement <em>or composition</em> body", and the composition side was not merely
     * unimplemented -- it rejected a legal restatement as a duplicate member name, citing the paragraph that
     * permits it. The restatement replaces the inherited group in place rather than appending a second one.
     */
    @Test
    void restatingAGroupInACompositionBodyTightensItInPlace() {
        Map<String, TypeDefinition> entries = resolveAll(BOUNDS
                + "  strict => bounds & { ( min: integer | exclusive_min: integer )  extra: text }");

        RecordBody body = bodyOf(entries.get("strict"));
        assertEquals(List.of(new FieldGroup(List.of("min", "exclusive_min"), ElementState.REQUIRED)), body.groups());
        // the new field still appends after the inherited ones (§5.8's ordering rule)
        assertEquals(List.of("a", "min", "exclusive_min", "extra"), fieldNames(entries.get("strict")));
    }

    /** Restating at the same state is the identity case, and must not be mistaken for a new group. */
    @Test
    void restatingAGroupWithoutChangingItsStateIsANoOp() {
        Map<String, TypeDefinition> entries = resolveAll(BOUNDS
                + "  same => bounds ^ { ( min: integer | exclusive_min: integer )? }");

        assertEquals(List.of(new FieldGroup(List.of("min", "exclusive_min"), ElementState.OPTIONAL)),
                bodyOf(entries.get("same")).groups());
    }

    @Test
    void rejectsARestatementThatLoosensARequiredGroup() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("bounds => { ( min: integer | exclusive_min: integer ) }"
                        + "  loose => bounds ^ { ( min: integer | exclusive_min: integer )? }"));
        assertTrue(thrown.getMessage().contains("OPTIONAL→REQUIRED"), thrown.getMessage());
    }

    @Test
    void rejectsARestatementThatReordersOrDropsAMember() {
        TsonSchemaValidationException reordered = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(BOUNDS
                        + "  odd => bounds ^ { ( exclusive_min: integer | min: integer ) }"));
        assertTrue(reordered.getMessage().contains("same member labels in the same order"), reordered.getMessage());

        TsonSchemaValidationException added = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(BOUNDS
                        + "  odd => bounds ^ { ( min: integer | exclusive_min: integer | other: integer ) }"));
        assertTrue(added.getMessage().contains("changing membership"), added.getMessage());
    }

    /** "Member type-refs restated verbatim" -- narrowing a member's type is done by naming it as a field. */
    @Test
    void rejectsARestatementThatChangesAMembersType() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(BOUNDS
                        + "  odd => bounds ^ { ( min: text | exclusive_min: integer ) }"));
        assertTrue(thrown.getMessage().contains("restated verbatim"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'min'"), thrown.getMessage());
    }

    /** A group whose members are inherited plain fields is not a restatement of anything. */
    @Test
    void rejectsAGroupOverInheritedFieldsThatWereNeverAGroup() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("pair => { x: integer  y: integer }"
                        + "  odd => pair ^ { ( x: integer | y: integer ) }"));
        assertTrue(thrown.getMessage().contains("not a group"), thrown.getMessage());
    }

    /** A refinement adds nothing, groups included -- the group analogue of the no-new-fields rule. */
    @Test
    void rejectsAWhollyNewGroupInARefinementBody() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("bounds => { a: text }"
                        + "  odd => bounds ^ { ( p: integer | q: integer ) }"));
        assertTrue(thrown.getMessage().contains("names no inherited group"), thrown.getMessage());
    }

    /** A composition body may still introduce a genuinely new group -- the false branch of the same check. */
    @Test
    void aCompositionBodyStillAddsAWhollyNewGroup() {
        Map<String, TypeDefinition> entries = resolveAll("base => { a: text }"
                + "  extended => base & { ( p: integer | q: integer )? }");

        assertEquals(List.of("a", "p", "q"), fieldNames(entries.get("extended")));
        assertEquals(List.of(new FieldGroup(List.of("p", "q"), ElementState.OPTIONAL)),
                bodyOf(entries.get("extended")).groups());
    }

    // ── The six field-state spellings (§5.2) ──────────────────────────────

    /** §5.2's table, end to end: five states across six spellings, in one record. */
    @Test
    void resolvesAllSixFieldStateSpellings() {
        RecordBody body = bodyOf(resolveAll("""
                config => {
                  host:   text
                  port:   integer ~ 8080
                  debug:  boolean = false
                  label:  text?
                  format: text? = json
                  extra:  text? = _
                }
                """).get("config"));

        assertEquals(FieldState.REQUIRED, body.fields().get(0).state());
        assertEquals(FieldState.REQUIRED_DEFAULT, body.fields().get(1).state());
        assertEquals(FieldState.REQUIRED_FIXED, body.fields().get(2).state());
        assertEquals(FieldState.OPTIONAL, body.fields().get(3).state());
        assertEquals(FieldState.OPTIONAL_FIXED, body.fields().get(4).state());
        // the sixth spelling: OPTIONAL_FIXED carrying no value at all, so §8.1 writes a record_field
        // *without* a `value` member -- the field must be omitted or written as `_`
        assertEquals(FieldState.OPTIONAL_FIXED, body.fields().get(5).state());
        assertEquals(Optional.empty(), body.fields().get(5).value());
        assertTrue(body.fields().get(4).value().isPresent());
    }

    /**
     * §5.2 makes {@code = _} valid on a field "declared with {@code ?} <b>or inherited as OPTIONAL</b>", and
     * a modifier-only tightening entry has no {@code ?} of its own -- so presence has to be read off the
     * field being tightened. This is §5.9's IS-A-preserving counterpart to removal: the field stays in the
     * contract, its value is forbidden.
     */
    @Test
    void fixesAnInheritedOptionalFieldToAbsent() {
        Map<String, TypeDefinition> entries = resolveAll("""
                base => { name: text  nickname: text? }
                anonymous => base ^ { nickname: = _ }
                """);

        RecordField nickname = bodyOf(entries.get("anonymous")).fields().get(1);
        assertEquals(FieldState.OPTIONAL_FIXED, nickname.state());
        assertEquals(Optional.empty(), nickname.value());
        // unlike removal (§5.9), IS-A survives -- the field is still in the contract
        assertEquals(List.of("base"), entries.get("anonymous").supertypes());
        assertEquals(List.of("name", "nickname"), fieldNames(entries.get("anonymous")));
    }

    /** §5.2: "`~ _` (any field) -- a required field cannot fall back to not-being-filled." */
    @Test
    void rejectsAnAbsentDefault() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("config => { label: text? ~ _ }"));
        assertTrue(thrown.getMessage().contains("'~ _'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.2"), thrown.getMessage());
    }

    /** §5.2: "`= _` on a REQUIRED field -- a field cannot be required and fixed to not-being-present." */
    @Test
    void rejectsFixingARequiredFieldToAbsent() {
        TsonSchemaValidationException fresh = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("config => { label: text = _ }"));
        assertTrue(fresh.getMessage().contains("required"), fresh.getMessage());

        // and through inheritance: the source declares it REQUIRED, so the tightening entry inherits that
        TsonSchemaValidationException inherited = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("""
                        base => { name: text }
                        odd => base ^ { name: = _ }
                        """));
        assertTrue(inherited.getMessage().contains("required"), inherited.getMessage());
    }

    /** §5.2: "`type? ~ value` -- a default implies the field is always present, contradicting optional." */
    @Test
    void rejectsADefaultOnAnOptionalField() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("config => { label: text? ~ none }"));
        assertTrue(thrown.getMessage().contains("contradicts optional"), thrown.getMessage());
        // the message offers all three spellings the author might have meant
        assertTrue(thrown.getMessage().contains("'type ~ value'"), thrown.getMessage());
    }

    /**
     * A parametric modifier lands in a REQUIRED-family state whatever the presence axis says (§5.7's "Open
     * modifiers": "a parametric `= P` places the field in REQUIRED -- from OPTIONAL this is the table's
     * ordinary OPTIONAL → REQUIRED tightening"). That is what makes {@code array_min}'s {@code min_items: =
     * MIN} mandatory, so the parameter branch has to sit ahead of the OPTIONAL_FIXED one.
     */
    @Test
    void aParametricModifierOnAnInheritedOptionalFieldStillLandsInRequired() {
        Map<String, TypeDefinition> entries = resolveAll("""
                base => { bound: integer? }
                bounded => <MIN> base ^ { bound: = MIN }
                """);

        RecordField bound = bodyOf(entries.get("bounded")).fields().get(0);
        assertEquals(FieldState.REQUIRED, bound.state());
        assertEquals(Optional.of("MIN"), bound.valueParam());
        assertEquals(Optional.empty(), bound.value());
    }

    // ── Group presence under tightening (§5.11) ───────────────────────────
    //    "Group presence rules are checked against the refined states at schema
    //    load: a refinement under which two members of one group are always
    //    present (both in a REQUIRED-family state) is a resolver error."

    /**
     * The rule earns its keep: without it the declaration resolves, compiles, and then rejects every value
     * ever written against it -- a group admits at most one member, so two that must always be there is a
     * contract nothing can satisfy. Caught where it is written instead.
     */
    @Test
    void rejectsARefinementMakingTwoGroupMembersAlwaysPresent() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(BOUNDS
                        + "  impossible => bounds ^ { min: integer = 0  exclusive_min: integer = 1 }"));
        assertTrue(thrown.getMessage().contains("min and exclusive_min"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("at most one"), thrown.getMessage());
    }

    /**
     * §5.11's sentence says "a refinement", but it sits in a paragraph headed "Refinement and composition"
     * that puts both bodies under §5.7's tightening rules -- and a composition body produces the identical
     * unsatisfiable type, so reading it as refinement-only would leave the same defect legal by the other
     * spelling.
     */
    @Test
    void rejectsACompositionBodyMakingTwoGroupMembersAlwaysPresent() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(BOUNDS
                        + "  impossible => bounds & { min: integer = 0  exclusive_min: integer = 1 }"));
        assertTrue(thrown.getMessage().contains("at most one"), thrown.getMessage());
    }

    /** REQUIRED_DEFAULT counts too: a default supplies the value, so the field is there in every value. */
    @Test
    void aDefaultCountsAsAlwaysPresentForTheGroupRule() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(BOUNDS
                        + "  impossible => bounds ^ { min: integer ~ 0  exclusive_min: integer = 1 }"));
        assertTrue(thrown.getMessage().contains("min and exclusive_min"), thrown.getMessage());
    }

    /**
     * Pinning <em>one</em> alternative is the point of tightening a member, and stays legal. It also shows
     * the two spellings apart: a modifier-only entry moves only the mutability axis (§5.7's "only the value
     * state changes"), so an inherited-OPTIONAL member pinned with {@code = 0} lands in OPTIONAL_FIXED and
     * stays absent-able -- which for a group member is exactly right, since the sibling alternative has to
     * remain reachable.
     */
    @Test
    void tighteningASingleGroupMemberIsFine() {
        Map<String, TypeDefinition> entries = resolveAll(BOUNDS + "  pinned => bounds ^ { min: = 0 }");

        RecordBody body = bodyOf(entries.get("pinned"));
        assertEquals(FieldState.OPTIONAL_FIXED, body.fields().get(1).state());
        assertEquals(FieldState.OPTIONAL, body.fields().get(2).state());
        assertEquals(List.of(new FieldGroup(List.of("min", "exclusive_min"), ElementState.OPTIONAL)), body.groups());
    }

    /** The rule is per group -- one always-present member in each of two groups is not a conflict. */
    @Test
    void oneAlwaysPresentMemberInEachOfTwoGroupsIsFine() {
        Map<String, TypeDefinition> entries = resolveAll("""
                ranged => { ( min: integer | exclusive_min: integer )? ( max: integer | exclusive_max: integer )? }
                pinned => ranged ^ { min: integer = 0  max: integer = 9 }
                """);

        assertEquals(2, bodyOf(entries.get("pinned")).groups().size());
        assertEquals(FieldState.REQUIRED_FIXED, bodyOf(entries.get("pinned")).fields().get(0).state());
        assertEquals(FieldState.REQUIRED_FIXED, bodyOf(entries.get("pinned")).fields().get(2).state());
    }

    // ── Composition/refinement rejections (§5.7, §5.8, §5.11) ─────────────
    //    Every one is the author's error under a MUST in the spec, so each is a
    //    TsonSchemaValidationException. What varies is only which rule was broken,
    //    and the message has to say which -- that is what decides the author's fix.

    /** §5.8: "supertypes MUST contribute disjoint field sets" -- the message must name the supertype case. */
    @Test
    void rejectsAFieldNameTwoSupertypesBothContribute() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("""
                        address => { street: text  city: text }
                        contact => { city: text  email: text }
                        customer => address & contact
                        """));
        assertTrue(thrown.getMessage().contains("'city'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("disjoint field sets"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.8"), thrown.getMessage());
    }

    /** §5.11: a name is unique across a record's plain fields -- a different fix from the supertype case. */
    @Test
    void rejectsAFieldNameDeclaredTwiceInOneBody() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("point => { x: integer  x: text }"));
        assertTrue(thrown.getMessage().contains("'x'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("declares it twice"), thrown.getMessage());
    }

    /** §5.11: "member labels share the enclosing record's field namespace" -- the third distinct wording. */
    @Test
    void rejectsAGroupMemberRepeatingAPlainFieldName() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("bounds => { min: integer  ( min: text | other: text ) }"));
        assertTrue(thrown.getMessage().contains("'min'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("group member"), thrown.getMessage());
    }

    /**
     * §5.7's "Refinement requires a vocabulary body": a definition whose body is a binding record -- here a
     * top-level constructor application -- is <em>finished</em>, and {@code ^} on it is a resolver error. The
     * message points at the form that does work on an atom instance, {@code !I ^ { ... }} (§5.5), because
     * that is what an author reaching for this actually wants.
     */
    @Test
    void rejectsRefiningADefinitionWhoseBodyIsABindingRecord() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveSnippetsAgainstMetaKernel("""
                        bounded => integer ^ { min: = 0 }
                        """));
        assertTrue(thrown.getMessage().contains("finished"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("!integer ^"), thrown.getMessage());
    }

    /**
     * The composition twin of the case above. §5.8 states no vocabulary-body rule of its own, though it needs
     * one for the same reason -- a binding record has no fields to copy. Read as the author's error under
     * §5.7's principle; {@code SPEC-FEEDBACK.md} #38 asks for §5.8 to say so.
     */
    @Test
    void rejectsComposingWithASupertypeWhoseBodyIsABindingRecord() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveSnippetsAgainstMetaKernel("""
                        weird => integer & { extra: text }
                        """));
        assertTrue(thrown.getMessage().contains("no fields to contribute"), thrown.getMessage());
    }

    /**
     * §12.1 draws {@code construction-def}'s operands from {@code type-ref}, which admits {@code paren-type}
     * and {@code inline-array} -- where {@code refined-def} takes a name. Neither could ever denote a record,
     * so both are rejected here as the author's error rather than deferred: there is no field set for any
     * future implementation to compose with ({@code SPEC-FEEDBACK.md} #38 argues the production is the
     * defect).
     *
     * <p>Only reachable from the <em>second</em> operand onward. At the first, §12.1's disambiguation summary
     * sends {@code (} to paren-type and {@code [} to container-def, so {@code (a | b) & { ... }} is a parse
     * error before resolution ever sees it -- the same form admitted after {@code &} and forbidden before it,
     * which is half of what #38 reports.
     */
    @Test
    void rejectsAChoiceOrABracketedFormAsASupertype() {
        TsonSchemaValidationException choice = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("""
                        a => { x: text }
                        b => { y: text }
                        odd => a & (a | b)
                        """));
        assertTrue(choice.getMessage().contains("choice"), choice.getMessage());
        assertTrue(choice.getMessage().contains("variants"), choice.getMessage());

        TsonSchemaValidationException bracketed = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll("""
                        a => { x: text }
                        odd => a & [a]
                        """));
        assertTrue(bracketed.getMessage().contains("elements"), bracketed.getMessage());
    }

    /**
     * Resolves a hand-written body with meta-kernel's own entries as the type-name namespace, so a
     * declaration can name a real atom instance ({@code integer => !integer_type {}}) as a source or
     * supertype -- the shapes §5.7/§5.8 reject, which need a non-record body to exist at all.
     */
    private TypeDefinition resolveSnippetsAgainstMetaKernel(String body) {
        TsonCompiledMetaSchema metaKernel = metaKernelCompiled();
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { %s }""".formatted(body)).parseSchemaDocument();
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(metaKernel.schema().entries());
        TypeDefinition last = null;
        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            last = definitionResolverFor(metaKernel, namespace::get).resolve(declaration);
            namespace.put(declaration.name(), last);
        }
        return last;
    }

    // ── Subtraction (§5.9) ────────────────────────────────────────────────

    private static final String ACCOUNT = "account => { name: text  email: text  password: text }";

    /** Resolves a whole hand-written schema body in declaration order, so a later entry can compose with an earlier one. */
    private Map<String, TypeDefinition> resolveAll(String body) {
        SchemaDocument document = new TsonSchemaParser("""
                !!meta:"https://tson.io/2026/32/m/meta-kernel.tn1"
                { %s }""".formatted(body)).parseSchemaDocument();
        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            resolved.put(declaration.name(), resolver.resolve(declaration));
        }
        return resolved;
    }

    private static RecordBody bodyOf(TypeDefinition definition) {
        return assertInstanceOf(RecordBody.class, definition.body());
    }

    private static List<String> fieldNames(TypeDefinition definition) {
        return bodyOf(definition).fields().stream().map(RecordField::name).toList();
    }

    /**
     * §5.9's headline: the removed field is gone and IS-A is <em>broken</em> -- the contract index
     * ({@code type_definition.supertypes}) is empty, so §7.2's subsumption check will not let an
     * {@code account_public} stand where an {@code account} is expected, while the body keeps {@code account}
     * as authorial lineage. Getting only the field arithmetic right and leaving the supertypes alone would
     * silently make a subtracted type substitutable for the thing it deliberately isn't.
     */
    @Test
    void subtractionRemovesTheFieldAndBreaksIsAWhileKeepingLineage() {
        Map<String, TypeDefinition> entries = resolveAll(ACCOUNT + "  account_public => account - { password }");

        TypeDefinition subtracted = entries.get("account_public");
        assertEquals(List.of("name", "email"), fieldNames(subtracted));
        assertEquals(List.of(), subtracted.supertypes());              // contract: broken
        assertEquals(List.of("account"), bodyOf(subtracted).supertypes()); // lineage: kept
        assertEquals(TypeKind.PRODUCT, subtracted.kind());
        // the source is untouched -- removal builds a new field list, it does not edit the supertype's
        assertEquals(List.of("name", "email", "password"), fieldNames(entries.get("account")));
    }

    /**
     * Rule 1's ordering, over §5.9's own {@code staff_public} example: supertypes merge, the body adds and
     * tightens, and only then do removals apply. Adding one field while removing another in a single
     * declaration is ordinary, not a conflict -- rule 4 bites only when both name the <em>same</em> field.
     */
    @Test
    void removalsApplyAfterTheSupertypesAndTheBody() {
        Map<String, TypeDefinition> entries = resolveAll(ACCOUNT
                + "  user => { badge_id: text }"
                + "  staff_public => account & user & { badge: text } - { password }");

        TypeDefinition staff = entries.get("staff_public");
        // inherited in supertype order (minus the removal), then the body's genuinely new field
        assertEquals(List.of("name", "email", "badge_id", "badge"), fieldNames(staff));
        assertEquals(List.of(), staff.supertypes());
        assertEquals(List.of("account", "user"), bodyOf(staff).supertypes());
    }

    /** A body entry may tighten a field that survives the removal -- §5.9's own {@code account_view}. */
    @Test
    void aRemovalCoexistsWithATighteningOfADifferentField() {
        Map<String, TypeDefinition> entries = resolveAll(ACCOUNT
                + "  account_view => account & { email: text ~ \"n/a\" } - { password }");

        TypeDefinition view = entries.get("account_view");
        assertEquals(List.of("name", "email"), fieldNames(view));
        // the tightening replaced the inherited field in place, and removal ran afterwards
        assertEquals(FieldState.REQUIRED_DEFAULT, bodyOf(view).fields().get(1).state());
    }

    /**
     * Rule 4: stating a field and removing it in one declaration says two incompatible things. Checked ahead
     * of rule 2's "no such field", because a body-introduced field <em>is</em> in the merged set -- answering
     * "there is no such field" would be a wrong diagnosis of a real, differently-shaped mistake.
     */
    @Test
    void rejectsARemovalNamingAFieldTheBodyItselfIntroduces() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(ACCOUNT + "  odd => account & { badge: text } - { badge }"));
        assertTrue(thrown.getMessage().contains("own body also declares"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.9 rule 4"), thrown.getMessage());
    }

    /** Rule 4's other half: a body entry tightening a field the same declaration removes. */
    @Test
    void rejectsARemovalNamingAFieldTheBodyTightens() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(ACCOUNT + "  odd => account & { password: text ~ \"x\" } - { password }"));
        assertTrue(thrown.getMessage().contains("own body also declares"), thrown.getMessage());
    }

    /** Rule 2, symmetric with refinement's existing-fields-only rule: nothing to remove is an author error. */
    @Test
    void rejectsARemovalNamingAFieldThatIsNotThere() {
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolveAll(ACCOUNT + "  odd => account - { nickname }"));
        assertTrue(thrown.getMessage().contains("not a field of the composed type"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("§5.9 rule 2"), thrown.getMessage());
    }

    /**
     * §5.11's group arithmetic: a removed member leaves {@code members}, and a group down to one member is
     * dissolved, its survivor taking the <em>group's</em> state. That last step matters -- members are
     * flattened as OPTIONAL whatever the group says, so a dissolved REQUIRED group whose survivor stayed
     * OPTIONAL would quietly drop the "exactly one MUST be present" the author wrote.
     */
    @Test
    void removingAGroupMemberDissolvesAGroupLeftWithOne() {
        Map<String, TypeDefinition> entries = resolveAll("""
                bounds => { a: text  ( min: integer | exclusive_min: integer ) }
                one_bound => bounds - { exclusive_min }
                """);

        TypeDefinition dissolved = entries.get("one_bound");
        assertEquals(List.of("a", "min"), fieldNames(dissolved));
        assertEquals(List.of(), bodyOf(dissolved).groups());
        assertEquals(FieldState.REQUIRED, bodyOf(dissolved).fields().get(1).state());
        // the source still has both members and its group
        assertEquals(List.of(new FieldGroup(List.of("min", "exclusive_min"), ElementState.REQUIRED)),
                bodyOf(entries.get("bounds")).groups());
    }

    /** An OPTIONAL group's survivor becomes an OPTIONAL field -- the group's state, not the member's. */
    @Test
    void aDissolvedOptionalGroupLeavesAnOptionalField() {
        Map<String, TypeDefinition> entries = resolveAll("""
                bounds => { a: text  ( min: integer | exclusive_min: integer )? }
                one_bound => bounds - { exclusive_min }
                """);

        assertEquals(FieldState.OPTIONAL, bodyOf(entries.get("one_bound")).fields().get(1).state());
    }

    /** Three members less one is still a group: two members left, state untouched. */
    @Test
    void aGroupWithMembersToSpareSurvivesTheRemoval() {
        Map<String, TypeDefinition> entries = resolveAll("""
                stamps => { ( created: text | modified: text | accessed: text )? }
                fewer => stamps - { accessed }
                """);

        assertEquals(List.of("created", "modified"), fieldNames(entries.get("fewer")));
        assertEquals(List.of(new FieldGroup(List.of("created", "modified"), ElementState.OPTIONAL)),
                bodyOf(entries.get("fewer")).groups());
    }

    /**
     * Removing every member takes the group with them. §5.11 legislates only the reduced-to-one case, so this
     * is this implementation's reading of a gap, recorded as {@code SPEC-FEEDBACK.md} #36: an empty group has
     * no members to choose between, and keeping a REQUIRED one would demand a member that cannot exist.
     */
    @Test
    void removingEveryMemberDropsTheGroupItself() {
        Map<String, TypeDefinition> entries = resolveAll("""
                bounds => { a: text  ( min: integer | exclusive_min: integer ) }
                unbounded => bounds - { min  exclusive_min }
                """);

        assertEquals(List.of("a"), fieldNames(entries.get("unbounded")));
        assertEquals(List.of(), bodyOf(entries.get("unbounded")).groups());
    }

    /**
     * A supertype naming nothing is the schema author's error, not a gap in this resolver, so it is a
     * {@link TsonSchemaValidationException}. It used to be an {@code UnsupportedOperationException} saying
     * "not resolved yet (only supertypes declared earlier in the same schema map are visible so far)" --
     * a limitation that no longer exists, since {@code SchemaResolver} resolves on demand following
     * dependencies rather than source order (see {@code ForwardReferenceResolutionTest}).
     */
    @Test
    void compositionRejectsAnUnresolvedSupertype() throws IOException {
        SchemaMap schemaMap = new TsonSchemaParser(readFixture()).parseSchemaDocument().body();
        // "top" deliberately left out of the resolved map -- atom's supertype resolves to nothing
        // (the shared resolved field starts empty, and nothing puts "top" into it before this call).
        TsonSchemaValidationException thrown = assertThrows(TsonSchemaValidationException.class,
                () -> resolver.resolve(schemaMap.declarations().get("atom")));
        assertTrue(thrown.getMessage().contains("names no type this schema declares or imports"),
                thrown.getMessage());
    }

    private static String readFixture() throws IOException {
        return Files.readString(Path.of("").toAbsolutePath().resolve("../spec/m/meta-kernel.tn").normalize());
    }
}
