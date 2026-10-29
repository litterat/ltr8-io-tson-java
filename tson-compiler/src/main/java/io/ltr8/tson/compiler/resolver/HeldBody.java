package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.writer.DataClassObjectWriter;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An open entry's held tree -- the constructor application it holds, as a working value of the phases that need
 * one: a {@link TemplateBody}'s text parsed, or an open {@link RecordBody}'s tree written ({@link
 * WireForm#heldRecord}).
 *
 * <p><b>The tree is a working value, not part of the entry.</b> {@link TemplateBody} carries the application as
 * text because a parameter may stand in any slot of a constructor other than {@code record}; a record template is
 * the record value it is. Either way this builds the tree on demand for the four questions an open body is asked
 * before it closes: which names it mentions (§5.10's unreferenced-parameter
 * rule), which applications it writes (§5.10.1's regularity rule and §5.10's arity rule), what substitution
 * rewrites at materialisation, and which constructor heads it applies. Nothing outside this package sees a
 * {@link DataValue}.
 *
 * <p><b>Building rather than holding is what keeps the value model a value model.</b> The AST is
 * {@code tson-compiler}'s own grammar type and {@code tson-schema} depends on nothing here, so an entry
 * could not carry one without inverting that direction.
 *
 * <p><b>Built per call, never cached.</b> It is a pure function of the entry and schema-load work, never
 * read-path work, and a caller that needs the tree twice builds it once into a local.
 *
 * <p><b>The two questions are not interchangeable, and a check must pick the right one.</b>
 * {@link #applications()} returns a distinguishable shape; {@link #names()} returns every token the body
 * holds, type references and field names and literals alike. So a rule that must not fire on a field name --
 * "this token names an unapplied template" -- has no sound form here and belongs downstream, on the entry
 * materialisation mints.
 */
public final class HeldBody {

    /** Vocabulary-free, because a held application is written as the tree it is and read against nothing. */
    private static final DataClassObjectWriter WRITER = new DataClassObjectWriter();

    private final List<String> parameters;
    private final DataValue application;

    private HeldBody(List<String> parameters, DataValue application) {
        this.parameters = parameters;
        this.application = application;
    }

    /**
     * The held body for an application this resolver has built -- the write direction, and the only way a
     * {@link TemplateBody} is minted.
     *
     * <p><b>The tree in hand is not kept.</b> Handing it back from {@link #of} would be one parse cheaper
     * and would hide the thing most worth knowing: that a body written out and read back is the same body.
     * Parsing every held body through the same door as a read one keeps a round-trip defect a test failure
     * rather than a difference between two entries that should be equal.
     */
    public static TemplateBody held(List<String> parameters, DataValue application) {
        if (WireForm.RECORD.equals(application.typeRef().orElse(null))) {
            throw new IllegalStateException("a record template is a record with parameters, never held text "
                    + "(§5.10): " + parameters);
        }
        // Every parameter starts as a type parameter: its type is derived from the body once the whole schema has
        // resolved (ParameterTypes), which a held body is built well before.
        return new TemplateBody(parameters.stream().map(TemplateParam::typeParameter).toList(),
                WRITER.toTson(application));
    }

    /**
     * The held application of {@code open}, an open entry's value ({@link OpenEntry#isOpen}) -- the one door
     * every phase asking a question of an open body goes through, so which kernel shape carries one is answered
     * here and nowhere else.
     *
     * <p>The text is a {@code core-value} rather than a whole document, so it parses through the data
     * grammar and is complete when the value ends. A failure here is an internal fault and not an author
     * error: every held body in circulation was written by {@link WireForm} from a tree this resolver built,
     * so text that will not parse means the writer and the parser disagree.
     */
    public static HeldBody of(Top open) {
        Objects.requireNonNull(open, "open");
        if (OpenEntry.isFamilyBase(open)) {
            // An open record is a value of the vocabulary as it stands, so its tree is written rather than parsed.
            RecordBody record = (RecordBody) open;
            return new HeldBody(record.parameterNames(), WireForm.heldRecord(record, HeldBody::annotationValue));
        }
        if (!(open instanceof TemplateBody body)) {
            throw new IllegalStateException("only an open entry has a held body, and this one is closed: "
                    + open.getClass().getSimpleName());
        }
        try {
            return new HeldBody(body.parameterNames(),
                    new TsonDataParser(body.template()).parseDocument().root());
        } catch (RuntimeException e) {
            throw new IllegalStateException("an open entry's held body did not parse back: "
                    + body.template() + " -- a held body is written by WireForm and read here, so the "
                    + "two have disagreed about the one spelling §5.10 requires", e);
        }
    }

    /** A held tree in hand, for a check that runs on a payload before it is bound. */
    static HeldBody ofTree(List<String> parameters, DataValue application) {
        return new HeldBody(List.copyOf(parameters), application);
    }

    /** A field annotation's bound value back in wire form, for {@link WireForm#heldRecord}. */
    static DataValue annotationValue(Object value) {
        return new TsonDataParser(WRITER.toTson(value)).parseDocument().root();
    }

    /** The entry's own parameter names, in declaration order. */
    public List<String> parameters() {
        return parameters;
    }

    /** The held application as a value tree -- what substitution rewrites and what identity is derived from. */
    public DataValue application() {
        return application;
    }

    /**
     * Every unquoted name this body mentions, at any depth -- the one question §5.10 asks at link time: a
     * declared parameter the body never references is an author error.
     *
     * <p>What it returns is exactly the set of tokens substitution would rewrite -- unquoted ones, a quoted
     * token in a value slot being a literal and never a name -- or the check and the rewrite disagree about
     * what a parameter reference is.
     */
    public Set<String> names() {
        Set<String> names = new LinkedHashSet<>();
        collect(application.coreValue(), names);
        return names;
    }

    /**
     * Every type application this body writes, at any depth -- the question §5.10.1 asks at the declaration:
     * a recursive application that does not pass its parameters through unchanged grows its argument at
     * every level, so no finite set of types closes it.
     *
     * <p><b>Why the check cannot simply wait for materialisation.</b> A template nobody applies is never
     * materialised, so deferring the rule would let an irregular one ship with no verdict at all; and an
     * application that does reach materialisation hits a depth backstop instead, which reports a chain of
     * derived names against the line that used the template rather than the line that contains the mistake.
     *
     * <p>Nesting is the caller's -- what this returns is every application the tree holds, an application
     * inside another's argument list included, each as the {@link TypeRef} it spells.
     */
    public List<TypeRef> applications() {
        List<TypeRef> applications = new ArrayList<>();
        collectApplications(application.coreValue(), applications);
        return applications;
    }

    /**
     * Every {@code type_ref} record form the held tree holds. It does <b>not</b> descend into one it finds:
     * an application's own arguments come back inside the {@link TypeRef} it yields, and the caller that
     * cares about nesting walks those -- descending here as well would report each nested application twice.
     */
    private static void collectApplications(CoreValue value, List<TypeRef> into) {
        switch (value) {
            case RecordValue record when WireForm.isApplication(record) ->
                    into.add(WireForm.typeRefOf(record));
            case RecordValue record -> record.fields()
                    .forEach(field -> collectApplications(field.value().coreValue(), into));
            case ArrayValue array -> array.elements()
                    .forEach(element -> collectApplications(element.coreValue(), into));
            // `record.fields` is a map, and an application stands in a field's value as readily as in an array.
            case MapValue map -> map.entries().forEach(entry -> {
                collectApplications(entry.key().coreValue(), into);
                collectApplications(entry.value().coreValue(), into);
            });
            default -> { } // a token names a type but applies nothing
        }
    }

    private static void collect(CoreValue value, Set<String> into) {
        switch (value) {
            case TokenValue token when token.form() == TokenForm.UNQUOTED -> into.add(token.text());
            case ArrayValue array -> array.elements()
                    .forEach(element -> collect(element.coreValue(), into));
            case RecordValue record -> record.fields()
                    .forEach(field -> collect(field.value().coreValue(), into));
            // A map's keys carry names as readily as its values do -- `{ S => [T] }` puts one parameter in
            // each -- and a key is a full data-value (§2.6), so it is walked rather than read as a token.
            case MapValue map -> map.entries().forEach(entry -> {
                collect(entry.key().coreValue(), into);
                collect(entry.value().coreValue(), into);
            });
            default -> { } // a quoted token is a literal, and nothing else carries a name
        }
    }
}
