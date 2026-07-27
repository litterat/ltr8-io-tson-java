package io.ltr8.tson.parser.binder;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.parser.base.TsonAtomContext;
import io.ltr8.tson.parser.compiler.AtomTypeParser;
import io.ltr8.tson.parser.compiler.RecordParser;
import io.ltr8.tson.parser.compiler.TsonParserFactoryRegistry;
import io.ltr8.tson.schema.TsonLinkedSchema;

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
     * The {@code io.ltr8.tson.schema.meta} default -- {@link TsonAtomContext}'s own built-in atom
     * registrations, plus a {@code DataNameBinder} scoped to {@link SchemaMetaNameBinder}'s own
     * namespace/alias table. {@link #factoryRegistry} needs a {@link DataBindContext} whose {@code
     * getDescriptor(String)} already resolves against that namespace -- a {@code DataNameBinder} is
     * fixed at a {@link DataBindContext}'s own construction, so it can't be attached after the fact,
     * which is why this exists as its own factory rather than something {@link #factoryRegistry}
     * could apply to an arbitrary caller-supplied context. A caller binding their own schema to
     * their own Java library builds their own {@link DataBindContext} instead (typically {@link
     * TsonAtomContext#registerDefaults} applied to a {@link DataBindContext.Builder} configured
     * with their own {@code DataNameBinder}) and passes it to {@link #factoryRegistry} directly.
     */
    public static DataBindContext defaultContext() {
        return TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(SchemaMetaNameBinder.INSTANCE).build());
    }

    /**
     * Takes {@code schema} itself (unlike {@code TsonParserFactoryRegistry#dom()}, which needs no
     * schema at all) -- needed so {@link TsonObjectBinder#bind} can eagerly resolve and validate a
     * Java class for every {@code record}-shaped entry the schema actually declares, up front,
     * rather than discovering a missing binding lazily, one entry at a time, only once something
     * happens to read it. {@code context} must already be able to resolve this schema's own type
     * names -- {@link #defaultContext()} for the {@code io.ltr8.tson.schema.meta} default, or a
     * caller's own similarly-configured {@link DataBindContext} for a schema binding to their own
     * Java library instead.
     */
    public static TsonParserFactoryRegistry factoryRegistry(TsonLinkedSchema schema, DataBindContext context) {
        TsonBoundSchema bound = TsonObjectBinder.bind(schema, context);
        ObjectRecordShapeFactory shapeFactory = new ObjectRecordShapeFactory(bound);
        return TsonParserFactoryRegistry.withoutRecordOrEnum()
                .register("record", RecordParser.factory(shapeFactory))
                .register("enum", AtomTypeParser.ENUM_OBJECT_MODE)
                .build();
    }
}
