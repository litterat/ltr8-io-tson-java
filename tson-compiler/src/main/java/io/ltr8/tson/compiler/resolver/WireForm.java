package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.tson.base.SchemaValidationException;
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
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * How schema vocabulary is spelled as data, in both directions -- the one place that knows what a reference and a
 * held application look like on the wire, an open record's tree included, and the one that rewrites a parameter
 * standing inside one.
 *
 * <p><b>Why one class rather than a producer over here and a consumer over there.</b> A held body is written
 * by two phases and read by four: {@code SchemaDesugarer} lifts a sugar form and {@link HeldBody} writes an open
 * record's tree ({@link #heldRecord}); {@code TemplateMaterialiser} closes one, {@link HeldBody}
 * answers §5.10's declaration-time questions about one, {@code SyntheticMerge} asks whether one holds an
 * application, and {@code ParameterTypes} walks one for §5.10's parameter types. A second opinion about what
 * an application looks like is what makes one of those wrong.
 *
 * <p><b>Nothing here is canonical output, and {@code DataClassObjectWriter} cannot serve any of it.</b> That
 * writer's output is canonical-explicit and fully quoted, which is a different language from the one a held
 * body is written in: {@code TemplateBody.names()} and {@link #substitute} both key on a token being
 * unquoted, so a quoted body references no parameters at all. What is written here is what an author would
 * have written -- an unquoted token where the writer would quote, an application as the data grammar's reference,
 * and nothing at all where the constructor's own default says it.
 *
 * <p><b>One spelling per shape is the requirement, not the tidiness.</b> An entry name derives from what is
 * written ({@code SchemaDesugarer.internalName}), so a second spelling of one reference splits one type
 * across two entries. That is why {@link #refValue} is what every producer goes through, and why
 * {@link #applicationIn} -- its inverse -- lives beside it rather than in the phase that happens to read.
 */
final class WireForm {

    private WireForm() {
    }

    // ── The vocabulary's own member names ────────────────────────────────────────────────────────

    /** {@code record_field.value}: §8.1's literal channel. */
    static final String VALUE = "value";

    /** The constructor an open record's held tree applies. */
    static final String RECORD = "record";

    static final String ALIAS = "alias";

    static final String TARGET = "target";

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
     * <p>An open record's held tree states it ({@link #heldRecord}), so each application closes with the base's
     * selectors in hand.
     */
    static final String DISCRIMINATORS = "discriminators";

    /**
     * {@code record.extension} -- how the record may be realised (§5.2). Written only where it is not
     * {@code OPEN}, the constructor's own default, so an open record's tree states a mark exactly when one was
     * made.
     */
    static final String EXTENSION = "extension";

    /**
     * {@code record.name_type} -- the type a record's field names are values of ([TSON-SCHEMA] §5.2). Written
     * exactly where the record states one, so a held body keeps the difference between stating and not.
     */
    static final String NAME_TYPE = "name_type";

    // ── Building blocks ──────────────────────────────────────────────────────────────────────────

    /**
     * A {@code field_group} as a held body writes it: {@code optional_members} only where it names a member, and
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
     * {@code record.fields} as a held body writes it: a map from each field's declared name ({@link
     * #declaredName}) to its {@code record_field}, in declaration order. An empty one is the empty brace, which
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
     * annotations on the {@code record_field} in resolver output, and a held body reaches that through the
     * wire value, so they travel here rather than being re-attached after the fact.
     */
    static DataValue plain(CoreValue value, List<Annotation> annotations) {
        return new DataValue(annotations, Optional.empty(), value);
    }

    /**
     * A {@code record_field}'s presence and role, each written only where it leaves the kernel's own default
     * ({@code optional: false}, {@code voidable: false}, {@code role: FREE}) -- the one spelling both producers
     * of a held record share, so a template body and the closed record beside it state a field alike.
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
     * it is not one token: a held body would not parse back. Quoting is safe for the parameter channel, which
     * keys on unquoted tokens, because a parameter's name is an identifier and so is never quoted here.
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
     * The held body of an <b>open alias</b> -- {@code <B> !alias { target: pair<uuid B> }}, the form
     * [TSON-SCHEMA] §8.1 says a partial application denotes: {@code alias.target} is a reference, and a reference
     * may carry the arguments the alias already binds.
     *
     * <p>The target goes through {@link #refValue} like every other reference this package writes, so a
     * no-argument target is a bare token and an application a {@link ReferenceValue} -- the one spelling §5.10
     * requires, however many phases produce it.
     */
    static DataValue heldReference(TypeRef target) {
        return new DataValue(List.of(), Optional.of(ALIAS), new RecordValue(List.of(
                new RecordValue.Field(TARGET, plain(refValue(target))))));
    }

    /**
     * An open record's held tree ({@code HeldBody.of}): the {@code !record { … }} application closing substitutes
     * into and reads back through {@code record}'s own reader, written from the record value -- its parents as
     * written, its fields, groups, selectors, a non-default {@code name_type}, and its own {@code extension} where
     * it is not OPEN, the constructor's default. Through {@link #refValue} and {@link #nameField}, the spelling
     * every other wire form here shares.
     *
     * <p><b>{@code annotationValue} is the one thing this cannot do itself.</b> A resolved annotation carries
     * its value as a <em>bound object</em> ({@code Annotation.value} is {@code Optional<Object>}), and
     * unbinding one is exactly what an object writer is for -- so the caller passes that single leaf in and
     * everything structural stays here.
     */
    static DataValue heldRecord(RecordBody body, Function<Object, DataValue> annotationValue) {
        List<MapValue.MapEntry> fields = new ArrayList<>();
        for (Map.Entry<String, RecordField> declared : body.fields().entrySet()) {
            RecordField field = declared.getValue();
            List<RecordValue.Field> members = new ArrayList<>();
            members.add(new RecordValue.Field(TYPE, plain(refValue(field.type()))));
            addFacts(members, field.optional(), field.voidable(), field.role());
            // The two channels collapse into one: a literal keeps its own token form, and a routed parameter
            // is a bare name standing where the literal would.
            field.value().ifPresent(token -> members.add(new RecordValue.Field(VALUE,
                    plain(new TokenValue(token.text(), tokenForm(token.form()))))));
            fields.add(fieldEntry(declared.getKey(),
                    plain(new RecordValue(members), annotations(field.annotations(), annotationValue))));
        }
        List<DataValue> groups = new ArrayList<>();
        for (FieldGroup group : body.groups()) {
            groups.add(group(group, List.of()));
        }
        List<RecordValue.Field> binding = new ArrayList<>();
        if (!body.parents().isEmpty()) {
            // Held text's one chain slot, the parents as written (TypeChain.parents). Through refValue like every
            // other reference: a closed parent is a bare token and only a parent still applied to a parameter
            // carries arguments, which is what lets substitution close it.
            binding.add(new RecordValue.Field(CHAIN, plain(new RecordValue(List.of(new RecordValue.Field(PARENTS,
                    plain(new ArrayValue(body.parents().stream().map(parent -> plain(refValue(parent)))
                            .toList()))))))));
        }
        body.nameType().ifPresent(stated -> binding.add(nameField(NAME_TYPE, stated)));
        binding.add(new RecordValue.Field(FIELDS, plain(fieldMap(fields))));
        if (!groups.isEmpty()) {
            binding.add(new RecordValue.Field(GROUPS, plain(new ArrayValue(groups))));
        }
        // The base's own statement about which fields select a member (§5.2). Without it a held body keeps
        // each pin and loses what gives the pins meaning, so a template family could never be sealed.
        if (!body.discriminators().isEmpty()) {
            binding.add(new RecordValue.Field(DISCRIMINATORS, plain(new ArrayValue(
                    body.discriminators().stream()
                            .map(name -> plain(declaredName(name))).toList()))));
        }
        if (body.extension() != RecordExtensionType.OPEN) {
            binding.add(nameField(EXTENSION, body.extension().name()));
        }
        return new DataValue(List.of(), Optional.of(RECORD), new RecordValue(binding));
    }

    /** {@link #memberToken} for a caller in this package that reads a held field's own members. */
    static String memberTokenOf(RecordValue record, String member) {
        return memberToken(record, member);
    }

    /** One member's token text, or {@code null} where it is absent or is not a bare token. */
    private static String memberToken(RecordValue record, String member) {
        for (RecordValue.Field field : record.fields()) {
            if (field.name().equals(member) && field.value().coreValue() instanceof TokenValue token) {
                return token.text();
            }
        }
        return null;
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
     * A reference in the held spelling: a bare name an unquoted token, and an application a {@link ReferenceValue},
     * written unquoted as data grammar ([TSON-DATA] §7.4, SPEC-FEEDBACK.md #12).
     *
     * <p><b>One spelling is the requirement, not the tidiness.</b> An entry name derives from what is written, so a
     * second spelling of one reference would give one type two entry names. That is why {@link TemplateMaterialiser}'s
     * substitution writes a bound reference through this rather than spelling one of its own.
     */
    static CoreValue refValue(TypeRef ref) {
        return ref.arguments().isEmpty() ? new TokenValue(ref.name(), TokenForm.UNQUOTED) : new ReferenceValue(ref);
    }

    // ── Reading: the wire form back as the reference it spells ─────────────────────────────────

    /**
     * The application {@code value} is, or empty where it is none: a {@link ReferenceValue}, the data grammar's
     * reference with arguments. A bare name is an unquoted token, and a quoted token is never a reference, so a held
     * body's text slot holding {@code "box<T>"} is text. Package-visible so {@link HeldBody} and {@code
     * ParameterTypes} recognise an application by the same test that closes one.
     */
    static Optional<TypeRef> applicationIn(CoreValue value) {
        return value instanceof ReferenceValue reference ? Optional.of(reference.reference()) : Optional.empty();
    }

    /** The names an application writes: its head and every reference among its arguments, at any depth. */
    static List<String> namesIn(TypeRef application) {
        List<String> names = new ArrayList<>();
        names.add(application.name());
        for (TypeArgument argument : application.arguments()) {
            if (argument instanceof TypeArgument.Ref ref) {
                names.addAll(namesIn(ref.ref()));
            }
        }
        return names;
    }

    /** {@code application} with each argument that is a bare name in {@code renames} renamed, at any depth. */
    static TypeRef renamedArguments(TypeRef application, Map<String, String> renames) {
        List<TypeArgument> arguments = new ArrayList<>(application.arguments().size());
        for (TypeArgument argument : application.arguments()) {
            arguments.add(switch (argument) {
                case TypeArgument.Ref ref when ref.ref().arguments().isEmpty()
                        && renames.containsKey(ref.ref().name()) ->
                        new TypeArgument.Ref(TypeRef.of(renames.get(ref.ref().name())));
                case TypeArgument.Ref ref -> new TypeArgument.Ref(renamedArguments(ref.ref(), renames));
                case TypeArgument.Value value -> value;
            });
        }
        return new TypeRef(application.name(), arguments, application.annotations());
    }

    /**
     * The parents a held {@code !record { chain: { parents: [...] } ... }} tree writes, as references -- empty for a
     * held body that is no record or writes none. They are the parents as written ([TSON-SCHEMA] §5.10), a bare
     * name or an application, which is what closing a record template completes into a chain.
     */
    static List<TypeRef> writtenParents(DataValue held) {
        if (!(held.coreValue() instanceof RecordValue record)
                || !(field(record, CHAIN).orElse(null) instanceof RecordValue chain)
                || !(field(chain, PARENTS).orElse(null) instanceof ArrayValue parents)) {
            return List.of();
        }
        List<TypeRef> written = new ArrayList<>();
        for (DataValue parent : parents.elements()) {
            Optional<TypeRef> application = applicationIn(parent.coreValue());
            if (application.isPresent()) {
                written.add(application.get());
            } else if (parent.coreValue() instanceof TokenValue token && token.form() == TokenForm.UNQUOTED) {
                written.add(TypeRef.of(token.text()));
            }
        }
        return List.copyOf(written);
    }

    static Optional<CoreValue> field(RecordValue record, String name) {
        return record.fields().stream().filter(f -> f.name().equals(name))
                .map(f -> f.value().coreValue()).findFirst();
    }

    // ── Substituting a parameter standing in a held body (§5.10) ───────────────────────────────

    /**
     * The held body with every token naming one of the template's parameters replaced by the argument applied
     * for it -- at any depth, in a value slot, a type slot, an argument list, or inside a collection alike.
     *
     * <p><b>A held token needs no channel label, and that is the whole economy of holding.</b> A typed open
     * vocabulary has to record which kind of thing each slot was bound to, because a bare token in a value
     * slot is a literal and nothing else; here the body is uninterpreted until this substitution finishes, so
     * §8.1's shadowing rule decides it -- a token that resolves into {@code parameters} is a parameter, and
     * anything else is what it looks like. Inside an application ({@link #applicationIn}) an argument naming a
     * parameter is rewritten the same way, and the application written again through {@link #refValue}.
     *
     * <p><b>An argument may itself be open, and that is a supported use rather than an accident.</b>
     * {@code DefinitionResolver} substitutes an operand's held body with the arguments an enclosing
     * declaration wrote, which may be that declaration's own parameters -- the result is a held body still
     * carrying them, absorbed as fields and closed when the enclosing declaration is. Nothing here has to
     * know: a binding is a {@link TypeArgument} either way, and this walk never asks whether the token it
     * writes is concrete.
     */
    static CoreValue substitute(CoreValue value, String head, List<String> parameters,
            Map<String, TypeArgument> bindings) {
        return switch (value) {
            case TokenValue token when token.form() == TokenForm.UNQUOTED
                    && parameters.contains(token.text()) ->
                    argumentValue(argumentFor(token.text(), head, bindings));
            case ReferenceValue reference ->
                    refValue(substituted(reference.reference(), head, parameters, bindings));
            case ArrayValue array -> new ArrayValue(array.elements().stream()
                    .map(element -> rewritten(element, substitute(element.coreValue(), head, parameters,
                            bindings))).toList());
            case RecordValue record -> new RecordValue(record.fields().stream()
                    .map(field -> substituteField(field, head, parameters, bindings)).toList());
            // Both halves of an entry, because a parameter reaches either: one may stand in a key, and another
            // inside the array its value names. Each half is a data-value ([TSON-DATA] §2.6), rebuilt with its
            // own annotations and type-ref kept.
            case MapValue map -> new MapValue(map.entries().stream()
                    .map(entry -> new MapValue.MapEntry(
                            retyped(entry.key(), substitute(entry.key().coreValue(), head, parameters, bindings)),
                            rewritten(entry.value(), substitute(entry.value().coreValue(), head, parameters,
                                    bindings))))
                    .toList());
            default -> value;
        };
    }

    /** {@link #rescope}'s counterpart for a map key, which is a bare {@code data-value} and carries no scope. */
    static DataValue retyped(DataValue original, CoreValue rewritten) {
        return new DataValue(original.annotations(), original.typeRef(), rewritten);
    }

    /** One field of a held record, its value substituted. */
    private static RecordValue.Field substituteField(RecordValue.Field field, String head,
            List<String> parameters, Map<String, TypeArgument> bindings) {
        return new RecordValue.Field(field.name(),
                rewritten(field.value(), substitute(field.value().coreValue(), head, parameters, bindings)));
    }

    /**
     * An application with each argument naming a parameter replaced by the argument bound for it, at any depth. The
     * head is never a parameter: {@code T<text>} applies one, which is no form §12.1 has.
     */
    static TypeRef substituted(TypeRef application, String head, List<String> parameters,
                               Map<String, TypeArgument> bindings) {
        List<TypeArgument> arguments = new ArrayList<>(application.arguments().size());
        for (TypeArgument argument : application.arguments()) {
            arguments.add(switch (argument) {
                case TypeArgument.Ref ref when ref.ref().arguments().isEmpty()
                        && parameters.contains(ref.ref().name()) -> argumentFor(ref.ref().name(), head, bindings);
                case TypeArgument.Ref ref when !ref.ref().arguments().isEmpty() ->
                        new TypeArgument.Ref(substituted(ref.ref(), head, parameters, bindings));
                default -> argument;
            });
        }
        return new TypeRef(application.name(), arguments, application.annotations());
    }

    private static TypeArgument argumentFor(String parameter, String head, Map<String, TypeArgument> bindings) {
        TypeArgument argument = bindings.get(parameter);
        if (argument == null) {
            // A parameter of an enclosing template, still open: this application is not the one that closes
            // it. Nothing today reaches here -- an application is closed only once every argument is concrete
            // -- and saying so is what keeps that true rather than assuming it.
            throw new UnsupportedOperationException("'" + head + "<...>' holds the parameter '" + parameter
                    + "', which this application does not supply, and closing an open form onto another open "
                    + "form is not implemented (§5.10)");
        }
        return argument;
    }

    /**
     * One argument in the held spelling, standing where the parameter it binds stood.
     *
     * <p>A <b>literal</b> is its own token. A <b>reference</b> goes through {@link #refValue}, which spells a
     * no-argument one as a bare token and one carrying arguments as a {@link ReferenceValue}. Writing the head alone
     * would be the shorter code and is wrong: {@code box<inner<T>>} would put {@code inner} where {@code inner<T>}
     * belongs and drop the argument list with no diagnostic.
     *
     * <p><b>Reusing the desugarer's producer is the requirement, not the convenience.</b> Two spellings of
     * one form are two entries for one type, an entry name deriving from what is written -- so the phase that
     * substitutes and the phase that lifts have to agree down to how a reference is spelled. Sharing the
     * function is what makes that true by construction.
     *
     * <p>Every slot a parameter can occupy as a type takes either spelling: a field's {@code type}, an {@code
     * element_type}, a {@code variants}/{@code elements} entry, an application's argument. The one position that
     * would not is an application's <em>head</em>, and a parameter cannot stand there: {@code T<text>} applies a
     * parameter, which is no form §12.1 has.
     */
    private static CoreValue argumentValue(TypeArgument argument) {
        return switch (argument) {
            case TypeArgument.Ref reference -> refValue(reference.ref());
            case TypeArgument.Value value -> new TokenValue(value.value().text(),
                    switch (value.value().form()) {
                        case UNQUOTED -> TokenForm.UNQUOTED;
                        case SINGLE_LINE_QUOTED -> TokenForm.SINGLE_LINE_QUOTED;
                        case MULTI_LINE_QUOTED -> TokenForm.MULTI_LINE_QUOTED;
                    });
        };
    }

    /** The same data value carrying a rewritten core value -- annotations and type-ref kept as written. */
    static DataValue rewritten(DataValue original, CoreValue rewritten) {
        return new DataValue(original.annotations(), original.typeRef(), rewritten);
    }
}
