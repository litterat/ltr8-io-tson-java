package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ReferenceValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.writer.DataClassObjectWriter;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.SiblingRef;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * How schema vocabulary is spelled as data, in both directions -- the one place that knows what a reference and an
 * application in a slot look like on the wire.
 *
 * <p><b>Why one class rather than a producer over here and a consumer over there.</b> A binding record is written
 * by two phases and read by three: {@code SchemaDesugarer} lifts a sugar form and {@code TemplateMaterialiser}
 * names a closed record by its content ({@link #recordWire}); {@code TemplateMaterialiser} closes the applications
 * a binding holds, {@code SyntheticMerge} asks whether one holds an application, and {@code ParameterTypes} walks
 * an open record's explicit payload for where its parameters stand. A second opinion about what an application looks
 * like is what makes one of those wrong.
 *
 * <p><b>Nothing here is canonical output, and {@code DataClassObjectWriter} cannot serve any of it.</b> That
 * writer's output is canonical-explicit and fully quoted, which is a different language from the one a binding is
 * named in: a name is an unquoted token. What is written here is what an author would have written -- an unquoted
 * token where the writer would quote, an application as the data grammar's reference, and nothing at all where the
 * constructor's own default says it.
 *
 * <p><b>One spelling per shape is the requirement, not the tidiness.</b> An entry name derives from what is
 * written ({@code SchemaDesugarer.internalName}), so a second spelling of one reference splits one type
 * across two entries. That is why {@link #refValue} is what every producer goes through, and why
 * {@link #applicationIn} -- its inverse -- lives beside it rather than in the phase that happens to read.
 */
final class WireForm {

    /** Writes a bound annotation value back as text, for {@link #annotationValue} to parse. */
    private static final DataClassObjectWriter ANNOTATION_WRITER = new DataClassObjectWriter();

    private WireForm() {
    }

    // ── The vocabulary's own member names ────────────────────────────────────────────────────────

    /** {@code meta.record_field.value}: §8.1's literal channel. */
    static final String VALUE = "value";

    /** {@code meta.record}: the constructor an open record applies, and an application of one closes to. */
    static final String RECORD = KernelNames.of("record");

    static final String RECORD_FIELD = KernelNames.of("record_field");

    /** {@code meta.record.parameters}: an open record's parameters, each a {@code meta.param}. */
    static final String PARAMETERS = "parameters";
    static final String NAME = "name";

    static final String FIELDS = "fields";
    static final String GROUPS = "groups";
    static final String MEMBERS = "members";
    static final String TYPE = "type";
    static final String OPTIONAL = "optional";
    static final String OPTIONAL_MEMBERS = "optional_members";
    static final String VOIDABLE = "voidable";
    static final String ROLE = "role";
    static final String CHAIN = "chain";
    static final String PARENTS = "parents";

    /**
     * {@code record.discriminators} -- the fields a sealed family dispatches on (§5.2), in the order their
     * pins are compared as a tuple. Written only where the record states one, the constructor's own default
     * being absent, on {@link #EXTENSION}'s terms.
     *
     * <p>Written for a closed record ({@link #recordWire}), where a minted member names none.
     */
    static final String DISCRIMINATORS = "discriminators";

    /**
     * {@code record.extension} -- how the record may be realised (§5.2). Written only where it is not
     * {@code OPEN}, the constructor's own default, so a closed record's binding record ({@link #recordWire}) states a
     * mark exactly when one was made.
     */
    static final String EXTENSION = "extension";

    /**
     * {@code record.name_type} -- the type a record's field names are values of ([TSON-SCHEMA] §5.2). Written
     * exactly where the record states one, so a binding keeps the difference between stating and not.
     */
    static final String NAME_TYPE = "name_type";

    // ── Building blocks ──────────────────────────────────────────────────────────────────────────

    /**
     * A {@code meta.field_group} as a binding writes it: {@code optional_members} only where it names a member, and
     * {@code optional} only where it is set.
     */
    static DataValue group(FieldGroup group, List<Annotation> annotations) {
        List<RecordValue.Field> fields = new ArrayList<>();
        fields.add(new RecordValue.Field(MEMBERS, plain(new ArrayValue(group.members().stream()
                .map(option -> plain(names(option))).toList()))));
        if (!group.optionalMembers().isEmpty()) {
            fields.add(new RecordValue.Field(OPTIONAL_MEMBERS, plain(names(group.optionalMembers()))));
        }
        if (group.optional()) {
            fields.add(nameField(OPTIONAL, "true"));
        }
        return plain(new RecordValue(fields), annotations);
    }

    /** A list of field names as the array of bare names a resolved document writes. */
    private static ArrayValue names(List<String> names) {
        return new ArrayValue(names.stream().map(name -> plain(declaredName(name))).toList());
    }

    /**
     * {@code record.fields} as a binding writes it: a map from each field's declared name ({@link
     * #declaredName}) to its {@code meta.record_field}, in declaration order. An empty one is the empty brace, which
     * is what the text parses back to.
     */
    static CoreValue fieldMap(List<MapValue.MapEntry> entries) {
        return entries.isEmpty() ? new EmptyBrace() : new MapValue(entries);
    }

    /** One {@link #fieldMap} entry: the field's declared name as the key, its facts as the value. */
    static MapValue.MapEntry fieldEntry(String name, DataValue field) {
        return new MapValue.MapEntry(new DataValue(List.of(), Optional.empty(), declaredName(name)), field);
    }

    /** A bare value in a field or element position -- no annotations and no type-ref of its own. */
    static DataValue plain(CoreValue value) {
        return plain(value, List.of());
    }

    /**
     * The same, carrying the annotations written on the construct it stands for. §6 puts a field's own
     * annotations on the {@code meta.record_field} in resolver output, and a binding reaches that through the
     * wire value, so they travel here rather than being re-attached after the fact.
     */
    static DataValue plain(CoreValue value, List<Annotation> annotations) {
        return new DataValue(annotations, Optional.empty(), value);
    }

    /**
     * A {@code meta.record_field}'s presence and role, each written only where it leaves the kernel's own default
     * ({@code optional: false}, {@code voidable: false}, {@code role: FREE}) -- the one spelling every producer
     * of a record binding shares, so two records stating one field state it alike.
     */
    static void addFacts(List<RecordValue.Field> members, boolean optional, boolean voidable, FieldRole role) {
        if (optional) {
            members.add(nameField(OPTIONAL, "true"));
        }
        if (voidable) {
            members.add(nameField(VOIDABLE, "true"));
        }
        if (role != FieldRole.FREE) {
            members.add(nameField(ROLE, role.name()));
        }
    }

    static RecordValue.Field nameField(String name, String text) {
        return new RecordValue.Field(name, plain(new TokenValue(text, TokenForm.UNQUOTED)));
    }

    /**
     * A declared field name -- in {@code fields}, a group, {@code discriminators} -- as an author writes one:
     * unquoted where it is an identifier, quoted where it is not. A record's names are its name type's values
     * ([TSON-SCHEMA] §5.2), so {@code "@id"} or {@code "first name"} is a field name, and written unquoted
     * it is not one token: the binding would not parse back.
     */
    static TokenValue declaredName(String name) {
        return new TokenValue(name, IdentifierGrammar.validate(name).isEmpty() ? TokenForm.UNQUOTED
                : TokenForm.SINGLE_LINE_QUOTED);
    }

    static TokenForm tokenForm(Token.Form form) {
        return switch (form) {
            case UNQUOTED -> TokenForm.UNQUOTED;
            case SINGLE_LINE_QUOTED -> TokenForm.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> TokenForm.MULTI_LINE_QUOTED;
        };
    }

    // ── Writing: a resolved value as the wire form it was written in ───────────────────────────

    /**
     * A closed record's binding record, written as an author would write it -- what a minted record's name derives
     * from ({@code TemplateMaterialiser.mintedRecord}, {@link DerivedName#ofBinding}), so a record a template
     * application closes to is named from its own content, the same spelling every other closed form is named by.
     */
    static DataValue recordWire(RecordBody body, Function<Object, DataValue> annotationValue) {
        List<MapValue.MapEntry> fields = new ArrayList<>();
        for (Map.Entry<String, RecordField> declared : body.fields().entrySet()) {
            RecordField field = declared.getValue();
            fields.add(fieldEntry(declared.getKey(), fieldWire(refValue(field.type()), field.optional(),
                    field.voidable(), field.role(),
                    field.value().map(token -> new TokenValue(token.text(), tokenForm(token.form()))),
                    annotations(field.annotations(), annotationValue))));
        }
        return recordWire(fields, body.chain().parents(), body.nameType(), body.groups(), body.discriminators(),
                body.extension());
    }

    /** One {@code meta.record_field} as {@link #recordWire} spells it. */
    private static DataValue fieldWire(CoreValue type, boolean optional, boolean voidable, FieldRole role,
                                       Optional<TokenValue> value, List<Annotation> annotations) {
        List<RecordValue.Field> members = new ArrayList<>();
        members.add(new RecordValue.Field(TYPE, plain(type)));
        addFacts(members, optional, voidable, role);
        value.ifPresent(token -> members.add(new RecordValue.Field(VALUE, plain(token))));
        return plain(new RecordValue(members), annotations);
    }

    /** The record-level members {@link #recordWire} writes around a record's fields. */
    private static DataValue recordWire(List<MapValue.MapEntry> fields, List<TypeRef> parents,
            Optional<String> nameType, List<FieldGroup> declaredGroups, List<String> discriminators,
            RecordExtensionType extension) {
        List<DataValue> groups = new ArrayList<>();
        for (FieldGroup group : declaredGroups) {
            groups.add(group(group, List.of()));
        }
        List<RecordValue.Field> binding = new ArrayList<>();
        if (!parents.isEmpty()) {
            // The one chain slot a binding writes, the parents as written (TypeChain.parents), through refValue like
            // every other reference.
            binding.add(new RecordValue.Field(CHAIN, plain(new RecordValue(List.of(new RecordValue.Field(PARENTS,
                    plain(new ArrayValue(parents.stream().map(parent -> plain(refValue(parent)))
                            .toList()))))))));
        }
        nameType.ifPresent(stated -> binding.add(nameField(NAME_TYPE, stated)));
        binding.add(new RecordValue.Field(FIELDS, plain(fieldMap(fields))));
        if (!groups.isEmpty()) {
            binding.add(new RecordValue.Field(GROUPS, plain(new ArrayValue(groups))));
        }
        // The base's own statement about which fields select a member (§5.2), written where a record states one.
        if (!discriminators.isEmpty()) {
            binding.add(new RecordValue.Field(DISCRIMINATORS, plain(new ArrayValue(
                    discriminators.stream()
                            .map(name -> plain(declaredName(name))).toList()))));
        }
        if (extension != RecordExtensionType.OPEN) {
            binding.add(nameField(EXTENSION, extension.name()));
        }
        return new DataValue(List.of(), Optional.of(RECORD), new RecordValue(binding));
    }

    /** A resolved annotation carrier back in wire form, its bound value unbound by the caller's writer. */
    private static List<Annotation> annotations(Annotations resolved, Function<Object, DataValue> annotationValue) {
        if (resolved.values().isEmpty()) {
            return List.of();
        }
        List<Annotation> written = new ArrayList<>();
        for (io.ltr8.annotation.Annotation annotation : resolved.values()) {
            written.add(new Annotation(annotation.name(), annotation.value().map(annotationValue)));
        }
        return written;
    }

    /**
     * A reference in the wire spelling: a bare name an unquoted token, and an application a {@link ReferenceValue},
     * written unquoted as data grammar ([TSON-DATA] §7.4).
     *
     * <p><b>One spelling is the requirement, not the tidiness.</b> An entry name derives from what is written, so a
     * second spelling of one reference would give one type two entry names.
     */
    static CoreValue refValue(TypeRef ref) {
        return ref.arguments().isEmpty() ? new TokenValue(ref.name(), TokenForm.UNQUOTED) : new ReferenceValue(ref);
    }

    /** A record field's type in the wire spelling: a type reference as {@link #refValue(TypeRef)}, a sibling as itself. */
    static CoreValue refValue(Reference ref) {
        return switch (ref) {
            case TypeRef reference -> refValue(reference);
            case SiblingRef sibling -> new ReferenceValue(sibling);
        };
    }

    // ── Reading: the wire form back as the reference it spells ─────────────────────────────────

    /**
     * The application {@code value} is, or empty where it is none: a {@link ReferenceValue}, the data grammar's
     * reference with arguments. A bare name is an unquoted token, and a quoted token is never a reference, so a
     * text slot holding {@code "box<T>"} is text. Package-visible so {@code ParameterTypes} recognises an application
     * by the same test that closes one.
     */
    static Optional<TypeRef> applicationIn(CoreValue value) {
        return value instanceof ReferenceValue(TypeRef reference) ? Optional.of(reference) : Optional.empty();
    }

    /** A field annotation's bound value back in wire form, for {@link #recordWire}. */
    static DataValue annotationValue(Object value) {
        return new TsonDataParser(ANNOTATION_WRITER.toTson(value)).parseDocument().root();
    }

    static Optional<CoreValue> field(RecordValue record, String name) {
        return record.fields().stream().filter(f -> f.name().equals(name))
                .map(f -> f.value().coreValue()).findFirst();
    }

    /** {@link #rewritten}'s counterpart for a map key, which is a bare {@code data-value}. */
    static DataValue retyped(DataValue original, CoreValue rewritten) {
        return new DataValue(original.annotations(), original.typeRef(), rewritten);
    }

    /** The same data value carrying a rewritten core value -- annotations and type-ref kept as written. */
    static DataValue rewritten(DataValue original, CoreValue rewritten) {
        return new DataValue(original.annotations(), original.typeRef(), rewritten);
    }
}
