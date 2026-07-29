package io.ltr8.tson.compiler.resolver;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.compiler.TsonCompiledMetaSchema;
import io.ltr8.tson.compiler.compiler.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.config.TsonAtomContext;
import io.ltr8.tson.compiler.config.TsonCompiledRegistry;
import io.ltr8.tson.compiler.config.ValueReaderFactoryResolver;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A small, user-defined schema -- not one of this library's own bundled documents -- that imports
 * {@code core.tn} (governed by {@code meta.tn}, the same as {@code core.tn} itself) and uses two
 * of its real declarations, plus a small, locally-declared one-field record bound to a real Java
 * class the caller supplies manually (not resolved via {@link SchemaMetaNameBinder}'s own {@code
 * io.ltr8.tson.schema.meta} convention -- {@code my_record} has no class there at all). The point:
 * proving {@code core.tn} can be {@code !!import}ed by an *ordinary* consuming schema, not just
 * registered on its own the way {@link CoreSchemaImportTest} does, and that a real consumer's own
 * record type -- composed with {@code core.tn}'s own vocabulary as a field -- compiles and reads
 * cleanly in object-binding mode with nothing more than a manual {@link DataNameBinder} entry.
 *
 * <p>{@code my_int} is a bare reference (§8.3) to core.tn's own {@code int32}; {@code
 * my_percentage} further atom-refines core.tn's own {@code positive_integer}; {@code my_record} is
 * a fresh, one-field record (§8.1, no supertypes) whose own field type is core.tn's own {@code
 * int32}. All three prove the import reached real, usable vocabulary, not just a well-formed URI --
 * {@code resolveComposition}/atom-refinement resolution, and an ordinary field's own type-ref, all
 * look a name straight up in the merged type-name namespace with no fallback, so none of them would
 * resolve at all if {@code !!import} weren't genuinely merging core.tn's own entries in.
 */
class TinySchemaImportsCoreTn1Test {

    /** The Java shape {@code my_record => { value: int32 }} is manually bound to below -- deliberately outside {@code io.ltr8.tson.schema.meta}, so {@link SchemaMetaNameBinder}'s own namespace convention can't find it by accident. */
    public record MyRecord(Integer value) {
    }

    private static final String TINY_DOCUMENT = """
            !!id:"https://example.test/tiny-core-import.tn1"
            !!meta:"https://tson.io/2026/32/m/meta.tn"
            !!import:"https://tson.io/2026/32/m/core.tn"
            {
              my_int => int32
              my_percentage => !positive_integer ^ { max: 100 }
              my_record => { value: int32 }
            }
            """;

    /**
     * A {@link DataNameBinder} that manually resolves {@code "my_record"} to {@link MyRecord}
     * directly, falling back to {@link SchemaMetaNameBinder#INSTANCE}'s own {@code
     * io.ltr8.tson.schema.meta} convention for every other name this test's own compile still needs
     * (meta-kernel's/meta.tn's/core.tn's own composite constructors -- {@code record}, {@code
     * array}, ...) -- exactly the pattern {@link SchemaMetaNameBinder}'s own class Javadoc describes
     * for "a caller binding their own schema to their own Java library."
     */
    private static final DataNameBinder MANUAL_BINDER = schemaTypeName -> "my_record".equals(schemaTypeName)
            ? MyRecord.class
            : SchemaMetaNameBinder.INSTANCE.resolve(schemaTypeName);

    @Test
    void aTinySchemaThatImportsCoreTn1RegistersAndCompilesCleanly() {
        TsonSchemaRegistry schemaRegistry = new TsonSchemaRegistry();
        DataBindContext context = TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(MANUAL_BINDER).build());
        ValueReaderFactoryResolver resolver = ValueReaderFactoryRegistry.bind(context);
        TsonCompiledRegistry registry = new TsonCompiledRegistry(schemaRegistry, resolver);
        DefaultTsonCompiledSchemaLoader loader = new DefaultTsonCompiledSchemaLoader(registry, TsonBundledSchemas::fetch);

        // meta-kernel's own bootstrap case, registered explicitly -- see TsonBundledSchemas's own
        // class Javadoc for why this step can't just be another loader.load(...) call.
        SchemaDocument metaKernelDocument = new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument();
        TsonSchema resolvedMetaKernel = new TsonSchemaResolver(loader).resolveSchema(metaKernelDocument);
        registry.register(resolvedMetaKernel, loader.load(TsonBundledSchemas.META_KERNEL_ID));

        TsonCompiledMetaSchema compiledMeta = loader.load(TsonBundledSchemas.META_ID);
        loader.load(TsonBundledSchemas.CORE_ID); // must be registered before the tiny schema's own !!import can find it

        SchemaDocument tinyDocument = new TsonSchemaParser(TINY_DOCUMENT).parseSchemaDocument();
        TsonSchema resolvedTiny = new TsonSchemaResolver(loader).resolveSchema(tinyDocument);

        // Imported entries are visible during resolution but never part of the resolved schema's own
        // entries() -- only the three local declarations should be here.
        assertEquals(Set.of("my_int", "my_percentage", "my_record"), resolvedTiny.entries().keySet());

        TypeDefinition myInt = resolvedTiny.entries().get("my_int");
        assertEquals(TypeKind.REFERENCE, myInt.kind());
        TypeDefinition myRecord = resolvedTiny.entries().get("my_record");
        assertEquals(TypeKind.PRODUCT, myRecord.kind());

        // Links (merging core.tn's own entries in, via the shared schemaRegistry this time --
        // TsonSchemaLinker's own import-merge, a separate pass from resolveSchema's own above),
        // registers, and compiles -- governed by meta.tn's own compiled reader, the same one
        // core.tn itself was compiled against. my_record's own field dispatches to a real,
        // manually-bound RecordBindReader here, via MANUAL_BINDER above.
        TsonCompiledMetaSchema compiledTiny = registry.register(resolvedTiny, compiledMeta);

        // Reading real data through the compiled readers proves core.tn's own vocabulary was
        // genuinely reached, not just referenced at the resolver level.
        Object myIntValue = compiledTiny.compiledSchema().get("my_int")
                .read(new TsonDataParser("42").parseDocument().root());
        assertEquals(42, myIntValue);

        Object myPercentageValue = compiledTiny.compiledSchema().get("my_percentage")
                .read(new TsonDataParser("50").parseDocument().root());
        assertEquals(BigInteger.valueOf(50), myPercentageValue);

        // The record case: real TSON data binds directly to the manually-registered MyRecord class,
        // its own "value" field narrowed through core.tn's own int32 the same way an ordinary
        // schema-declared field would be.
        Object myRecordValue = compiledTiny.compiledSchema().get("my_record")
                .read(new TsonDataParser("{ value: 7 }").parseDocument().root());
        assertEquals(new MyRecord(7), myRecordValue);
    }
}
