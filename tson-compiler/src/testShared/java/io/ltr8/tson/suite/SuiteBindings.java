package io.ltr8.tson.suite;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;

import java.util.Map;
import java.util.Set;

/**
 * The binding both runners resolve the corpus under: the schema model's own vocabulary, plus a host class for
 * each record a corpus fixture's namespace types hold ({@code io.ltr8.tson.suite.data}).
 *
 * <p><b>Why a runner binds classes at all.</b> A value of a meta layer's namespace type reads only into classes the
 * reading application binds ([TSON-SCHEMA] §4.1; {@code design/meta-layer-data-kind.md}), so a vector whose schema
 * applies one cannot be judged by a runner that binds none. The corpus states the records in a fixture meta-schema;
 * this is the host side, one record per meta record and named for it, found by the name binder's package
 * convention. Composed onto the library's own binder, which is asked first, so nothing here shadows the kernel's
 * vocabulary.
 */
public final class SuiteBindings {

    private static final DataNameBinder CORPUS_RECORDS = new DataNameBinder.DefaultDataNameBinder(
            Set.of("io.ltr8.tson.suite.data"), Map.of());

    private SuiteBindings() {
    }

    /**
     * The corpus's meta records alone, for a front door that composes them onto the library's own
     * ({@code ProcessorConfig.withMetaNameBinder}).
     */
    public static DataNameBinder names() {
        return CORPUS_RECORDS;
    }

    /** A fresh context: the schema model's binder, extended by the corpus's meta records. */
    public static DataBindContext context() {
        return SchemaMetaNameBinder.contextExtendedWith(CORPUS_RECORDS);
    }
}
