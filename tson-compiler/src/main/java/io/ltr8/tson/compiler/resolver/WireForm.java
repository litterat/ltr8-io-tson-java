package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
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
 * How schema vocabulary is spelled as data, in both directions -- the one place that knows what a {@code
 * type_ref} and a held {@code !record { ... }} look like on the wire, and the one that rewrites a parameter
 * standing inside one.
 *
 * <p><b>Why one class rather than a producer over here and a consumer over there.</b> A held body is written
 * by two phases and read by four: {@code SchemaDesugarer} lifts a sugar form and {@code DefinitionResolver}
 * holds a composition or refinement template; {@code TemplateMaterialiser} closes one, {@link HeldBody}
 * answers §5.10's declaration-time questions about one, {@code SyntheticMerge} asks whether one holds an
 * application, and {@code ParameterKinds} walks one for §5.10's parameter kinds. A second opinion about what
 * an application looks like is what makes one of those wrong.
 *
 * <p><b>Nothing here is canonical output, and {@code TsonObjectWriter} cannot serve any of it.</b> That
 * writer's output is canonical-explicit and fully quoted, which is a different language from the one a held
 * body is written in: {@code TemplateBody.names()} and {@link #substitute} both key on a token being
 * unquoted, so a quoted body references no parameters at all. What is written here is what an author would
 * have written -- an unquoted token where the writer would quote, a bare name where it would state
 * {@code { name: X  arguments: [] }}, and nothing at all where the constructor's own default says it.
 *
 * <p><b>One spelling per shape is the requirement, not the tidiness.</b> An entry name derives from what is
 * written ({@code SchemaDesugarer.internalName}), so a second spelling of one reference splits one type
 * across two entries. That is why {@link #refValue} is what every producer goes through, and why
 * {@link #typeRefOf} -- its inverse -- lives beside it rather than in the phase that happens to read.
 */
final class WireForm {

    private WireForm() {
    }

    // ── The vocabulary's own member names ────────────────────────────────────────────────────────

    /**
     * The {@code name} member. One constant for the two records that spell it alike -- {@code type_ref.name},
     * carrying a reference, and {@code record_field.name}, carrying a field name. What tells an application
     * from anything else is never this member alone but its pairing with {@link #ARGUMENTS}; see
     * {@link #isApplication}.
     */
    static final String NAME = "name";

    /** {@code type_ref.arguments} -- the second half of what makes a record an application. */
    static final String ARGUMENTS = "arguments";

    /** {@code record_field.value} and {@code type_argument.value}: §8.1's literal channel. */
    static final String VALUE = "value";

    /** The constructor a held record body carries. */
    static final String RECORD = "record";

    static final String REFERENCE = "reference";

    static final String TARGET = "target";

    static final String FIELDS = "fields";
    static final String GROUPS = "groups";
    static final String MEMBERS = "members";
    static final String TYPE = "type";
    static final String STATE = "state";
    static final String SUPERTYPES = "supertypes";

    // ── Building blocks ──────────────────────────────────────────────────────────────────────────

    /** A bare value in a field or element position -- no schema directive, no annotations, no type-ref of its own. */
    static ScopedValue scoped(CoreValue value) {
        return scoped(value, List.of());
    }

    /**
     * The same, carrying the annotations written on the construct it stands for. §6 puts a field's own
     * annotations on the {@code record_field} in resolver output, and a held body reaches that through the
     * wire value, so they travel here rather than being re-attached after the fact.
     */
    static ScopedValue scoped(CoreValue value, List<Annotation> annotations) {
        return new ScopedValue(Optional.empty(), new DataValue(annotations, Optional.empty(), value));
    }

    static RecordValue.Field nameField(String name, String text) {
        return new RecordValue.Field(name, scoped(new TokenValue(text, TokenForm.UNQUOTED)));
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
     * The held body of an <b>open alias</b> -- {@code <B> !reference { target: pair<uuid, B> }}, the form
     * [TSON-SCHEMA] §8.1 says a partial application denotes. Spellable because {@code reference.target} is a
     * {@code type_ref}, so an alias that still binds arguments states them in its own body and {@code source}
     * is never asked to hold them (§8.1).
     *
     * <p>The target goes through {@link #refValue} like every other reference this package writes, so a
     * no-argument target is a bare token and only an application carries {@code arguments} -- the one
     * spelling §5.10 requires, however many phases produce it.
     */
    static DataValue heldReference(TypeRef target) {
        return new DataValue(List.of(), Optional.of(REFERENCE), new RecordValue(List.of(
                new RecordValue.Field(TARGET, scoped(refValue(target))))));
    }

    /**
     * The held body an <b>error placeholder</b> carries -- {@code !record { fields: [] }}, the zero-field
     * record both absorbing stand-ins already stood for, now held like every other open body.
     *
     * <p><b>It exists so that "an open entry's body is held or a {@code Reference}" has no exceptions.</b>
     * A placeholder keeps its declaration's type parameters on purpose (answering "how many?" with zero
     * sends a downstream {@code bl<text>} to fix the wrong declaration), which used to make it the last
     * producer of a parameterised {@code RecordBody} -- and so kept a whole second substitution path alive
     * to serve a body that has no fields to substitute into.
     *
     * <p>Built structurally rather than through {@link #heldRecord}: a placeholder is what a <em>reported</em>
     * declaration leaves behind, so the one thing it must not do is fail again, and an empty record needs
     * neither a namespace nor a writer to state.
     */
    static DataValue heldEmptyRecord() {
        return new DataValue(List.of(), Optional.of(RECORD), new RecordValue(List.of(
                new RecordValue.Field(FIELDS, scoped(new ArrayValue(List.of()))))));
    }

    /**
     * The same {@code !record { … }} held body, built from a body that is <b>already resolved</b> -- the form
     * a composition or refinement template arrives in, since both absorb fields from a source and so cannot
     * be rewritten before there is a namespace to absorb from.
     *
     * <p><b>Two producers of the held wire form are fine; two spellings of it are not.</b> This one and
     * {@code SchemaDesugarer}'s record binding both go through {@link #refValue} and {@link #nameField},
     * which is what makes them one spelling by construction rather than by two authors agreeing -- the class
     * Javadoc has why that matters.
     *
     * <p><b>{@code annotationValue} is the one thing this cannot do itself.</b> A resolved annotation carries
     * its value as a <em>bound object</em> ({@code Annotation.value} is {@code Optional<Object>}), and
     * unbinding one is exactly what an object writer is for -- so the caller passes that single leaf in and
     * everything structural stays here.
     */
    static DataValue heldRecord(RecordBody body, Function<Object, DataValue> annotationValue) {
        List<ScopedValue> fields = new ArrayList<>();
        for (RecordField field : body.fields()) {
            List<RecordValue.Field> members = new ArrayList<>();
            members.add(nameField(NAME, field.name()));
            members.add(new RecordValue.Field(TYPE, scoped(refValue(field.type()))));
            if (field.state() != FieldState.REQUIRED) {
                members.add(nameField(STATE, field.state().name()));
            }
            // The two channels collapse into one: a literal keeps its own token form, and a routed parameter
            // is a bare name standing where the literal would.
            field.value().ifPresent(token -> members.add(new RecordValue.Field(VALUE,
                    scoped(new TokenValue(token.text(), tokenForm(token.form()))))));
            fields.add(scoped(new RecordValue(members), annotations(field.annotations(), annotationValue)));
        }
        List<ScopedValue> groups = new ArrayList<>();
        for (FieldGroup group : body.groups()) {
            List<RecordValue.Field> members = new ArrayList<>();
            members.add(new RecordValue.Field(MEMBERS, scoped(new ArrayValue(group.members().stream()
                    .map(member -> scoped(new TokenValue(member, TokenForm.UNQUOTED))).toList()))));
            if (group.state() != ElementState.REQUIRED) {
                members.add(nameField(STATE, group.state().name()));
            }
            groups.add(scoped(new RecordValue(members)));
        }
        List<RecordValue.Field> binding = new ArrayList<>();
        if (!body.supertypes().isEmpty()) {
            binding.add(new RecordValue.Field(SUPERTYPES, scoped(new ArrayValue(body.supertypes().stream()
                    .map(supertype -> scoped(new TokenValue(supertype, TokenForm.UNQUOTED))).toList()))));
        }
        binding.add(new RecordValue.Field(FIELDS, scoped(new ArrayValue(fields))));
        if (!groups.isEmpty()) {
            binding.add(new RecordValue.Field(GROUPS, scoped(new ArrayValue(groups))));
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
     * A resolved type reference in the held spelling: a bare name, or {@code type_ref}'s record form.
     *
     * <p><b>The {@code arguments().isEmpty()} branch is load-bearing, not an optimisation.</b> A held body is
     * read by later phases as wire form, and {@code type_argument} is told from {@code type_ref} by which
     * shape a slot carries -- so stating a no-argument reference in the record form would make the two
     * indistinguishable to a walk that reads neither against a vocabulary, and would give one type two entry
     * names, since a name derives from what is written. That is why {@link TemplateMaterialiser}'s
     * substitution writes a bound reference through this rather than spelling one of its own: the open form
     * needs one spelling however many phases produce it.
     */
    static CoreValue refValue(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return new TokenValue(ref.name(), TokenForm.UNQUOTED);
        }
        List<ScopedValue> arguments = new ArrayList<>();
        for (TypeArgument argument : ref.arguments()) {
            arguments.add(scoped(new RecordValue(List.of(switch (argument) {
                case TypeArgument.Ref reference ->
                        new RecordValue.Field(NAME, scoped(refValue(reference.ref())));
                case TypeArgument.Value literal ->
                        new RecordValue.Field(VALUE, scoped(new TokenValue(literal.value().text(),
                                tokenForm(literal.value().form()))));
            }))));
        }
        return new RecordValue(List.of(nameField(NAME, ref.name()),
                new RecordValue.Field(ARGUMENTS, scoped(new ArrayValue(arguments)))));
    }


    // ── Reading: the wire form back as the reference it spells ─────────────────────────────────

    /**
     * {@code type_ref}'s record form is the one shape carrying both members; a bare name is a token, and a
     * {@code type_argument} carries {@code name} or {@code value} but never {@code arguments}. Package-visible
     * so {@link HeldBody} recognises an application by the same test that closes one -- a held body is written
     * by one phase and read by two, and a second opinion about what an application looks like is what makes
     * one of them wrong.
     */
    static boolean isApplication(RecordValue record) {
        return field(record, NAME).isPresent() && field(record, ARGUMENTS).isPresent();
    }

    /** {@code { name: head  arguments: [ ... ] }} back as the reference it spells. */
    static TypeRef typeRefOf(RecordValue record) {
        CoreValue name = field(record, NAME).orElseThrow();
        String head = name instanceof TokenValue token ? token.text()
                : typeRefOf((RecordValue) name).name();
        List<TypeArgument> arguments = new ArrayList<>();
        if (field(record, ARGUMENTS).orElseThrow() instanceof ArrayValue array) {
            for (ScopedValue element : array.elements()) {
                arguments.add(argumentOf((RecordValue) element.value().coreValue()));
            }
        }
        return new TypeRef(head, arguments);
    }

    /** One argument record: {@code value} carries a literal, {@code name} a reference, simple or compound. */
    private static TypeArgument argumentOf(RecordValue argument) {
        Optional<CoreValue> literal = field(argument, VALUE);
        if (literal.isPresent()) {
            TokenValue token = (TokenValue) literal.get();
            return new TypeArgument.Value(new Token(token.text(), switch (token.form()) {
                case UNQUOTED -> Token.Form.UNQUOTED;
                case SINGLE_LINE_QUOTED -> Token.Form.SINGLE_LINE_QUOTED;
                case MULTI_LINE_QUOTED -> Token.Form.MULTI_LINE_QUOTED;
            }));
        }
        CoreValue name = field(argument, NAME).orElseThrow();
        return new TypeArgument.Ref(name instanceof TokenValue token ? TypeRef.of(token.text())
                : typeRefOf((RecordValue) name));
    }

    static Optional<CoreValue> field(RecordValue record, String name) {
        return record.fields().stream().filter(f -> f.name().equals(name))
                .map(f -> f.value().value().coreValue()).findFirst();
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
     * anything else is what it looks like. The one place the channel still shows is inside a {@code type_ref}
     * record, whose {@code name} member takes a reference: a parameter there bound to a literal moves to the
     * {@code value} member, since an argument list distinguishes the two by which member holds it.
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
            case ArrayValue array -> new ArrayValue(array.elements().stream()
                    .map(element -> rescope(element, substitute(element.value().coreValue(), head, parameters,
                            bindings))).toList());
            case RecordValue record -> new RecordValue(record.fields().stream()
                    .map(field -> substituteField(field, head, parameters, bindings)).toList());
            // Both halves of an entry, because a parameter reaches either: core's `extern_of => <S> !scoped
            // { scope: [EXTERN]  schemas: { S => _ } }` puts one in a key, and `extern_type`'s `T` inside the
            // array its value names. A key is a data-value and a value a scoped-value ([TSON-DATA] §2.6), so
            // the two are rebuilt through their own carriers rather than one.
            case MapValue map -> new MapValue(map.entries().stream()
                    .map(entry -> new MapValue.MapEntry(
                            retyped(entry.key(), substitute(entry.key().coreValue(), head, parameters, bindings)),
                            rescope(entry.value(), substitute(entry.value().value().coreValue(), head, parameters,
                                    bindings))))
                    .toList());
            default -> value;
        };
    }

    /** {@link #rescope}'s counterpart for a map key, which is a bare {@code data-value} and carries no scope. */
    static DataValue retyped(DataValue original, CoreValue rewritten) {
        return new DataValue(original.annotations(), original.typeRef(), rewritten);
    }

    /**
     * One field of a held record, with {@code type_ref}'s own {@code name}/{@code value} split honoured: a
     * {@code name} member bound to a value argument is that argument's literal on the {@code value} member,
     * because §8.1 tells a reference argument from a literal one by which member carries it.
     */
    private static RecordValue.Field substituteField(RecordValue.Field field, String head,
            List<String> parameters, Map<String, TypeArgument> bindings) {
        CoreValue held = field.value().value().coreValue();
        if (NAME.equals(field.name()) && held instanceof TokenValue token
                && token.form() == TokenForm.UNQUOTED && parameters.contains(token.text())
                && argumentFor(token.text(), head, bindings) instanceof TypeArgument.Value literal) {
            return new RecordValue.Field(VALUE, rescope(field.value(), argumentValue(literal)));
        }
        return new RecordValue.Field(field.name(),
                rescope(field.value(), substitute(held, head, parameters, bindings)));
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
     * <p>A <b>literal</b> is its own token. A <b>reference</b> goes through {@link #refValue},
     * which spells a no-argument one positionally (§5.6, where a reference and a literal look alike) and one
     * carrying arguments in {@code type_ref}'s own record form. Writing the head name alone would be the
     * shorter code and is wrong: {@code box<inner<T>>} would put {@code inner} where {@code inner<T>} belongs
     * and drop the argument list with no diagnostic, because a token has nowhere to keep it.
     *
     * <p><b>Reusing the desugarer's producer is the requirement, not the convenience.</b> Two spellings of
     * one form are two entries for one type, an entry name deriving from what is written -- so the phase that
     * substitutes and the phase that lifts have to agree down to whether a no-argument reference is a token
     * or a record. Sharing the function is what makes that true by construction.
     *
     * <p>Every slot a parameter can occupy takes either spelling: a field's {@code type}, an {@code
     * element_type}, a {@code variants}/{@code elements} entry, and {@code type_argument}'s own {@code name}
     * member are all typed {@code type_ref}. The one position that would not is a {@code type_ref}'s
     * <em>head</em> -- {@code type_ref.name} is a {@code type_name} -- and a parameter cannot stand there:
     * {@code T<text>} applies a parameter, which is no form §12.1 has.
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

    /** The same scoped value carrying a rewritten core value -- annotations and type-ref kept as written. */
    static ScopedValue rescope(ScopedValue original, CoreValue rewritten) {
        DataValue value = original.value();
        return new ScopedValue(original.schemaRef(),
                new DataValue(value.annotations(), value.typeRef(), rewritten));
    }
}
