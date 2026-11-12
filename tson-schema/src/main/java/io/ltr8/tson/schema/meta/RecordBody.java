package io.ltr8.tson.schema.meta;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * The kernel's {@code record} constructor's own vocabulary, resolved (Part 2 §5.2, §5.10, §8.1): {@code
 * access_pattern}/{@code size_type} are fixed by the constructor itself ({@code NAMED}/{@code FIXED}) and
 * never appear in output; {@code parameters}, {@code groups} and {@code discriminators} are empty for a record with
 * none.
 *
 * <p><b>Closed or open.</b> A record with no {@link #parameters} is closed: it is what readers and writers are built
 * for. A record with parameters is an <em>open record</em>, a
 * template ([TSON-SCHEMA] §5.10): a reference names it only as the head of an application, never bare, and each
 * application substitutes its arguments for the parameters and closes to a record without them. So no closed
 * position, and no member set over {@code meta.ref<meta.record>}, reaches an open record, and nothing builds a reader
 * or a writer for one ({@link #isOpen}).
 *
 * <p><b>Where a parameter stands is this value's to state.</b> A parameter is written as its name, a reference value
 * looked up in {@link #parameters} before the namespace, and stands at a field's {@code type}, at a slot of a type
 * written in place there at any depth ({@code { meta: { cursor: T } }}, {@code [T]}), or as an argument of an
 * application at any of those or in a parent ({@code <T> result<T> & { ... }}). The constructor turns every such name
 * into a {@link ParamRef} as the record is built ({@link TypeSlots}), so a record bound from a resolved form and one
 * the resolver builds are one value, and refuses a {@link ParamRef} its parameters do not declare. A field's {@code
 * value} is a literal and never a parameter; that, and a parameter at any other slot, is the author's rule, refused
 * where the declaration resolves.
 *
 * <p><b>A closed record does not judge its parameters.</b> One written in place inside an open record holds that
 * record's parameters, and only the open record can tell them from a stray one; closing an application substitutes
 * every one away ({@code TemplateMaterialiser}).
 *
 * <p><b>{@code chain} is {@code chained}'s</b> (see {@link TypeChain}): the complete IS-A chain and its inverse,
 * computed by the resolver and the linker. A fresh record and a subtracted one ([TSON-SCHEMA] §5.9) have an empty
 * chain, and a composition's chain names every operand and each operand's own chain. An open record's chain keeps
 * its parents as written, which each application substitutes and completes.
 *
 * <p><b>{@code fields} is a value of the kernel's {@code field_map}</b> ({@value #FIELD_MAP}), the ordered map from
 * a field's name, as {@code text}, to its facts in declaration order, flattened: a composition's fields are its
 * operands' and its own, merged, so a consumer reading values against it never repeats the merge. The name is the
 * key and nowhere else, so the map's own key uniqueness refuses a name written twice by its spelling; two spellings
 * that are one name under the record's {@code name_type} are the linker's to refuse, since only it can resolve the
 * type. Declaration order is part of the value, and every reader, writer and message follows it. A field name is
 * text within its own record, judged by {@code name_type} as {@code discriminators} and the groups are, and no
 * namespace key: a qualified name never selects a field declaration.
 *
 * <p><b>{@code extension} states how the record may be realised</b> ({@link RecordExtensionType}) and is
 * {@link RecordExtensionType#OPEN} unless the record says otherwise. It is a kernel field rather than a
 * preserved annotation because it decides which values a position typed by this record admits, so erasing
 * it would change the type. Only {@code record} carries one: the other products are constructors, and a
 * constructor's instances are the closed forms a schema writes rather than a family an author extends. On an open
 * record it, the groups, the discriminators and the name type are each application's, unchanged by substitution.
 *
 * <p>Named {@code RecordBody}, not {@code Record} -- the kernel's own constructor is literally
 * called {@code record}, but a Java class named {@code Record} would collide, confusingly, with
 * {@code java.lang.Record} (the very language feature every type in this model is built from).
 */
@Typename(name = "meta.record")
public record RecordBody(TypeChain chain, List<Param> parameters, AnnotatedMap<String, RecordField> fields,
                          List<FieldGroup> groups, RecordExtensionType extension, List<String> discriminators,
                          @Field("name_type") Optional<String> nameType) implements Product {

    /**
     * The kernel's {@code meta.field_name}, an identifier: a record's name type where neither it nor a supertype states
     * one ([TSON-SCHEMA] §5.2). {@link #nameType} keeps the difference between stating {@code meta.field_name} and
     * stating nothing, because the two resolve in different namespaces: a name an author wrote resolves in the
     * author's own schema first, as an enum's does, and the absent default is the kernel's.
     */
    public static final String FIELD_NAME = "meta.field_name";

    /** The kernel's {@code field_map}, the ordered map type a record's {@link #fields} are a value of. */
    public static final String FIELD_MAP = "meta.field_map";

    /**
     * <b>Absent and empty are the same value</b> for the four the kernel makes optional ({@code chain}, {@code
     * parameters}, {@code groups} and {@code discriminators}): a body resolved from source arrives with an empty one
     * where one bound from a resolved-form document that omits the field arrives with {@code null}, and no rule
     * distinguishes "nothing stated" from "an empty list". {@code fields} is
     * required and is deliberately not guarded -- an absent required field is a violation the reader
     * reports and abandons the construction over, so it never reaches here, and swallowing a {@code null}
     * would only turn some other defect into an empty record.
     *
     * <p>With parameters, each name they declare among the fields' types, at any depth, and the parents' arguments
     * becomes the {@link ParamRef} it is; a {@link ParamRef} they do not declare, or a parameter declared twice, is
     * refused.
     */
    @Record
    public RecordBody {
        chain = TypeChain.orEmpty(chain);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        fields = AnnotatedMap.copyOf(Objects.requireNonNull(fields, "fields"));
        groups = groups == null ? List.of() : List.copyOf(groups);
        discriminators = discriminators == null ? List.of() : List.copyOf(discriminators);
        nameType = nameType == null ? Optional.empty() : nameType;
        Set<String> declared = new LinkedHashSet<>();
        for (Param parameter : parameters) {
            if (!declared.add(parameter.name())) {
                throw new IllegalArgumentException("parameter '" + parameter.name() + "' is declared twice");
            }
        }
        if (!declared.isEmpty()) {
            TypeSlots.Rewrite scope = reference -> scoped(reference, declared);
            chain = chain.withParents(chain.parents().stream().map(parent -> TypeSlots.map(parent, scope)).toList());
            fields = scopedFields(fields, declared);
            for (TypeRef reference : references(chain, fields)) {
                requireDeclared(reference, declared);
            }
        }
    }

    /**
     * The same record closed, with no parameters: every caller building a record that is no template, its chain
     * empty or already placed.
     */
    public RecordBody(TypeChain chain, AnnotatedMap<String, RecordField> fields, List<FieldGroup> groups,
                      RecordExtensionType extension, List<String> discriminators, Optional<String> nameType) {
        this(chain, List.of(), fields, groups, extension, discriminators, nameType);
    }

    /**
     * A field map from {@code Map.entry(name, field)} pairs, in the order given -- the low-ceremony way to state
     * a record's fields by hand. A name given twice is refused, as the kernel's map refuses a repeated key.
     */
    @SafeVarargs
    public static AnnotatedMap<String, RecordField> fieldMap(Map.Entry<String, RecordField>... entries) {
        SequencedMap<String, RecordField> fields = new LinkedHashMap<>();
        for (Map.Entry<String, RecordField> entry : entries) {
            if (fields.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalArgumentException("field '" + entry.getKey() + "' is given twice");
            }
        }
        return fieldMap(fields);
    }

    /** {@code fields}, in its order, as a value of {@value #FIELD_MAP}, an {@link AnnotatedMap}'s key annotations kept. */
    public static AnnotatedMap<String, RecordField> fieldMap(SequencedMap<String, RecordField> fields) {
        return AnnotatedMap.copyOf(fields);
    }

    /**
     * Order-sensitive over {@link #fields}, and over each key's annotations: the kernel's {@code field_map} is
     * {@code ordered: true}, so two records declaring the same fields in different orders are two values, where a
     * {@link Map}'s equality would call them one and ignore what is written on a key.
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof RecordBody other
                && chain.equals(other.chain)
                && parameters.equals(other.parameters)
                && List.copyOf(fields.entrySet()).equals(List.copyOf(other.fields.entrySet()))
                && keyAnnotations(fields).equals(keyAnnotations(other.fields))
                && groups.equals(other.groups)
                && extension == other.extension
                && discriminators.equals(other.discriminators)
                && nameType.equals(other.nameType);
    }

    /** Consistent with {@link #equals}: {@link #fields} contributes in declaration order. */
    @Override
    public int hashCode() {
        return Objects.hash(chain, parameters, List.copyOf(fields.entrySet()), groups, extension,
                discriminators, nameType);
    }

    private static Map<String, Annotations> keyAnnotations(AnnotatedMap<String, RecordField> fields) {
        Map<String, Annotations> annotated = new LinkedHashMap<>();
        for (String key : fields.annotatedKeys()) {
            annotated.put(key, fields.getAnnotations(key));
        }
        return annotated;
    }

    /**
     * {@link #fields} with each field replaced by what {@code map} makes of it, names, order and key annotations
     * unchanged.
     */
    public AnnotatedMap<String, RecordField> mapFields(UnaryOperator<RecordField> map) {
        AnnotatedMap<String, RecordField> mapped = new AnnotatedMap<>();
        fields.forEach((name, field) -> mapped.put(name, map.apply(field), fields.getAnnotations(name)));
        return AnnotatedMap.copyOf(mapped);
    }

    /** Whether this record declares parameters: an open record, a template, which no position names bare. */
    public boolean isOpen() {
        return !parameters.isEmpty();
    }

    /** The parameter names, in declaration order -- what an application's arity is checked against. */
    public List<String> parameterNames() {
        return parameters.stream().map(Param::name).toList();
    }

    /**
     * Every type reference this record holds, in its fields' {@code type}s at any depth of types written in place
     * ({@link TypeSlots}) and then its parents as written: the places a parameter stands, as a {@link ParamRef} itself
     * or among an application's arguments ({@link ApplyBody}).
     */
    public List<TypeRef> references() {
        return references(chain, fields);
    }

    private static List<TypeRef> references(TypeChain chain, AnnotatedMap<String, RecordField> fields) {
        List<TypeRef> references = new ArrayList<>();
        fields.values().forEach(field -> TypeSlots.forEach(field.type(), references::add));
        chain.parents().forEach(parent -> TypeSlots.forEach(parent, references::add));
        return references;
    }

    /** {@code reference} as the {@link ParamRef} it is where {@code declared} holds its name. */
    private static TypeRef scoped(TypeRef reference, Set<String> declared) {
        return reference instanceof NameRef name && declared.contains(name.name())
                ? new ParamRef(name.name(), name.annotations()) : reference;
    }

    private static AnnotatedMap<String, RecordField> scopedFields(AnnotatedMap<String, RecordField> fields,
                                                                  Set<String> declared) {
        AnnotatedMap<String, RecordField> scoped = new AnnotatedMap<>();
        TypeSlots.Rewrite scope = reference -> scoped(reference, declared);
        fields.forEach((name, field) -> scoped.put(name, field.withType(TypeSlots.map(field.type(), scope)),
                fields.getAnnotations(name)));
        return AnnotatedMap.copyOf(scoped);
    }

    /** Refuses {@code reference} where it is a {@link ParamRef} {@code declared} does not hold. */
    private static void requireDeclared(TypeRef reference, Set<String> declared) {
        if (reference instanceof ParamRef parameter && !declared.contains(parameter.name())) {
            throw new IllegalArgumentException("'" + parameter.name() + "' stands as a parameter, and the record "
                    + (declared.isEmpty() ? "declares none" : "declares " + declared));
        }
    }

    /** The name type's name, for a message: the one stated, or {@link #FIELD_NAME}. */
    public String nameTypeName() {
        return nameType.orElse(FIELD_NAME);
    }

    /** This body with an empty IS-A chain -- a value no resolver has placed in one. */
    public RecordBody(AnnotatedMap<String, RecordField> fields, List<FieldGroup> groups, RecordExtensionType extension,
                       List<String> discriminators, Optional<String> nameType) {
        this(TypeChain.EMPTY, fields, groups, extension, discriminators, nameType);
    }

    /** The same body with an empty chain, {@code discriminators} unstated and the default field name type. */
    public RecordBody(AnnotatedMap<String, RecordField> fields, List<FieldGroup> groups,
                       RecordExtensionType extension) {
        this(fields, groups, extension, List.of(), Optional.empty());
    }

    /** A fresh record body: no supertypes, no field groups, just plain fields, extensible. */
    public static RecordBody of(AnnotatedMap<String, RecordField> fields) {
        return new RecordBody(fields, List.of(), RecordExtensionType.OPEN);
    }

    @Override
    public RecordBody withChain(TypeChain chain) {
        return new RecordBody(chain, parameters, fields, groups, extension, discriminators, nameType);
    }

    @Override
    public RecordBody withSupertypes(List<String> supertypes) {
        return withChain(chain.withSupertypes(supertypes));
    }

    /** This body with {@code extension} replaced -- every other facet, the chain included, unchanged. */
    public RecordBody withExtension(RecordExtensionType extension) {
        return new RecordBody(chain, parameters, fields, groups, extension, discriminators, nameType);
    }

    /** This body with {@code fields} replaced -- every other facet, the chain included, unchanged. */
    public RecordBody withFields(AnnotatedMap<String, RecordField> fields) {
        return new RecordBody(chain, parameters, fields, groups, extension, discriminators, nameType);
    }

    /** This body with {@code parameters} replaced -- how a declaration's written bounds are put on. */
    public RecordBody withParameters(List<Param> parameters) {
        return new RecordBody(chain, parameters, fields, groups, extension, discriminators, nameType);
    }
}
