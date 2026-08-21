package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.TestDocuments;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves object-binding mode ({@link ValueReaderFactoryRegistry#bind}) genuinely produces real,
 * bound {@code schema.meta} Java objects -- not {@code Map<String, Object>} -- reading against the
 * real, registered {@code meta-kernel.tn1} schema, mirroring {@link MetaKernelEndToEndTest}'s own
 * DOM-mode bootstrap pattern. {@link SchemaMetaNameBinder} (a {@code Class.forName}-based lookup,
 * not a scan of any sealed hierarchy) resolves every real {@code record}-shaped entry in the
 * schema, including nested helper records like {@code integer_size} -- so {@code integer_type}
 * itself compiles cleanly (its own {@code size: integer_size?} field is not a {@link
 * io.ltr8.tson.schema.meta.Top} leaf, which a sealed-hierarchy scan couldn't have resolved at all).
 */
class RecordBindReaderTest {

    private static TsonCompiledSchema compiled() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        return TsonSchemaCompiler.compile(linked, ValueReaderFactoryRegistry.bind(context));
    }

    @Test
    void textTypeNarrowsTheSchemasArbitraryPrecisionIntegerDownToTheRealFieldsIntWidth() {
        // min_length/max_length are the schema's own unconstrained `integer` atom (natural host
        // type BigInteger), but TextType.minLength/maxLength are Optional<Integer> -- this is
        // exactly the narrowing path NumberNarrowing exists for.
        //
        // A standalone schema with "text_type"'s own real field shape but no subtypes -- the real
        // meta-kernel entry also carries uri_type/regex_type/email_type as subtypes (see
        // constructorFlaggedTypeWithRealSubtypesDispatchesToTheNamedSubtype below for that
        // dispatch itself); this test isolates the narrowing behavior specifically.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("text", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), TextType.UNCONSTRAINED));
        io.ltr8.tson.schema.meta.FieldState optional = io.ltr8.tson.schema.meta.FieldState.OPTIONAL;
        entries.put("text_type", TypeDefinition.product(RecordBody.of(List.of(
                new RecordField("min_length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty()),
                new RecordField("max_length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty()),
                new RecordField("length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty()),
                new RecordField("pattern", TypeRef.of("text"), optional, Optional.empty(), Optional.empty())))));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn1", "https://example.test/meta.tn1", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(linkedSchema, ValueReaderFactoryRegistry.bind(context));

        Object result = compiled.get("text_type").read(TestDocuments.document("{ min_length: 3 max_length: 10 }"));

        TextType textType = assertInstanceOf(TextType.class, result);
        assertEquals(new TextType(Optional.of(3), Optional.of(10), Optional.empty(), Optional.empty()), textType);
    }

    /**
     * A FIXED field's check has to compare like with like. Bind mode narrows {@code precomputedValue} in
     * place -- here the schema's own {@code integer} atom gives a {@link BigInteger} while {@code
     * TextType.minLength} is an {@code Optional<Integer>} -- so comparing a freshly-parsed token against
     * the narrowed copy would report every conforming document as contradicting its own schema. The check
     * keeps the raw value and the pre-rebind parser for exactly that reason; this pins both directions.
     */
    @Test
    void aFixedFieldChecksTheWrittenValueAgainstTheRawSchemaValueNotTheNarrowedOne() {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        entries.put("text", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), TextType.UNCONSTRAINED));
        io.ltr8.tson.schema.meta.FieldState optional = io.ltr8.tson.schema.meta.FieldState.OPTIONAL;
        entries.put("text_type", TypeDefinition.product(RecordBody.of(List.of(
                new RecordField("min_length", TypeRef.of("integer"),
                        io.ltr8.tson.schema.meta.FieldState.REQUIRED_FIXED,
                        Optional.of(new io.ltr8.tson.schema.meta.Token("3",
                                io.ltr8.tson.schema.meta.Token.Form.UNQUOTED)), Optional.empty()),
                new RecordField("max_length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty()),
                new RecordField("length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty()),
                new RecordField("pattern", TypeRef.of("text"), optional, Optional.empty(), Optional.empty())))));
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(
                new TsonLinkedSchema(new TsonSchema("https://example.test/s.tn1",
                        "https://example.test/meta.tn1", List.of(), entries)),
                ValueReaderFactoryRegistry.bind(SchemaMetaNameBinder.defaultContext()));

        // stating the fixed value is fine, and the bound field still gets the narrowed Integer
        TextType stated = assertInstanceOf(TextType.class,
                compiled.get("text_type").read(TestDocuments.document("{ min_length: 3 }")));
        assertEquals(Optional.of(3), stated.minLength());
        // omitting it injects the same thing
        TextType omitted = assertInstanceOf(TextType.class,
                compiled.get("text_type").read(TestDocuments.document("{}")));
        assertEquals(Optional.of(3), omitted.minLength());
        // and contradicting it is still caught
        TsonReadException thrown = assertThrows(TsonReadException.class,
                () -> compiled.get("text_type").read(TestDocuments.document("{ min_length: 4 }")));
        assertTrue(thrown.getMessage().contains("cannot be given another value"), thrown.getMessage());
    }

    @Test
    void integerTypeCompilesAndReadsWithSizeLeftAbsent() {
        // integer_type's own `size: integer_size?` field previously had no Class binding at all
        // under a sealed-hierarchy scan (integer_size isn't a Top leaf) -- SchemaMetaNameBinder has
        // no such restriction (a plain Class.forName lookup), so integer_type compiles cleanly.
        //
        // `size` is left absent in the data here, deliberately -- not to route around anything, but
        // because integer_size.signed is a primitive boolean sourced from the schema's own
        // `boolean => !enum [true false]`, which resolves to the String "true"/"false", not a real
        // Java boolean -- an already-documented, permanent limit of generic binding (see
        // MetaKernelEndToEndTest's own "text, not a Java boolean" note), unrelated to this test.
        TsonCompiledSchema compiled = compiled();

        Object result = compiled.get("integer_type").read(TestDocuments.document("{ min: -5 max: 100 }"));

        IntegerType integerType = assertInstanceOf(IntegerType.class, result);
        assertEquals(new IntegerType(Optional.empty(), Optional.of(BigInteger.valueOf(-5)), Optional.empty(),
                Optional.of(BigInteger.valueOf(100)), Optional.empty(), Optional.empty()), integerType);
    }

    @Test
    void theWholeRealMetaKernelSchemaCompilesCleanlyInBindMode() {
        // Every one of the real, registered entries -- including the 5 pure marker roots
        // (atom/product/sum/top/type_argument, which resolve to real but deliberately non-record
        // sealed interfaces) -- gets a compiled reader; TsonSchemaCompiler's own eager walk builds
        // the whole schema regardless of whether any given entry is ever actually read.
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonLinkedSchema linked = TsonSchemaLinker.linkBootstrap(raw);
        TsonCompiledSchema compiled = compiled();

        for (String name : linked.schema().entries().keySet()) {
            compiled.get(name);
        }
        assertEquals(58, linked.schema().entries().size());
    }

    @Test
    void constructorFlaggedTypeWithRealSubtypesDispatchesToTheNamedSubtype() {
        // A standalone schema mirroring text_type's own real shape: a constructor-flagged record
        // with real fields of its own *and* a real subtype composing on top of it (email_type is
        // the real fixture's own such subtype -- see RecordBindReader.Factory's own Javadoc). Before
        // the fix, RecordBindReader.Factory bound "text_type" as a plain record regardless of its
        // own subtypes, so an explicit !email_type value at a text_type-typed position silently read
        // as a TextType, never dispatching to EmailType at all.
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        entries.put("integer", new TypeDefinition(Optional.empty(), TypeKind.ATOM, List.of(), false, List.of(),
                List.of(), Optional.empty(), IntegerType.UNCONSTRAINED));
        io.ltr8.tson.schema.meta.FieldState optional = io.ltr8.tson.schema.meta.FieldState.OPTIONAL;
        entries.put("text_type", new TypeDefinition(Optional.empty(), TypeKind.PRODUCT, List.of(), true,
                List.of(), List.of("email_type"), Optional.empty(),
                RecordBody.of(List.of(
                        new RecordField("min_length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty()),
                        new RecordField("max_length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty())))));
        entries.put("email_type", new TypeDefinition(Optional.of(TypeRef.of("text_type")), TypeKind.PRODUCT, List.of(),
                true, List.of("text_type"), List.of(), Optional.empty(),
                RecordBody.of(List.of(
                        new RecordField("min_length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty()),
                        new RecordField("max_length", TypeRef.of("integer"), optional, Optional.empty(), Optional.empty())))));
        TsonSchema schema = new TsonSchema("https://example.test/s.tn1", "https://example.test/meta.tn1", List.of(), entries);
        TsonLinkedSchema linkedSchema = new TsonLinkedSchema(schema);
        DataBindContext context = SchemaMetaNameBinder.defaultContext();
        TsonCompiledSchema compiled = TsonSchemaCompiler.compile(linkedSchema, ValueReaderFactoryRegistry.bind(context));

        // No type-ref -- reads against text_type's own body, same as before the fix.
        Object ownResult = compiled.get("text_type").read(TestDocuments.document("{ min_length: 3 }"));
        TextType textType = assertInstanceOf(TextType.class, ownResult);
        assertEquals(3, textType.minLength().orElseThrow());

        // An explicit !email_type value at the same text_type-typed position now dispatches to
        // email_type's own compiled reader, producing a real EmailType, not a TextType.
        Object subtypeResult = compiled.get("text_type").read(TestDocuments.document("!email_type { min_length: 5 }"));
        io.ltr8.tson.schema.meta.EmailType emailType =
                assertInstanceOf(io.ltr8.tson.schema.meta.EmailType.class, subtypeResult);
        assertEquals(5, emailType.minLength().orElseThrow());
    }

    @Test
    void aPureMarkerRootHasNoOwnDataAndRequiresAnExplicitSubtype() {
        // "top" mangles to the real, sealed Top interface -- a genuine DataClassUnion with nothing
        // instantiable of its own. Its compiled reader still exists (see the previous test), but
        // reading a value with no type-ref against it -- i.e. treating it as if it had real fields
        // of its own -- fails, since there's no Java object "just a top" could construct.
        TsonCompiledSchema compiled = compiled();

        assertThrows(TsonReadException.class, () -> compiled.get("top").read(TestDocuments.document("{}")));
    }
}
