package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;

import java.util.Map;
import java.util.Set;

/**
 * The {@link DataNameBinder} object-binding mode builds its default {@code DataBindContext}
 * against (see {@link #defaultContext}) -- a fixed namespace plus a snake_case-to-PascalCase
 * mangling of the schema type name (e.g. {@code "integer_size"} -> {@code IntegerSize}, {@code
 * "atom_specification"} -> {@code AtomSpecification}), which holds directly for every genuine
 * constraint-vocabulary/helper record in that package, with one confirmed exception (found by
 * actually running this against the real, registered meta-kernel.tn1 fixture, not guessed in
 * advance): {@link #ALIASES}.
 *
 * <p>Meta-kernel's own description of a composite constructor's shape (e.g. {@code record =>
 * ~product & { fields: ... groups: ... } }) is structurally identical to the {@code *Body} class
 * that represents a *bound instance* of that same constructor ({@link
 * io.ltr8.tson.schema.meta.RecordBody}, {@link io.ltr8.tson.schema.meta.ArrayBody}, {@link
 * io.ltr8.tson.schema.meta.MapBody}, {@link io.ltr8.tson.schema.meta.TupleBody}, {@link
 * io.ltr8.tson.schema.meta.EnumBody}, {@link io.ltr8.tson.schema.meta.ChoiceBody}) -- but each of
 * those classes' own {@code @Typename} is the *bare* constructor name (matching the instance side,
 * e.g. {@code RecordBody}'s own is {@code "record"}, not {@code "record_body"}), so this forward
 * (schema-name -> Class) direction needs the {@code "_body"} suffix added explicitly; nothing
 * recovers it mechanically from the bare name alone. {@code set}/{@code array_min}/{@code
 * array_max}/{@code array_ranged}/{@code vector} are parameterized template constructors that share
 * {@code array}'s own resolved shape rather than declaring one of their own -- their own field set
 * is identical to {@code array}'s (refinement never adds or removes fields, only tightens values),
 * so they alias to the same {@code ArrayBody} target rather than needing one of their own.
 *
 * <p><b>A handful of real entries resolve to a genuine Java class that isn't a record at all</b> --
 * {@code atom}/{@code product}/{@code sum}/{@code top} (meta-kernel's own empty-bodied base-kind
 * declarations) mangle to the real, sealed marker interfaces {@link
 * io.ltr8.tson.schema.meta.Atom}/{@link io.ltr8.tson.schema.meta.Product}/{@link
 * io.ltr8.tson.schema.meta.Sum}/{@link io.ltr8.tson.schema.meta.Top}, and {@code type_argument}
 * mangles to {@link io.ltr8.tson.schema.meta.TypeArgument}, deliberately a sealed interface, not a
 * plain record (see that class's own Javadoc on the mutual-recursion trap that forced this). This
 * binder resolves the class either way -- it isn't this class's job to decide whether a resolved
 * class is usable as a record; a caller resolving one of these against a {@code Record*Reader} sees
 * that decision surface as an ordinary compile-time failure for that one entry instead.
 */
public final class SchemaMetaNameBinder {

    private SchemaMetaNameBinder() {
    }

    private static final String NAMESPACE = "io.ltr8.tson.schema.meta";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("record", "record_body"),
            Map.entry("array", "array_body"),
            Map.entry("map", "map_body"),
            Map.entry("tuple", "tuple_body"),
            Map.entry("choice", "choice_body"),
            Map.entry("enum", "enum_body"),
            Map.entry("set", "array_body"),
            Map.entry("array_min", "array_body"),
            Map.entry("array_max", "array_body"),
            Map.entry("array_ranged", "array_body"),
            Map.entry("vector", "array_body"),
            Map.entry("binary", "binary_type"),
            Map.entry("datetime_type", "date_time_type"),
            Map.entry("field_name", "token"),
            Map.entry("type_name", "token"),
            Map.entry("param_name", "token"));
            ;

    public static final DataNameBinder INSTANCE = new DataNameBinder.DefaultDataNameBinder(Set.of(NAMESPACE), ALIASES);

    /**
     * The {@code io.ltr8.tson.schema.meta} default -- {@link TsonAtomContext}'s own built-in atom
     * registrations, plus a {@link DataNameBinder} scoped to {@link #INSTANCE}'s own namespace/alias
     * table. A {@link DataNameBinder} is fixed at a {@link DataBindContext}'s own construction, so
     * it can't be attached after the fact -- a caller binding their own schema to their own Java
     * library builds their own {@link DataBindContext} instead (typically {@link
     * TsonAtomContext#registerDefaults} applied to a {@link DataBindContext.Builder} configured with
     * their own {@link DataNameBinder}) and passes it to {@link ValueReaderFactoryRegistry#bind}
     * directly.
     */
    public static DataBindContext defaultContext() {
        return TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(INSTANCE).build());
    }
}
