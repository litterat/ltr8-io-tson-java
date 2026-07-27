package io.ltr8.tson.parser.bind;

import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.parser.base.TsonAtomContext;
import io.ltr8.tson.parser.resolver.MetaKernelBootstrapResolver;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaLinker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TsonObjectBinder#bind}'s own eager, whole-schema behavior, against the real, registered
 * {@code meta-kernel.tn1} schema -- the read path this binding result feeds is covered separately
 * in {@code ObjectRecordShapeFactoryTest}.
 */
class TsonObjectBinderTest {

    @Test
    void theWholeRealMetaKernelSchemaBindsCleanly() {
        // Confirmed empirically (not assumed): of the 58 real, registered entries, 23 are
        // record-shaped and genuinely bind (including set/array_min/array_max/array_ranged, via
        // SchemaMetaTypeNameBinder's own ArrayBody alias, and every atom constraint-vocabulary
        // record like uri_type/regex_type, which need TsonAtomContext's own URI/UUID/... atom
        // registrations to resolve at all); 5 more (atom/product/sum/top/type_argument) resolve to
        // a real, deliberately non-record class and are silently skipped, not failures. Nothing
        // should throw.
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonSchema registered = TsonSchemaLinker.linkBootstrap(raw).schema();

        TsonObjectBinder.bind(registered, TsonAtomContext.defaultContext(), SchemaMetaTypeNameBinder.INSTANCE);
    }

    @Test
    void bindReportsEveryUnresolvableEntryAtOnceRatherThanOneAtATime() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonSchema registered = TsonSchemaLinker.linkBootstrap(raw).schema();
        TsonTypeNameBinder alwaysMissing = name -> {
            throw new ClassNotFoundException("no class for '" + name + "' under this test's own binder");
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> TsonObjectBinder.bind(registered, TsonAtomContext.defaultContext(), alwaysMissing));

        // At least "integer_type" and "text_type" -- two real, distinct record-shaped entries --
        // both named in the one report, not just the first one bind() happened to hit.
        assertTrue(thrown.getMessage().contains("'integer_type'"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("'text_type'"), thrown.getMessage());
    }

    @Test
    void resultOmitsEntriesThatResolveToARealNonRecordClass() {
        TsonSchema raw = MetaKernelBootstrapResolver.getMetaKernelSchema();
        TsonSchema registered = TsonSchemaLinker.linkBootstrap(raw).schema();

        Map<String, DataClassRecord> bound =
                TsonObjectBinder.bind(registered, TsonAtomContext.defaultContext(), SchemaMetaTypeNameBinder.INSTANCE);

        assertTrue(bound.containsKey("integer_type"));
        assertTrue(bound.containsKey("text_type"));
        // atom/product/sum/top/type_argument mangle to real, deliberately non-record classes --
        // present in the schema, but not in this binding result.
        assertTrue(!bound.containsKey("atom") && !bound.containsKey("top"));
    }
}
