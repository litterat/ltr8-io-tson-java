package io.ltr8.tson.parser.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.tson.parser.compiler.AtomTypeParser;
import io.ltr8.tson.parser.compiler.RecordParser;
import io.ltr8.tson.parser.compiler.TsonParserFactoryRegistry;
import io.ltr8.tson.schema.TsonSchema;

import java.util.Map;

/**
 * Assembles a {@link TsonParserFactoryRegistry} for object-binding mode -- `record` produces a
 * real, bound {@code schema.meta} Java object (via {@link ObjectRecordShapeFactory}) instead of DOM
 * mode's {@code Map<String, Object>} -- every other factory is identical to {@code
 * TsonParserFactoryRegistry#dom()} (both start from {@link TsonParserFactoryRegistry
 * #withoutRecordOrEnum()}), since Array/Map/Tuple/Choice/Variant/atom-family parsers already
 * produce mode-correct nested content purely by recursing into whichever child parser the same
 * registry resolves.
 *
 * <p>Moved here from {@code TsonParserFactoryRegistry} itself (2026-07-27, on the user's own
 * explicit direction) so that class -- the actual compiler-facing registry -- has no dependency on
 * Java-object-binding machinery at all, the same reasoning that keeps {@code tson-bind} itself a
 * leaf module: DOM mode never needs {@code tson-bind}'s own {@code DataClassRecord} reflection, so
 * nothing about compiling a schema should require it either. This package depends on {@code
 * compiler} (for {@link TsonParserFactoryRegistry} itself, {@link
 * RecordParser#factory}, and {@link AtomTypeParser#ENUM_OBJECT_MODE}, each widened to {@code public}
 * specifically for this one external caller) -- but {@code compiler} has no
 * dependency on, or awareness of, this package at all. One-way, not circular.
 */
public final class TsonObjectBinding {

    private TsonObjectBinding() {
    }

    /**
     * Takes {@code schema} itself (unlike {@code TsonParserFactoryRegistry#dom()}, which needs no
     * schema at all), not just a {@link DataBindContext} -- needed so {@link
     * TsonObjectBinder#bind} can eagerly resolve and validate a Java class for every
     * {@code record}-shaped entry the schema actually declares, up front, rather than discovering a
     * missing binding lazily, one entry at a time, only once something happens to read it. Uses
     * {@link SchemaMetaTypeNameBinder}, the default {@code io.ltr8.tson.schema.meta} binder -- see
     * {@link #factoryRegistry(TsonSchema, DataBindContext, TsonTypeNameBinder)} to supply a
     * different one (e.g. for a schema binding to a caller's own Java library instead).
     */
    public static TsonParserFactoryRegistry factoryRegistry(TsonSchema schema, DataBindContext context) {
        return factoryRegistry(schema, context, SchemaMetaTypeNameBinder.INSTANCE);
    }

    /**
     * As {@link #factoryRegistry(TsonSchema, DataBindContext)}, with an explicit {@link
     * TsonTypeNameBinder} rather than the {@code schema.meta} default.
     */
    public static TsonParserFactoryRegistry factoryRegistry(TsonSchema schema, DataBindContext context,
                                                              TsonTypeNameBinder binder) {
        Map<String, DataClassRecord> bound = TsonObjectBinder.bind(schema, context, binder);
        ObjectRecordShapeFactory shapeFactory = new ObjectRecordShapeFactory(bound);
        return TsonParserFactoryRegistry.withoutRecordOrEnum()
                .register("record", RecordParser.factory(shapeFactory))
                .register("enum", AtomTypeParser.ENUM_OBJECT_MODE)
                .build();
    }
}
