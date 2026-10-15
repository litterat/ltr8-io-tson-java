package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.UnaryOperator;

/**
 * The kernel's {@code record} constructor's own vocabulary, resolved (Part 2 §5.2, §8.1): {@code
 * access_pattern}/{@code size_type} are fixed by the constructor itself ({@code NAMED}/{@code FIXED}) and
 * never appear in output; {@code groups} and {@code discriminators} are empty for a record with none.
 *
 * <p><b>{@code supertypes} and {@code subtypes} are {@code type}'s</b> (see {@link Type}): the complete IS-A chain
 * and its inverse, computed by the resolver and the linker. A fresh record and a subtracted one ([TSON-SCHEMA]
 * §5.9) have an empty chain, and a composition's chain names every operand and each operand's own chain. Inside
 * held template text the slot holds the parents as written, one of which may be an application ({@code <T>
 * result<T> & { ... }}); substitution reaches it with every other reference the body holds, so {@code ok<text>}
 * reaches an edge to {@code result<text>} and not to {@code result<int32>}.
 *
 * <p><b>{@code fields} is an ordered map from a field's name to its facts</b> ({@code field_map}, the kernel's
 * {@code !map { key_type: text  value_type: record_field  ordered: true }}), flattened: a composition's fields
 * are its operands' and its own, merged, so a consumer reading values against it never repeats the merge. The
 * name is the key and nowhere else, so the map's own key uniqueness refuses a name written twice by its
 * spelling; two spellings that are one name under the record's {@code name_type} are the linker's to refuse,
 * since only it can resolve the type. Declaration order is part of the value, and every reader, writer and
 * message follows it. The key is {@code text} rather than {@code field_name} because {@code name_type} decides
 * which names a record may declare.
 *
 * <p><b>{@code extension} states how the record may be realised</b> ({@link RecordExtensionType}) and is
 * {@link RecordExtensionType#OPEN} unless the record says otherwise. It is a kernel field rather than a
 * preserved annotation because it decides which values a position typed by this record admits, so erasing
 * it would change the type. Only {@code record} carries one: the other products are constructors, and a
 * constructor's instances are the closed forms a schema writes rather than a family an author extends.
 *
 * <p>Named {@code RecordBody}, not {@code Record} -- the kernel's own constructor is literally
 * called {@code record}, but a Java class named {@code Record} would collide, confusingly, with
 * {@code java.lang.Record} (the very language feature every type in this model is built from).
 */
@Typename(name = "record")
public record RecordBody(List<TypeRef> supertypes, List<String> subtypes, SequencedMap<String, RecordField> fields,
                          List<FieldGroup> groups, RecordExtensionType extension, List<String> discriminators,
                          @Field("name_type") Optional<String> nameType) implements Product {

    /**
     * The kernel's {@code field_name}, an identifier: a record's name type where neither it nor a supertype states
     * one (SPEC-FEEDBACK.md #10). {@link #nameType} keeps the difference between stating {@code field_name} and
     * stating nothing, because the two resolve in different namespaces: a name an author wrote resolves in the
     * author's own schema first, as an enum's does, and the absent default is the kernel's.
     */
    public static final String FIELD_NAME = "field_name";

    /**
     * <b>Absent and empty are the same list</b> for the four the kernel makes optional ({@code supertypes},
     * {@code subtypes}, {@code groups} and {@code discriminators}): a body resolved from source arrives with an
     * empty
     * list where one bound from a resolved-form document that omits the field arrives with {@code null},
     * and no rule distinguishes "no supertypes stated" from "an empty supertypes list". {@code fields} is
     * required and is deliberately not guarded -- an absent required field is a violation the reader
     * reports and abandons the construction over, so it never reaches here, and swallowing a {@code null}
     * would only turn some other defect into an empty record.
     */
    @Record
    public RecordBody {
        supertypes = Chains.copy(supertypes);
        subtypes = Chains.copy(subtypes);
        fields = Collections.unmodifiableSequencedMap(new LinkedHashMap<>(fields));
        groups = groups == null ? List.of() : List.copyOf(groups);
        discriminators = discriminators == null ? List.of() : List.copyOf(discriminators);
        nameType = nameType == null ? Optional.empty() : nameType;
    }

    /**
     * A field map from {@code Map.entry(name, field)} pairs, in the order given -- the low-ceremony way to state
     * a record's fields by hand. A name given twice is refused, as the kernel's map refuses a repeated key.
     */
    @SafeVarargs
    public static SequencedMap<String, RecordField> fieldMap(Map.Entry<String, RecordField>... entries) {
        SequencedMap<String, RecordField> fields = new LinkedHashMap<>();
        for (Map.Entry<String, RecordField> entry : entries) {
            if (fields.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException("field '" + entry.getKey() + "' is given twice");
            }
        }
        return fields;
    }

    /**
     * Order-sensitive over {@link #fields}: the kernel's {@code field_map} is {@code ordered: true}, so two records
     * declaring the same fields in different orders are two values, where {@code Map.equals} would call them one.
     * The override stands in for an ordered-map value type: a {@link SequencedMap} keeps order and compares as a
     * set, so the equality the kernel states has to be written here until a map type that owns it carries
     * {@code fields}.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordBody other
                && supertypes.equals(other.supertypes)
                && subtypes.equals(other.subtypes)
                && List.copyOf(fields.sequencedEntrySet()).equals(List.copyOf(other.fields.sequencedEntrySet()))
                && groups.equals(other.groups)
                && extension == other.extension
                && discriminators.equals(other.discriminators)
                && nameType.equals(other.nameType);
    }

    /** Consistent with {@link #equals}: {@link #fields} contributes in declaration order. */
    @Override
    public int hashCode() {
        return Objects.hash(supertypes, subtypes, List.copyOf(fields.sequencedEntrySet()), groups, extension,
                discriminators, nameType);
    }

    /** {@link #fields} with each field replaced by what {@code map} makes of it, names and order unchanged. */
    public SequencedMap<String, RecordField> mapFields(UnaryOperator<RecordField> map) {
        SequencedMap<String, RecordField> mapped = new LinkedHashMap<>();
        fields.forEach((name, field) -> mapped.put(name, map.apply(field)));
        return mapped;
    }

    /** The name type's name, for a message: the one stated, or {@link #FIELD_NAME}. */
    public String nameTypeName() {
        return nameType.orElse(FIELD_NAME);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public RecordBody(SequencedMap<String, RecordField> fields, List<FieldGroup> groups, RecordExtensionType extension,
                       List<String> discriminators, Optional<String> nameType) {
        this(List.of(), List.of(), fields, groups, extension, discriminators, nameType);
    }

    /** The same body with an empty chain, {@code discriminators} unstated and the default field name type. */
    public RecordBody(SequencedMap<String, RecordField> fields, List<FieldGroup> groups,
                       RecordExtensionType extension) {
        this(fields, groups, extension, List.of(), Optional.empty());
    }

    /** A fresh record body: no supertypes, no field groups, just plain fields, extensible. */
    public static RecordBody of(SequencedMap<String, RecordField> fields) {
        return new RecordBody(fields, List.of(), RecordExtensionType.OPEN);
    }

    @Override
    public RecordBody withChain(List<TypeRef> supertypes, List<String> subtypes) {
        return new RecordBody(supertypes, subtypes, fields, groups, extension, discriminators, nameType);
    }

    @Override
    public RecordBody withSupertypes(List<String> supertypes) {
        return withChain(supertypes.stream().map(TypeRef::of).toList(), subtypes);
    }

    /** This body with {@code extension} replaced -- every other facet, the chain included, unchanged. */
    public RecordBody withExtension(RecordExtensionType extension) {
        return new RecordBody(supertypes, subtypes, fields, groups, extension, discriminators, nameType);
    }

    /** This body with {@code fields} replaced -- every other facet, the chain included, unchanged. */
    public RecordBody withFields(SequencedMap<String, RecordField> fields) {
        return new RecordBody(supertypes, subtypes, fields, groups, extension, discriminators, nameType);
    }
}
