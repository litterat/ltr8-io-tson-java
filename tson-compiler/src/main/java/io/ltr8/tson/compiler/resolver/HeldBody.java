package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.Annotations;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.writer.DataClassObjectWriter;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link TemplateBody}'s text, parsed -- the constructor application an open entry holds, as a working
 * value of the phases that need one.
 *
 * <p><b>The parsed form is a working value, not part of the entry.</b> {@link TemplateBody} carries the
 * application as text because that is what §5.10 means by held; this parses it once, on demand, for the four
 * questions a held body is asked before it closes: which names it mentions (§5.10's unreferenced-parameter
 * rule), which applications it writes (§5.10.1's regularity rule and §5.10's arity rule), what substitution
 * rewrites at materialisation, and which constructor heads it applies. Nothing outside this package sees a
 * {@link DataValue}.
 *
 * <p><b>Parsing rather than holding is what keeps the value model a value model.</b> The AST is
 * {@code tson-compiler}'s own grammar type and {@code tson-schema} depends on nothing here, so an entry
 * could not carry one without inverting that direction. It also makes the text authoritative, which is what
 * §8.1's ingest rule already assumes: an open entry's held body is re-resolved as source.
 *
 * <p><b>Memoised, because the questions are asked repeatedly and the answer cannot change.</b> A
 * {@link TemplateBody} is an immutable value and parsing is a pure function of its text, so one entry is
 * parsed once however many phases ask. The cache is keyed on the record itself and bounded by the number of
 * open entries a process loads -- schema-load work, never read-path work.
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

    private final TemplateBody body;
    private final DataValue application;

    private HeldBody(TemplateBody body, DataValue application) {
        this.body = body;
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
        // The parent's extension is derived here because this is the one door every open entry passes
        // through, whatever spelling produced it -- and from the payload's own structure rather than from
        // the text below it, which is not parsed back on this path at all.
        return new TemplateBody(parameters, WRITER.toTson(application),
                WireForm.parentExtension(application, parameters));
    }

    /**
     * {@code body}'s text parsed, from the cache when it has been asked before.
     *
     * <p>The text is a {@code core-value} rather than a whole document, so it parses through the data
     * grammar and is complete when the value ends. A failure here is an internal fault and not an author
     * error: every held body in circulation was written by {@link WireForm} from a tree this resolver built,
     * so text that will not parse means the writer and the parser disagree.
     */
    public static HeldBody of(TemplateBody body) {
        Objects.requireNonNull(body, "body");
        try {
            return new HeldBody(body, new TsonDataParser(body.template()).parseDocument().root());
        } catch (RuntimeException e) {
            throw new IllegalStateException("an open entry's held body did not parse back: "
                    + body.template() + " -- a held body is written by WireForm and read here, so the "
                    + "two have disagreed about the one spelling §5.10 requires", e);
        }
    }

    /**
     * The <b>discriminator fields</b> a marked template's family dispatches on ({@code SPEC-FEEDBACK.md}
     * #13): each as the {@link RecordField} a closed base would declare -- its own name, its declared type,
     * {@code REQUIRED}, and no pin, the pin being what an argument supplies per member.
     *
     * <p><b>One derivation, two callers, and that is the point.</b> {@code RecordExtension} checks the family
     * (every member pins every selector, pins pairwise distinct) and the reader dispatches on it, and a second
     * opinion about which fields those are would let the check pass a family the read cannot place. Both go
     * through here.
     *
     * <p><b>Read off the held payload, which is where the mark survives.</b> §5.7 fixation clears the mark on
     * each member ({@code TemplateMaterialiser.fixRoutedValues}) -- the mark belongs to the field that is
     * still unpinned -- so the members cannot answer this and the template's own body is the only carrier.
     * A declared type that is a parameter is skipped: a position typed by the template reads the selector
     * before it knows the member, so a type varying per application is one it cannot read (the <em>pin</em>
     * varying is the whole design, and does).
     *
     * <p>Empty for every body but a {@code record} application, which is the only shape with fields at all.
     */
    public List<RecordField> selectors() {
        if (!WireForm.RECORD.equals(application.typeRef().orElse(null))
                || !(application.coreValue() instanceof RecordValue binding)) {
            return List.of();
        }
        List<RecordField> selectors = new ArrayList<>();
        for (RecordValue.Field member : binding.fields()) {
            if (!WireForm.FIELDS.equals(member.name())
                    || !(member.value().value().coreValue() instanceof ArrayValue fields)) {
                continue;
            }
            for (ScopedValue element : fields.elements()) {
                if (element.value().coreValue() instanceof RecordValue field) {
                    selectorOf(field).ifPresent(selectors::add);
                }
            }
        }
        return List.copyOf(selectors);
    }

    /** One held field as a selector, or empty where it carries no mark or its type is a parameter. */
    private Optional<RecordField> selectorOf(RecordValue field) {
        if (!"true".equals(WireForm.memberTokenOf(field, WireForm.DISCRIMINATOR))) {
            return Optional.empty();
        }
        CoreValue declared = WireForm.field(field, WireForm.TYPE).orElse(null);
        if (!(declared instanceof TokenValue type) || parameters().contains(type.text())) {
            return Optional.empty();
        }
        String name = WireForm.memberTokenOf(field, WireForm.NAME);
        return name == null ? Optional.empty()
                : Optional.of(new RecordField(name, TypeRef.of(type.text()), FieldState.REQUIRED,
                        true, Optional.empty(), Annotations.empty(), Optional.empty()));
    }

    /** The entry's own parameter names, in declaration order. */
    public List<String> parameters() {
        return body.parameters();
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
                    .forEach(field -> collectApplications(field.value().value().coreValue(), into));
            case ArrayValue array -> array.elements()
                    .forEach(element -> collectApplications(element.value().coreValue(), into));
            default -> { } // a token names a type but applies nothing
        }
    }

    private static void collect(CoreValue value, Set<String> into) {
        switch (value) {
            case TokenValue token when token.form() == TokenForm.UNQUOTED -> into.add(token.text());
            case ArrayValue array -> array.elements()
                    .forEach(element -> collect(element.value().coreValue(), into));
            case RecordValue record -> record.fields()
                    .forEach(field -> collect(field.value().value().coreValue(), into));
            // A map's keys carry names as readily as its values do -- `{ S => [T] }` puts one parameter in
            // each -- and a key is a full data-value (§2.6), so it is walked rather than read as a token.
            case MapValue map -> map.entries().forEach(entry -> {
                collect(entry.key().coreValue(), into);
                collect(entry.value().value().coreValue(), into);
            });
            default -> { } // a quoted token is a literal, and nothing else carries a name
        }
    }
}
