package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;
import io.ltr8.annotation.Unbound;
import io.ltr8.tson.base.SourcePosition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The kernel's {@code meta.record_template_field} ([TSON-SCHEMA] §5.10, §8.1, SPEC-FEEDBACK.md #19): a field of a
 * record template, a {@link RecordField}'s four facts where the type and the value may each be one of the
 * template's parameters, and the label says which. {@link #typeParam} names a parameter standing for the whole
 * type and {@link #valueParam} one standing for the whole value; {@link #type} and {@link #value} are as on a record
 * field. Exactly one of {@code type} and {@code type_param} is present, and at most one of {@code value} and {@code
 * value_param}: the kernel's two field groups.
 *
 * <p><b>A parameter may still stand inside {@code type}</b>, as an argument of an application ({@code list<T>}),
 * recognised there because its text resolves into the template's {@code parameters} (§5.10's shadowing rule). A
 * whole-type parameter is never spelled that way: {@code type: T} with {@code T} a parameter is
 * {@code type_param: T}, which is what lets {@code type} and {@code value} mean what they mean on a closed field.
 *
 * <p><b>A literal {@code value} needs a closed type</b>, so a field whose type is a parameter, or an application
 * over one, takes {@code value_param} or nothing. {@link RecordTemplateBody} checks it against the template's
 * parameters, which a field alone does not know.
 *
 * <p><b>An application substitutes every parameter away</b>, and each of its fields is then a {@link RecordField}:
 * {@code type_param} becomes {@code type} and {@code value_param} becomes {@code value}, carrying the argument, and
 * no other fact changes. So {@code optional}, {@code voidable} and {@code role} are the facts the modifier's
 * literal spelling takes (§5.7): {@code w?: T = P} is an optional FIXED field before and after.
 *
 * <p>{@code position} is {@code @Unbound} and, with {@code annotations}, outside equality, for
 * {@link RecordField}'s reasons.
 */
@Typename(name = "meta.record_template_field")
public record RecordTemplateField(Optional<TypeRef> type, @Field("type_param") Optional<String> typeParam,
                                  boolean optional, boolean voidable, FieldRole role, Optional<Token> value,
                                  @Field("value_param") Optional<String> valueParam, Annotations annotations,
                                  @Unbound Optional<SourcePosition> position) {

    @Record
    public RecordTemplateField {
        type = type == null ? Optional.empty() : type;
        typeParam = typeParam == null ? Optional.empty() : typeParam;
        value = value == null ? Optional.empty() : value;
        valueParam = valueParam == null ? Optional.empty() : valueParam;
        role = role == null ? FieldRole.FREE : role;
        annotations = annotations == null ? Annotations.empty() : annotations;
        position = position == null ? Optional.empty() : position;
        if (type.isPresent() == typeParam.isPresent()) {
            throw new IllegalArgumentException("a record template field states exactly one of 'type' and "
                    + "'type_param'");
        }
        if (value.isPresent() && valueParam.isPresent()) {
            throw new IllegalArgumentException("a record template field states at most one of 'value' and "
                    + "'value_param'");
        }
    }

    /** A closed field as a template field: its type and value as they are, labelling nothing. */
    public static RecordTemplateField of(RecordField field) {
        return new RecordTemplateField(Optional.of(field.type()), Optional.empty(), field.optional(),
                field.voidable(), field.role(), field.value(), Optional.empty(), field.annotations(),
                field.position());
    }

    /**
     * This field as the closed {@link RecordField} it is once no parameter is left in it -- what a closed record's
     * resolution and an application's substitution end in. A field still carrying a label is no record field.
     */
    public RecordField closed() {
        if (typeParam.isPresent() || valueParam.isPresent()) {
            throw new IllegalStateException("a field naming the parameter '" + typeParam.or(() -> valueParam)
                    .orElseThrow() + "' is a record template's, and a record's fields carry no parameter");
        }
        return new RecordField(type.orElseThrow(), optional, voidable, role, value, annotations, position);
    }

    /** What this field yields when a document never writes it, as {@link RecordField#omitted} answers it. */
    public RecordField.Omitted omitted(boolean groupMember) {
        return RecordField.omitted(optional, value.isPresent() || valueParam.isPresent(), groupMember);
    }

    /** These facts in the words a diagnostic uses, as {@link RecordField#describe} says them. */
    public String describe() {
        return RecordField.describe(optional, role);
    }

    /**
     * The parameter that types this field, whole or as an argument at any depth -- empty where the type means the
     * same thing in every application.
     */
    public Optional<String> parameterInType(List<String> parameters) {
        return typeParam.isPresent() ? typeParam : OpenEntry.parameterIn(type.orElseThrow(), parameters);
    }

    /** A copy of this field with {@code type} replaced -- every other component unchanged. */
    public RecordTemplateField withType(TypeRef type) {
        return new RecordTemplateField(Optional.of(type), Optional.empty(), optional, voidable, role, value,
                valueParam, annotations, position);
    }

    /** A copy of this field with its presence and role replaced -- every other component unchanged. */
    public RecordTemplateField withFacts(boolean optional, boolean voidable, FieldRole role) {
        return new RecordTemplateField(type, typeParam, optional, voidable, role, value, valueParam, annotations,
                position);
    }

    /** A copy of this field with {@code annotations} replaced -- every other component unchanged. */
    public RecordTemplateField withAnnotations(Annotations annotations) {
        return new RecordTemplateField(type, typeParam, optional, voidable, role, value, valueParam, annotations,
                position);
    }

    /** Excludes {@code annotations}/{@code position} -- neither changes a field's identity. */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordTemplateField other
                && type.equals(other.type)
                && typeParam.equals(other.typeParam)
                && optional == other.optional
                && voidable == other.voidable
                && role == other.role
                && value.equals(other.value)
                && valueParam.equals(other.valueParam);
    }

    /** Excludes {@code annotations}/{@code position} -- see {@link #equals}. */
    @Override
    public int hashCode() {
        return Objects.hash(type, typeParam, optional, voidable, role, value, valueParam);
    }
}
