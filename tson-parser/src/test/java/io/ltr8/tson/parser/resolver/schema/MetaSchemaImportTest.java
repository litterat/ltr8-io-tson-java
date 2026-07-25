package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.SchemaParser;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.resolver.TsonAtomContext;
import io.ltr8.tson.parser.resolver.schema.compiled.ParserFactoryRegistry;
import io.ltr8.tson.parser.resolver.schema.compiled.TsonCompiledRegistry;
import io.ltr8.tson.schema.MetaSchema;
import io.ltr8.tson.schema.SchemaRegistry;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@code SchemaValidator}'s {@code !!import} merging (see its own Javadoc) against the
 * real {@code meta.tn1} fixture -- register meta-kernel first, then meta.tn1's own declarations
 * (resolved via {@link SchemaResolver#resolveAll}, mirroring the now-deleted {@code MetaTn1Parser}'s
 * own bootstrap steps -- see {@link #parseMetaTn1}), and confirm meta-kernel's names (e.g. {@code
 * atom}, {@code text_type}) are visible and correctly referenced from meta.tn1's own
 * composition-based declarations (e.g. {@code date_type => ~atom & atom_specification & {...}}).
 *
 * <p><b>meta.tn1 now registers in full, all 31 declarations</b> (2026-07-24, once {@code
 * SchemaResolver} gained generic {@code Instance} resolution -- Phase B step 4) -- previously 4 of
 * its 31 declarations ({@code binary_encoding}, {@code ieee_format}, {@code complex_component},
 * {@code ordered}, all {@code !enum [...]}) had to be skipped, and 3 more ({@code binary}, {@code
 * float_type}, {@code complex_type}) that reference one of those four as a field type had to be
 * excluded too (registering them without their dependency present correctly failed validation).
 * With generic {@code Instance} resolution in place, every one of the 31 resolves in a single
 * source-order pass (meta.tn1's own declaration order already has each dependency before its use --
 * unlike meta-kernel.tn1 itself, which needs {@code MetaKernelParser}'s own two-pass ordering for
 * forward references like {@code boolean => !enum [...]} preceding {@code enum}'s own declaration),
 * and the merged, validated registration succeeds outright.
 */
class MetaSchemaImportTest {

    /**
     * Deliberately still resolves via a bare {@link SchemaResolver#resolveAll(SchemaDocument)} call
     * rather than {@link DefaultSchemaCoordinator#resolve(String)} -- this test wants meta.tn1's own
     * *raw, unregistered, local-only* result (31 entries, no merged imports) to exercise {@code
     * SchemaRegistry#register}'s own import-merge itself, one stage later; {@code
     * DefaultSchemaCoordinator#resolve} would register (and materialize/merge) it immediately as
     * part of the same call, collapsing the two stages this test means to keep separate. {@link
     * BundledSchemaSource} is still reused here, just for the raw fetch, so this doesn't duplicate
     * its own classpath-reading logic -- the same reasoning that replaced the old, now-deleted
     * {@code MetaTn1Parser} everywhere else that only wants the *fully* resolved-and-registered
     * result (see {@code SchemaResolverCompiledMetaSchemaTest#loadMetaKernelAndMeta}).
     */
    private static TsonSchema parseMetaTn1(MetaSchema metaKernel) {
        TsonSchema materializedMetaKernel = new SchemaRegistry().register(metaKernel);
        DataBindContext context = TsonAtomContext.defaultContext();
        ParserFactoryRegistry objectFactories = ParserFactoryRegistry.object(materializedMetaKernel, context);

        TsonCompiledRegistry compiledRegistry = new TsonCompiledRegistry(objectFactories);
        compiledRegistry.register(metaKernel);
        DefaultSchemaCoordinator coordinator = new DefaultSchemaCoordinator(compiledRegistry);

        String source = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_TN1_ID);
        SchemaDocument metaDocument = new SchemaParser(source).parseSchemaDocument();
        return new SchemaResolver(coordinator).resolveAll(metaDocument);
    }

    @Test
    void mergesMetaKernelIntoAllThirtyOneOfMetaTn1sOwnDeclarations() {
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);

        TsonSchema meta = parseMetaTn1(metaKernel);
        assertEquals(31, meta.entries().size(), "expected every meta.tn1 declaration to resolve");

        TsonSchema registered = registry.register(meta);

        // Meta-kernel's own imported entries are visible in the merged, validated namespace.
        assertTrue(registered.entries().containsKey("atom"));
        assertTrue(registered.entries().containsKey("text_type"));
        // meta.tn1's own composition against an imported supertype resolved and validated correctly.
        assertTrue(registered.entries().containsKey("date_type"));
        // The four constructor-application (!enum [...]) declarations previously excluded now
        // resolve too, bound generically via TsonMapperReader against Atom.class.
        assertEquals(new EnumBody(List.of("BASE64", "BASE64URL", "BASE32", "HEX")),
                registered.entries().get("binary_encoding").body());
        // ...and the three declarations that reference one of those four as a field type now
        // register successfully as well, since their dependency is present in the same schema.
        assertTrue(registered.entries().containsKey("binary"));
        assertTrue(registered.entries().containsKey("float_type"));
        assertTrue(registered.entries().containsKey("complex_type"));
    }

    @Test
    void registeringBinaryWithoutItsUnresolvedBinaryEncodingFieldCorrectlyFailsValidation() {
        MetaSchema metaKernel = MetaKernelParser.parse();
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(metaKernel);

        TsonSchema meta = parseMetaTn1(metaKernel);
        TypeDefinition binary = meta.entries().get("binary");

        TsonSchema withBinaryOnly = new TsonSchema(meta.id(), meta.meta(), meta.imports(), Map.of("binary", binary));

        assertThrows(io.ltr8.tson.schema.SchemaValidationException.class, () -> registry.register(withBinaryOnly));
    }
}
