package io.ltr8.tson.parser.resolver.schema.compiled;

import java.util.Map;

/**
 * The default {@link TypeNameBinder} for {@code io.ltr8.tson.schema.meta} -- a fixed namespace
 * plus a snake_case-to-PascalCase mangling of the schema type name (e.g. {@code "integer_size"} ->
 * {@code IntegerSize}, {@code "atom_specification"} -> {@code AtomSpecification}), which holds
 * directly for every genuine constraint-vocabulary/helper record in that package, with one
 * confirmed exception (found by actually running this against the real, registered
 * meta-kernel.tn1 fixture, not guessed in advance): {@link #ALIASES}.
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
 * array_max}/{@code array_ranged} are parameterized template constructors that share {@code
 * array}'s own resolved shape rather than declaring one of their own (see {@code
 * ParserFactoryRegistry}'s own Javadoc: "{@code set} was never a distinct resolved shape, only a
 * distinct declared name") -- their own field set is identical to {@code array}'s (refinement never
 * adds or removes fields, only tightens values), so they alias to the same {@code ArrayBody} target
 * rather than needing one of their own.
 *
 * <p><b>A handful of real entries resolve to a genuine Java class that isn't a record at all</b> --
 * {@code atom}/{@code product}/{@code sum}/{@code top} (meta-kernel's own empty-bodied base-kind
 * declarations) mangle to the real, sealed marker interfaces {@link
 * io.ltr8.tson.schema.meta.Atom}/{@link io.ltr8.tson.schema.meta.Product}/{@link
 * io.ltr8.tson.schema.meta.Sum}/{@link io.ltr8.tson.schema.meta.Top}, and {@code type_argument}
 * mangles to {@link io.ltr8.tson.schema.meta.TypeArgument}, deliberately a sealed interface, not a
 * plain record (see that class's own Javadoc on the mutual-recursion trap that forced this). This
 * binder resolves the class either way -- it isn't this class's job to decide whether a resolved
 * class is usable as a record; see {@link ObjectRecordShapeFactory#validate} for why a non-record
 * result there is treated as "doesn't apply" rather than a binding failure.
 */
public final class SchemaMetaTypeNameBinder implements TypeNameBinder {

    public static final SchemaMetaTypeNameBinder INSTANCE = new SchemaMetaTypeNameBinder();

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
            Map.entry("array_ranged", "array_body"));

    @Override
    public Class<?> resolve(String schemaTypeName) throws ClassNotFoundException {
        String lookupName = ALIASES.getOrDefault(schemaTypeName, schemaTypeName);
        return Class.forName(NAMESPACE + "." + mangle(lookupName));
    }

    private static String mangle(String snakeCase) {
        StringBuilder result = new StringBuilder(snakeCase.length());
        boolean capitalizeNext = true;
        for (char c : snakeCase.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(Character.toLowerCase(c));
            }
        }
        return result.toString();
    }
}
