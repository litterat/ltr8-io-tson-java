package io.ltr8.tson.schema;

import io.ltr8.bind.DataBindException;
import io.ltr8.tson.mapper.TsonMapper;
import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Writes resolved values through plain {@code TsonMapper.toTson} -- no hand-written schema-model
 * writer at all -- deliberately, to validate the {@code io.ltr8.tson.schema.meta} model is built
 * from ordinary, idiomatic Java (records, sealed interfaces, enums, {@code Optional}) that {@code
 * tson-bind}'s generic introspection already knows how to bind, rather than a shape that happens
 * to work only because a bespoke writer papered over it.
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
 * (an enum's bridge produces a {@code String}, and {@code TsonMapper} always quotes strings --
 * already true, and already documented, for every other enum this codebase binds), every
 * empty-list/false/{@code REQUIRED}-at-default field written out rather than omitted ({@code
 * Optional.empty()}/{@code null} are the only things generic binding omits), and {@code TypeRef}
 * always in its full {@code { name: ... arguments: [...] } } form, never Part 2 §5.6's positional
 * bare-token spelling (a schema-specific encoding convention plain {@code tson-mapper}, a
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
    private final TsonMapper mapper = new TsonMapper();

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

        TsonSchema schema = resolver.resolveAll(doc.body());

        assertEquals(2, schema.entries().size());
        assertEquals(EXPECTED_INTEGER_SIZE, write(schema.entries().get("integer_size")));
    }

    // ── TypeBody variants SchemaResolver doesn't produce yet: hand-built,
    //    checked against the real toTson output (see class Javadoc for why it
    //    diverges, structurally faithfully, from meta-kernel-resolved.tn1's own text) ──

    @Test
    void writesAReferenceBody() throws DataBindException {
        // Structurally: type_name => !type_definition { kind: REFERENCE source: token body: !reference { target: token } }
        TypeDefinition typeName = TypeDefinition.reference("token");

        assertEquals("{ source: { name: \"token\" arguments: [] } kind: \"REFERENCE\" parameters: [] "
                        + "constructor: false supertypes: [] subtypes: [] "
                        + "body: !reference { target: { name: \"token\" arguments: [] } } }",
                write(typeName));
    }

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

    private static String readFixture() throws IOException {
        return Files.readString(Path.of("").toAbsolutePath().resolve("../spec/m/meta-kernel.tn1").normalize());
    }
}
