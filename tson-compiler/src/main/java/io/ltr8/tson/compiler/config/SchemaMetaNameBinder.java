package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@link DataNameBinder} object-binding mode builds its default {@code DataBindContext}
 * against (see {@link #defaultContext}) -- a fixed namespace plus a snake_case-to-PascalCase
 * mangling of the schema type name (e.g. {@code "integer_size"} -> {@code IntegerSize}, {@code
 * "record_field"} -> {@code RecordField}), which holds directly for every genuine
 * constraint-vocabulary/helper record in that package, with one confirmed exception (found by
 * actually running this against the real, registered meta-kernel.tn fixture, not guessed in
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
 * recovers it mechanically from the bare name alone. {@code set} is a constructor that shares {@code
 * array}'s own resolved shape rather than declaring one of its own -- its own field set
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
 *
 * <p><b>The namespace is fixed; the binder is not the last word.</b> A meta-schema of a consumer's own may
 * declare constructors the kernel never heard of, and their instances bind to that consumer's classes --
 * {@link #contextExtendedWith} is how those names join this one's, by composition rather than replacement.
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
            Map.entry("binary", "binary_type"),
            Map.entry("datetime_type", "date_time_type"),
            Map.entry("field_name", "identifier"),
            Map.entry("type_name", "identifier"),
            Map.entry("param_name", "identifier"));
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
        return context(INSTANCE);
    }

    /**
     * {@link #defaultContext()} extended with the names a consumer's own <b>meta layer</b> adds -- the
     * context to resolve a schema against when its governing meta declares constructors of its own
     * ({@code operation => ~data & { ... }}), whose instances bind to that consumer's Java classes.
     * {@code TsonConfig.metaNameBinder} is the front-door route to it.
     *
     * <p>Composition, never replacement: {@link #INSTANCE} answers first and {@code additional} is asked
     * only for a name the kernel's own vocabulary does not know. So a consumer cannot shadow {@code
     * record}/{@code enum}, and cannot lose {@link TsonAtomContext}'s registrations by building a context
     * that forgets them -- what the extension adds is names, and nothing else.
     *
     * <p>This is the whole seam. A meta-layer constructor needs three things and no more: the meta schema
     * declaring it, a class carrying {@code @Typename} for it, and a binder that can find that class.
     */
    public static DataBindContext contextExtendedWith(DataNameBinder additional) {
        return context(extendedWith(additional));
    }

    /**
     * {@link #INSTANCE} first, {@code additional} for anything it does not resolve -- the binder half of
     * {@link #contextExtendedWith}, for a caller assembling a {@link DataBindContext} themselves.
     *
     * <p>A {@link DataBindException} from {@code additional} propagates as it stands: it names the packages
     * that consumer's own binder searched, which is where a name the kernel does not declare was expected
     * to be.
     */
    public static DataNameBinder extendedWith(DataNameBinder additional) {
        Objects.requireNonNull(additional, "additional");
        return name -> {
            try {
                return INSTANCE.resolve(name);
            } catch (DataBindException notKernelVocabulary) {
                return additional.resolve(name);
            }
        };
    }

    private static DataBindContext context(DataNameBinder binder) {
        return TsonAtomContext.registerDefaults(DataBindContext.builder().nameBinder(binder).build());
    }
}
