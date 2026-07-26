package io.ltr8.tson.parser.resolver.schema.compiled;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.Parser;
import io.ltr8.tson.parser.ast.Document;
import io.ltr8.tson.parser.resolver.TsonAtomContext;
import io.ltr8.tson.parser.resolver.schema.MetaKernelParser;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.TextType;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves object-binding mode ({@link ParserFactoryRegistry#object}) genuinely produces real, bound
 * {@code schema.meta} Java objects -- not {@code Map<String, Object>} -- reading against the real,
 * registered {@code meta-kernel.tn1} schema, mirroring {@link MetaKernelEndToEndTest}'s own
 * bootstrap pattern. {@link SchemaMetaTypeNameBinder} (a {@code Class.forName}-based lookup, not a
 * scan of any sealed hierarchy) resolves every real {@code record}-shaped entry in the schema,
 * including nested helper records like {@code integer_size} -- so {@code integer_type} itself now
 * *compiles* cleanly, previously blocked entirely (see {@link
 * #integerTypeCompilesAndReadsWithSizeLeftAbsent}'s own Javadoc for why its data still leaves
 * {@code size} unpopulated -- a separate, already-tracked gap, not a regression here).
 */
class ObjectRecordShapeFactoryTest {

    private static TsonSchemaParser compiled() {
        MetaSchema raw = MetaKernelParser.getMetaKernelSchema();
        TsonSchema registered = new SchemaRegistry().register(raw);
        DataBindContext context = TsonAtomContext.defaultContext();
        return TsonSchemaParser.compile(registered, ParserFactoryRegistry.object(registered, context));
    }

    @Test
    void textTypeNarrowsTheSchemasArbitraryPrecisionIntegerDownToTheRealFieldsIntWidth() {
        // min_length/max_length are the schema's own unconstrained `integer` atom (natural host
        // type BigInteger), but TextType.minLength/maxLength are Optional<Integer> -- this is
        // exactly the narrowing path NumberNarrowing exists for.
        TsonSchemaParser compiled = compiled();
        Document document = new Parser("{ min_length: 3 max_length: 10 }").parseDocument();

        Object result = compiled.get("text_type").read(document.root());

        TextType textType = assertInstanceOf(TextType.class, result);
        assertEquals(new TextType(Optional.of(3), Optional.of(10), Optional.empty(), Optional.empty()), textType);
    }

    @Test
    void integerTypeCompilesAndReadsWithSizeLeftAbsent() {
        // The case that used to block compilation entirely: integer_type's own `size:
        // integer_size?` field previously had no Class binding at all (integer_size isn't a Top
        // leaf), so RecordParser.factory's own eager per-field ctx.resolve("integer_size") failed
        // before any data was ever read -- regardless of whether a given value populated `size`.
        // SchemaMetaTypeNameBinder has no such restriction (a plain Class.forName lookup, not a
        // scan of any sealed hierarchy), so integer_type now compiles cleanly.
        //
        // `size` is still left absent in the DATA here, deliberately -- not to route around the
        // compile-time fix (already proven: this test would have thrown before ever reaching a
        // read), but because integer_size.signed is a primitive boolean sourced from the schema's
        // own `boolean => !enum [true false]`, which resolves to the String "true"/"false", not a
        // real Java boolean -- an already-documented, permanent limit of generic binding (tracked
        // separately, see MetaKernelEndToEndTest's own "text, not a Java boolean" note), unrelated
        // to this change and out of scope for it.
        TsonSchemaParser compiled = compiled();
        Document document = new Parser("{ min: -5 max: 100 }").parseDocument();

        Object result = compiled.get("integer_type").read(document.root());

        IntegerType integerType = assertInstanceOf(IntegerType.class, result);
        assertEquals(new IntegerType(Optional.empty(), Optional.of(BigInteger.valueOf(-5)), Optional.empty(),
                Optional.of(BigInteger.valueOf(100)), Optional.empty(), Optional.empty()), integerType);
    }

    @Test
    void theWholeRealMetaKernelSchemaValidatesCleanly() {
        // Confirmed empirically (not assumed): of the 58 real, registered entries, 23 are
        // record-shaped and genuinely bind (including set/array_min/array_max/array_ranged, via
        // SchemaMetaTypeNameBinder's own ArrayBody alias, and every atom constraint-vocabulary
        // record like uri_type/regex_type, which need TsonAtomContext's own URI/UUID/... atom
        // registrations to resolve at all); 5 more (atom/product/sum/top/type_argument) resolve to
        // a real, deliberately non-record class and are silently skipped, not failures. Nothing
        // should throw.
        MetaSchema raw = MetaKernelParser.getMetaKernelSchema();
        TsonSchema registered = new SchemaRegistry().register(raw);
        ObjectRecordShapeFactory shapeFactory = new ObjectRecordShapeFactory(TsonAtomContext.defaultContext());

        shapeFactory.validate(registered);
    }

    @Test
    void validateReportsEveryUnresolvableEntryAtOnceRatherThanOneAtATime() {
        MetaSchema raw = MetaKernelParser.getMetaKernelSchema();
        TsonSchema registered = new SchemaRegistry().register(raw);
        TypeNameBinder alwaysMissing = name -> {
            throw new ClassNotFoundException("no class for '" + name + "' under this test's own binder");
        };
        ObjectRecordShapeFactory shapeFactory =
                new ObjectRecordShapeFactory(TsonAtomContext.defaultContext(), alwaysMissing);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> shapeFactory.validate(registered));

        // At least "integer_type" and "text_type" -- two real, distinct record-shaped entries --
        // both named in the one report, not just the first one validate() happened to hit.
        assertTrue(thrown.getMessage().contains("'integer_type'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'text_type'"), thrown.getMessage());
    }
}
