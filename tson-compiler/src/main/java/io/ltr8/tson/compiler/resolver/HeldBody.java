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
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
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
     * The name of the <b>parent entry</b> a record-bodied template gets ({@code SPEC-FEEDBACK.md} #13), or
     * empty where the template has no parent.
     *
     * <p><b>One function, two callers, which is the point.</b> {@code TemplateMaterialiser} mints the entry
     * under this name and {@code TsonSchemaLinker} indexes members under it, and the two must agree exactly
     * or the members land on an entry nothing resolves to. Deriving it twice from one function is the same
     * arrangement {@link DerivedName#ofBinding} already has with its two lift channels; two functions of one
     * template would be the defect {@code WireForm}'s own note warns about.
     *
     * <p><b>It is a function of the template's name and its held fields alone</b>, so the linker derives the
     * same name for an <em>imported</em> template as the declaring schema minted -- which is what lets a
     * schema name another schema's template at a type position at all.
     */
    public static Optional<String> parentNameOf(String templateName, TypeDefinition template) {
        return parentBodyOf(template).map(body -> DerivedName.ofBinding(templateName, wireFields(body)));
    }

    /**
     * The <b>erased</b> record a template's parent carries, or empty where the template has no parent
     * ({@code SPEC-FEEDBACK.md} #13) -- the one input both {@link #parentNameOf} and the entry itself are
     * built from, so the name and the body cannot describe different records.
     */
    public static Optional<RecordBody> parentBodyOf(TypeDefinition template) {
        if (!(template.body() instanceof TemplateBody held) || held.extension().isEmpty()) {
            return Optional.empty();
        }
        return WireForm.parentBody(of(held).application(), template.parameters(), held.extension().get());
    }

    /** The parent's canonical rendering, for §8.2's freshness check ({@code MintedNames}). */
    static String canonicalParent(String templateName, RecordBody body) {
        return DerivedName.canonicalBinding(templateName, wireFields(body));
    }

    /**
     * The erased body back in wire form, which is what both naming functions render.
     *
     * <p><b>The erased body and not the held one, which is the whole point.</b> A held body still carries the
     * parameter tokens, so naming from it would splice {@code T} and {@code N} into the entry name -- two
     * templates identical up to a consistent renaming of their parameters would reach two parents, where
     * §8.2 makes them one form, and an importer deriving the name would agree with the declaring schema only
     * by having chosen the same parameter letters. Erasure has already dropped every parameter-typed field,
     * so what is rendered here mentions none.
     */
    private static List<RecordValue.Field> wireFields(RecordBody body) {
        return WireForm.heldRecord(body, value -> {
            throw new IllegalStateException("a parent's erased fields carry no annotation values");
        }).coreValue() instanceof RecordValue record ? record.fields() : List.of();
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
