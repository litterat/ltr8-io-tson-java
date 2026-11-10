package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The kernel's {@code record_template} constructor's vocabulary, resolved ([TSON-SCHEMA] §5.10, §8.1):
 * a record body over {@link #parameters}, whose fields are {@link RecordTemplateField}s and may carry them. Each
 * application substitutes its arguments for the parameters and closes to a {@link RecordBody}.
 *
 * <p><b>Not a type.</b> No position names a template bare (§5.10): only an application of it is a type, a value of
 * which is read against the closed record the application denotes. So nothing reads or writes a value against a
 * template and no reader or writer is built for one, and it is its own Java type: code that builds a record reader
 * takes a {@link RecordBody} and cannot be handed a template. An application's IS-A edges are those its written
 * parents reach, never the template itself.
 *
 * <p><b>A parameter stands in two places</b>: a field's {@code type_param}, or an argument of an application in a
 * field's {@code type} or in a parent ({@code <T> result<T> & { ... }}). The rest of the template is what every
 * application shares. A field's value is never a parameter, a literal {@code value} needs a closed type, and every field
 * {@link #discriminators} names is typed by no parameter, so a selector means the same in every application; both
 * are the author's rules, refused where the declaration resolves. What this value refuses itself is a label naming
 * no parameter it declares.
 *
 * <p>{@link #extension}, {@link #discriminators}, {@link #groups} and {@link #nameType} are each application's,
 * unchanged by substitution: an abstract template's application is an abstract record, the base of the declarations
 * that compose it. The chain keeps the parents as written, and {@link #fields} is flattened, as a record's is.
 */
@Typename(name = "meta.record_template")
public record RecordTemplateBody(TypeChain chain, AnnotatedMap<String, RecordTemplateField> fields,
                                 List<FieldGroup> groups, RecordExtensionType extension, List<String> discriminators,
                                 @Field("name_type") Optional<String> nameType,
                                 List<TemplateParam> parameters) implements Product {

    /** The kernel's {@code record_template_field_map}, the ordered map type a template's {@link #fields} are a value of. */
    public static final String FIELD_MAP = "meta.record_template_field_map";

    /** Absent and empty are one value for the optional lists, as on {@link RecordBody}. */
    @Record
    public RecordTemplateBody {
        chain = TypeChain.orEmpty(chain);
        fields = AnnotatedMap.copyOf(Objects.requireNonNull(fields, "fields"));
        groups = groups == null ? List.of() : List.copyOf(groups);
        extension = extension == null ? RecordExtensionType.OPEN : extension;
        discriminators = discriminators == null ? List.of() : List.copyOf(discriminators);
        nameType = nameType == null ? Optional.empty() : nameType;
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("a record template declares at least one parameter, and a record "
                    + "with none is a closed record (§5.10)");
        }
        List<String> names = parameters.stream().map(TemplateParam::name).toList();
        fields.forEach((name, field) -> {
            field.typeParam().filter(p -> !names.contains(p)).ifPresent(p -> {
                throw new IllegalArgumentException("field '" + name + "' names '" + p + "' as its type parameter, "
                        + "and the template declares " + names);
            });
        });
    }

    /**
     * What this template states at the record level ({@link RecordHead}): each application's.
     */
    public RecordHead head() {
        return new RecordHead(nameType, Collections.unmodifiableSet(fields.keySet()), discriminators, groups, extension);
    }

    /** The parameter names, in declaration order. */
    public List<String> parameterNames() {
        return parameters.stream().map(TemplateParam::name).toList();
    }

    /**
     * Every type reference this template holds, in its fields' {@code type}s and its parents as written -- the one
     * place a parameter stands that is not a label, as an argument of an application. A {@code type_param} is no
     * reference, and a sibling reference names a field, never a parameter or a type, so neither is among them.
     */
    public List<TypeRef> references() {
        List<TypeRef> references = new ArrayList<>();
        fields.values().forEach(field -> field.type().ifPresent(type -> {
            if (type instanceof TypeRef reference) {
                references.add(reference);
            }
        }));
        references.addAll(chain.parents());
        return List.copyOf(references);
    }

    /**
     * {@link #fields} with each field replaced by what {@code map} makes of it, names, order and key annotations
     * unchanged.
     */
    public AnnotatedMap<String, RecordTemplateField> mapFields(UnaryOperator<RecordTemplateField> map) {
        AnnotatedMap<String, RecordTemplateField> mapped = new AnnotatedMap<>();
        fields.forEach((name, field) -> mapped.put(name, map.apply(field), fields.getAnnotations(name)));
        return AnnotatedMap.copyOf(mapped);
    }

    @Override
    public RecordTemplateBody withChain(TypeChain chain) {
        return new RecordTemplateBody(chain, fields, groups, extension, discriminators, nameType, parameters);
    }

    @Override
    public RecordTemplateBody withSupertypes(List<String> supertypes) {
        return withChain(chain.withSupertypes(supertypes));
    }

    /** This template with {@code extension} replaced -- every other facet unchanged. */
    public RecordTemplateBody withExtension(RecordExtensionType extension) {
        return new RecordTemplateBody(chain, fields, groups, extension, discriminators, nameType, parameters);
    }

    /** This template with {@code fields} replaced -- every other facet unchanged. */
    public RecordTemplateBody withFields(AnnotatedMap<String, RecordTemplateField> fields) {
        return new RecordTemplateBody(chain, fields, groups, extension, discriminators, nameType, parameters);
    }

    /** This template with {@code parameters} replaced -- how a declaration's written bounds are put on. */
    public RecordTemplateBody withParameters(List<TemplateParam> parameters) {
        return new RecordTemplateBody(chain, fields, groups, extension, discriminators, nameType, parameters);
    }

    /**
     * Order-sensitive over {@link #fields} and each key's annotations, as {@link RecordBody#equals} is: the kernel's
     * field map is {@code ordered: true}.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordTemplateBody other
                && chain.equals(other.chain)
                && List.copyOf(fields.entrySet()).equals(List.copyOf(other.fields.entrySet()))
                && keyAnnotations(fields).equals(keyAnnotations(other.fields))
                && groups.equals(other.groups)
                && extension == other.extension
                && discriminators.equals(other.discriminators)
                && nameType.equals(other.nameType)
                && parameters.equals(other.parameters);
    }

    /** Consistent with {@link #equals}: {@link #fields} contributes in declaration order. */
    @Override
    public int hashCode() {
        return Objects.hash(chain, List.copyOf(fields.entrySet()), groups, extension, discriminators, nameType,
                parameters);
    }

    private static Map<String, Annotations> keyAnnotations(AnnotatedMap<String, RecordTemplateField> fields) {
        Map<String, Annotations> annotated = new LinkedHashMap<>();
        for (String key : fields.annotatedKeys()) {
            annotated.put(key, fields.getAnnotations(key));
        }
        return annotated;
    }
}
