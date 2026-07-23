package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Typename;

import java.util.List;

/**
 * The kernel's {@code record} constructor's own vocabulary, resolved (Part 2 §5.2, §8.1): {@code
 * access_pattern}/{@code size_type} are fixed by the constructor itself (`NAMED`/`FIXED`) and never
 * appear in output; {@code supertypes} is populated only when the source composed with `&` (e.g.
 * `atom => top & {}`) -- empty here ideally means a fresh record with no listed supertypes, omitted
 * from output the same way; {@code groups} similarly for a record with no field groups. In
 * practice, bound through plain {@code TsonMapper.toTson} rather than a hand-written writer (see
 * {@code TypeDefinition}'s own Javadoc), both currently render as {@code []} rather than being
 * omitted when empty -- {@code tson-bind} doesn't support {@code Optional<List<T>>} record
 * components yet (only a bare, always-present {@code List} does), so there's no wrapper available
 * to opt into the omit-when-absent behavior non-list optional fields already get for free.
 *
 * <p>Named {@code RecordBody}, not {@code Record} -- the kernel's own constructor is literally
 * called {@code record}, but a Java class named {@code Record} would collide, confusingly, with
 * {@code java.lang.Record} (the very language feature every type in this model is built from).
 */
@Typename(name = "record")
public record RecordBody(List<String> supertypes, List<RecordField> fields, List<FieldGroup> groups)
        implements TypeBody {

    public RecordBody {
        supertypes = List.copyOf(supertypes);
        fields = List.copyOf(fields);
        groups = List.copyOf(groups);
    }

    /** A fresh record body: no supertypes, no field groups, just plain fields. */
    public static RecordBody of(List<RecordField> fields) {
        return new RecordBody(List.of(), fields, List.of());
    }
}
