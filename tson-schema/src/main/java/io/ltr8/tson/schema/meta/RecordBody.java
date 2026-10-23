package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

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
 * <p><b>{@code parameters}, where not empty, make the record a template</b> ([TSON-SCHEMA] §5.10) and a family
 * base ({@link OpenEntry}): its field types and values may name the parameters, and the rest of the record is what
 * every application shares. Its {@code extension} is each application's; the base's own is ABSTRACT by
 * construction. A closed record declares none.
 *
 * <p><b>{@code chain} is {@code chained}'s</b> (see {@link TypeChain}): the complete IS-A chain and its inverse,
 * computed by the resolver and the linker. A fresh record and a subtracted one ([TSON-SCHEMA] §5.9) have an empty
 * chain, and a composition's chain names every operand and each operand's own chain. A record template keeps its
 * parents as written, one of which may be an application ({@code <T> result<T> & { ... }}); substitution
 * reaches it with every other reference the record holds, so {@code ok<text>} reaches an edge to {@code
 * result<text>} and not to {@code result<int32>}.
 *
 * <p><b>{@code fields} is a value of the kernel's {@code field_map}</b>, the namespace type mapping a field's name
 * to its facts in declaration order ({@link Namespace} typed {@value #FIELD_MAP}), flattened: a composition's fields
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
public record RecordBody(TypeChain chain, Namespace<RecordField> fields,
                          List<FieldGroup> groups, RecordExtensionType extension, List<String> discriminators,
                          @Field("name_type") Optional<String> nameType,
                          List<TemplateParam> parameters) implements Product {

    /**
     * The kernel's {@code field_name}, an identifier: a record's name type where neither it nor a supertype states
     * one (SPEC-FEEDBACK.md #10). {@link #nameType} keeps the difference between stating {@code field_name} and
     * stating nothing, because the two resolve in different namespaces: a name an author wrote resolves in the
     * author's own schema first, as an enum's does, and the absent default is the kernel's.
     */
    public static final String FIELD_NAME = "field_name";

    /** The kernel's {@code field_map}, the namespace type a record's {@link #fields} are a value of. */
    public static final String FIELD_MAP = "field_map";

    /**
     * <b>Absent and empty are the same value</b> for the three the kernel makes optional ({@code chain}, {@code
     * groups} and {@code discriminators}): a body resolved from source arrives with an empty one where one bound
     * from a resolved-form document that omits the field arrives with {@code null}, and no rule distinguishes
     * "nothing stated" from "an empty list". {@code fields} is
     * required and is deliberately not guarded -- an absent required field is a violation the reader
     * reports and abandons the construction over, so it never reaches here, and swallowing a {@code null}
     * would only turn some other defect into an empty record.
     */
    @Record
    public RecordBody {
        chain = TypeChain.orEmpty(chain);
        Objects.requireNonNull(fields, "fields");
        groups = groups == null ? List.of() : List.copyOf(groups);
        discriminators = discriminators == null ? List.of() : List.copyOf(discriminators);
        nameType = nameType == null ? Optional.empty() : nameType;
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    /** A closed record: one that declares no parameters. */
    public RecordBody(TypeChain chain, Namespace<RecordField> fields, List<FieldGroup> groups,
                      RecordExtensionType extension, List<String> discriminators, Optional<String> nameType) {
        this(chain, fields, groups, extension, discriminators, nameType, List.of());
    }

    /**
     * A field map from {@code Map.entry(name, field)} pairs, in the order given -- the low-ceremony way to state
     * a record's fields by hand. A name given twice is refused, as the kernel's map refuses a repeated key.
     */
    @SafeVarargs
    public static Namespace<RecordField> fieldMap(Map.Entry<String, RecordField>... entries) {
        SequencedMap<String, RecordField> fields = new LinkedHashMap<>();
        for (Map.Entry<String, RecordField> entry : entries) {
            if (fields.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException("field '" + entry.getKey() + "' is given twice");
            }
        }
        return fieldMap(fields);
    }

    /** {@code fields}, in its order, as a value of {@value #FIELD_MAP}. */
    public static Namespace<RecordField> fieldMap(SequencedMap<String, RecordField> fields) {
        return Namespace.of(FIELD_MAP, fields);
    }

    /**
     * Order-sensitive over {@link #fields}: the kernel's {@code field_map} is {@code ordered: true}, so two records
     * declaring the same fields in different orders are two values, where {@link Namespace}'s equality, which
     * leaves order to the constructor, would call them one.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordBody other
                && chain.equals(other.chain)
                && fields.equals(other.fields)
                && List.copyOf(fields.entrySet()).equals(List.copyOf(other.fields.entrySet()))
                && groups.equals(other.groups)
                && extension == other.extension
                && discriminators.equals(other.discriminators)
                && nameType.equals(other.nameType)
                && parameters.equals(other.parameters);
    }

    /** Consistent with {@link #equals}: {@link #fields} contributes in declaration order. */
    @Override
    public int hashCode() {
        return Objects.hash(chain, List.copyOf(fields.entrySet()), groups, extension,
                discriminators, nameType, parameters);
    }

    /**
     * {@link #fields} with each field replaced by what {@code map} makes of it, names, order and key annotations
     * unchanged.
     */
    public Namespace<RecordField> mapFields(UnaryOperator<RecordField> map) {
        AnnotatedMap<String, RecordField> mapped = new AnnotatedMap<>();
        fields.forEach((name, field) -> mapped.put(name, map.apply(field), fields.getAnnotations(name)));
        return new Namespace<>(fields.type(), mapped);
    }

    /** The name type's name, for a message: the one stated, or {@link #FIELD_NAME}. */
    public String nameTypeName() {
        return nameType.orElse(FIELD_NAME);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public RecordBody(Namespace<RecordField> fields, List<FieldGroup> groups, RecordExtensionType extension,
                       List<String> discriminators, Optional<String> nameType) {
        this(TypeChain.EMPTY, fields, groups, extension, discriminators, nameType);
    }

    /** The same body with an empty chain, {@code discriminators} unstated and the default field name type. */
    public RecordBody(Namespace<RecordField> fields, List<FieldGroup> groups,
                       RecordExtensionType extension) {
        this(fields, groups, extension, List.of(), Optional.empty());
    }

    /** A fresh record body: no supertypes, no field groups, just plain fields, extensible. */
    public static RecordBody of(Namespace<RecordField> fields) {
        return new RecordBody(fields, List.of(), RecordExtensionType.OPEN);
    }

    @Override
    public RecordBody withChain(TypeChain chain) {
        return new RecordBody(chain, fields, groups, extension, discriminators, nameType, parameters);
    }

    @Override
    public RecordBody withSupertypes(List<String> supertypes) {
        return withChain(chain.withSupertypes(supertypes));
    }

    /** This body with {@code extension} replaced -- every other facet, the chain included, unchanged. */
    public RecordBody withExtension(RecordExtensionType extension) {
        return new RecordBody(chain, fields, groups, extension, discriminators, nameType, parameters);
    }

    /** This body with {@code fields} replaced -- every other facet, the chain included, unchanged. */
    public RecordBody withFields(Namespace<RecordField> fields) {
        return new RecordBody(chain, fields, groups, extension, discriminators, nameType, parameters);
    }

    /** This body declaring {@code parameters} -- open where the list is not empty ([TSON-SCHEMA] §5.10). */
    public RecordBody withParameters(List<TemplateParam> parameters) {
        return new RecordBody(chain, fields, groups, extension, discriminators, nameType, parameters);
    }

    /** The parameter names, in declaration order -- {@code []} for a closed record. */
    public List<String> parameterNames() {
        return parameters.stream().map(TemplateParam::name).toList();
    }
}
