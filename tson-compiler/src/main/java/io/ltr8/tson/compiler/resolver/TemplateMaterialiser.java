package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * §5.10 materialisation: closes a template application by substituting its arguments into the template's
 * recorded open form, and replaces the application with a reference to the entry that results.
 *
 * <p><b>It runs over the resolved form, not the AST.</b> An application reaches here as a
 * {@link TypeRef} carrying {@code arguments} -- the one thing that shape means (a closed form is always an
 * entry named by a bare reference) -- so substitution is a walk over {@code schema.meta} values, and the
 * entry it produces can record its own {@code source}, which §8.2 keys identity on. Doing it over the AST
 * instead would reuse {@code SchemaDesugarer}'s injection machinery but has no channel for that {@code
 * source}, and would put type-level work back into a phase Tranche A made purely syntactic.
 *
 * <p><b>A held body closes by one process, whatever wrote it.</b> {@code <T> [T]} and {@code <T> { x: T }}
 * are both an application with a parameter standing in a slot -- {@code !array { element_type: T }} and
 * {@code !record { fields: [ { name: x  type: T } ] }} -- so both substitute by the same walk and are read
 * back through their own constructor's reader. See {@link #closeHeld}. What differs is only what the result
 * <em>is</em>: a <b>record</b> template's closure is the instantiation entry itself ({@link
 * #closeHeldRecord}), because a substituted record is the type the author named by writing the application;
 * every other held form closes to a <em>synthetic</em> named for the form, which the instantiation then
 * references ({@link #closeHeldTemplate}), because a form has no author-written name to be keyed on.
 *
 * <p><b>An alias closes by a third path and mints nothing.</b> §5.10's partial application,
 * {@code uuid_pair => <B> pair<uuid, B>}, holds {@code !reference { target: pair<uuid, B> }} like any other
 * open entry, but it <em>is</em> the application it names with some arguments still open -- so closing it
 * composes the two argument lists and hands back what that denotes, minting no entry of its own (§5.10: "no
 * intermediate entry per alias hop"). See {@link #closeHeldAlias}.
 *
 * <p><b>So the three cases are told apart by the constructor head, not by the body's shape.</b> Every open
 * entry's body is a {@link HeldBody} -- a record, composition or refinement template, a sugar form's lift,
 * an alias, and an error placeholder alike ({@code WireForm.heldEmptyRecord}). {@code record} closes
 * to the instantiation, {@code reference} to a name, everything else to a synthetic.
 *
 * <p><b>Identity (§8.2).</b> An instantiation entry is keyed on the flattened application recorded in
 * {@code source}, so two {@code box<text>} anywhere in the schema land on one entry. The derived name is
 * built by {@link DerivedName#ofApplication} from the application itself, which is what makes that dedup fall out of
 * naming rather than needing a second table.
 *
 * <p><b>Knot-tying.</b> The memo entry is registered <em>before</em> the body is substituted, so a
 * recursive application reached during substitution ({@code tree<T>} inside {@code tree}, which becomes
 * {@code tree<text>} once {@code T} is bound) finds the entry under construction and references it by name
 * rather than recursing forever.
 */
final class TemplateMaterialiser {

    /**
     * Every entry visible to this schema -- local declarations and merged {@code !!import}s alike. A
     * <em>getter</em> rather than a fixed map, because an application closed on demand during resolution
     * (at a supertype or refinement source) may name a head that has not been resolved yet; the getter is
     * {@code SchemaResolver}'s own memo, so asking for it resolves it, with the circular-composition guard
     * still in front.
     */
    private final DefinitionGetter namespace;


    /** The constructor a held record template carries -- its closure is the instantiation itself. */
    private static final String RECORD = "record";

    /** The constructor a held alias carries -- §5.10's partial application, which mints no entry. */
    private static final String REFERENCE = "reference";

    /** {@code reference}'s own member, the one an alias closes through. */
    private static final String TARGET = "target";

    /** The entries produced, keyed by their derived internal name, in creation order. */
    private final Map<String, TypeDefinition> materialised = new LinkedHashMap<>();

    /** §8.2's freshness MUST over the names this class mints -- see {@link MintedNames}. */
    private final MintedNames minted = new MintedNames();

    /**
     * Which of {@link #materialised} are <b>synthetic</b> entries rather than instantiation entries -- the
     * closed forms {@link #closeHeldTemplate} mints, which are indistinguishable from the entry a
     * directly-written {@code [pixel; 1920]} lifts to and are the same entry when both appear (§8.2).
     *
     * <p>§8.2 marks only these: an instantiation entry carries no {@code @synthetic}, its {@code source}
     * being an application where a synthetic's is a bare constructor. The caller reads this set to put the
     * marker on the right keys.
     */
    private final Set<String> synthetics = new LinkedHashSet<>();

    /** Applications currently being closed, for the knot-tying memo and the termination guard's chain. */
    private final Set<String> closing = new LinkedHashSet<>();

    /**
     * Applications of <em>reference</em> templates currently composing. They mint no entry of their own
     * (§5.10: "no intermediate entry per alias hop"), so {@link #closing}'s knot-tying answer -- name the
     * entry under construction -- has nothing to name for them, and an alias that applies itself would hand
     * back a name nothing ever defines. Tracked separately so that case is a diagnosis instead.
     */
    private final Set<String> aliasClosing = new LinkedHashSet<>();

    /** The author-written head each link of {@link #closing} came from, outermost first. */
    private final List<String> heads = new ArrayList<>();

    /**
     * How deep the closing chain may go before materialisation is abandoned -- a <b>backstop</b>, not the
     * rule.
     *
     * <p>{@code TemplateRegularity} rejects a template that grows its argument on a recursive step where it
     * is <em>declared</em>, so nothing that reaches here should be able to run away: <em>regular</em>
     * recursion ties the knot on its first repeat and never nests, since {@code chain<text>} reaches
     * {@code chain<text>} again and finds it in {@link #closing}. This stays because the alternative
     * failure, if that check ever has a hole, is a {@link StackOverflowError} -- not a diagnosis, and not
     * something the exception policy can classify. One comparison for that is worth paying.
     *
     * <p>Distinct from §5.10.1's productivity rule, which is about a type with no finite <em>data</em>
     * model; the regularity rule is about one with no finite <em>type</em> model.
     */
    private static final int MAX_CLOSING_DEPTH = 64;

    /**
     * Where each entry is published as it is built, so the namespace can see it immediately. Load-bearing
     * for the on-demand half: a composition supertype closes an application and then looks the resulting
     * name up through {@code namespaceDefinitions} to absorb its fields, which is the very next thing that
     * happens -- an entry only in this pass's own map would be invisible to it.
     */
    private final BiConsumer<String, TypeDefinition> publish;

    /**
     * How a closed held body becomes an ordinary constructor body -- the constructor's own
     * compiled reader, the same one a written {@code !array { ... }} binds through. Using it is what makes
     * {@code min_items: "two"} an ordinary read error rather than a check this class would have to grow: the
     * bindings a template defers are exactly the ones a closed instance has always had checked for it.
     */
    private final DefinitionMetaReader metaReader;

    /**
     * The entry names desugaring generated rather than the author writing them.
     *
     * <p><b>An application of one is machinery, not a use site.</b> Closing an authored template records the
     * application in an instantiation entry, because {@code grid<pixel, 3>} is something someone wrote and
     * §8.2 keys identity on it. Closing a generated open synthetic records nothing: nobody wrote
     * {@code array_p0_p1_p1_06c4e11f<pixel, 3>}, and an entry named for it would key identity on an internal
     * name D6 is explicit must not be relied on.
     */
    private final Set<String> generated;

    /**
     * Each template's parameter kinds ([TSON-SCHEMA] §5.10), by entry name then parameter name -- what lets
     * an argument be classified by the parameter it binds rather than by the shape of the token that spells
     * it. Empty until {@code SchemaResolver} has inferred them, which it cannot do before every declaration
     * has resolved; an application closed on demand before that point classifies as it always did.
     */
    private Map<String, Map<String, ParameterKinds.Kind>> parameterKinds = Map.of();

    /**
     * The same question answered one template at a time, for an application closed before the batch pass
     * could run -- a composition supertype or a refinement source, both of which close during resolution's
     * own driving loop. Memoised because a template is typically applied more than once.
     */
    private final Map<String, Map<String, ParameterKinds.Kind>> kindsOnDemand = new LinkedHashMap<>();

    /** The governing meta's entries, which is where a slot's declared type is read from. */
    private final Function<String, TypeDefinition> metaTypes;

    TemplateMaterialiser(DefinitionGetter namespace, BiConsumer<String, TypeDefinition> publish,
            DefinitionMetaReader metaReader, Set<String> generated,
            Function<String, TypeDefinition> metaTypes) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.metaReader = Objects.requireNonNull(metaReader, "metaReader");
        this.generated = Objects.requireNonNull(generated, "generated");
        this.metaTypes = Objects.requireNonNull(metaTypes, "metaTypes");
    }

    /** The inferred kinds, once {@code SchemaResolver} has them -- see {@link #parameterKinds}. */
    void parameterKinds(Map<String, Map<String, ParameterKinds.Kind>> kinds) {
        this.parameterKinds = kinds;
    }

    /**
     * Closes every application reachable from {@code entries}, returning the instantiation entries it
     * produced; {@code entries} is rewritten in place, each application replaced by a bare reference.
     *
     * <p>The returned map is separate from {@code entries} because the caller decides where the new entries
     * land -- they are local to this schema and carry no source position, being named by derivation rather
     * than declared.
     */
    Map<String, TypeDefinition> materialise(Map<String, TypeDefinition> entries,
            MaterialisationFailureReporter reporter) {
        TemplateMaterialiser pass = this;
        for (Map.Entry<String, TypeDefinition> entry : entries.entrySet()) {
            if (!entry.getValue().parameters().isEmpty()) {
                // A template's own body is open: `chain<T>` inside `chain` awaits substitution and is not an
                // application to close. Closing it here would mint an entry per level, keyed on the literal
                // parameter name.
                continue;
            }
            try {
                entry.setValue(pass.rewrite(entry.getValue()));
            } catch (TsonSchemaValidationException e) {
                if (reporter == null) {
                    throw e;
                }
                // Reported against the entry that wrote the application, and left as it was: an entry still
                // naming an open template is one the linker reports again, but that second complaint is
                // about the same line and does not invent a new problem.
                reporter.reportFailedApplication(entry.getKey(), e);
            }
        }
        return pass.materialised;
    }

    /**
     * The subset of what {@link #materialise} returned that is a synthetic entry, whose key carries the
     * derived {@code @synthetic} marker (§8.2). Everything else it returned is an instantiation entry, which
     * deliberately carries none.
     */
    Set<String> syntheticNames() {
        return Set.copyOf(synthetics);
    }

    /** Where an application this pass cannot close is reported, entry by entry. */
    @FunctionalInterface
    interface MaterialisationFailureReporter {
        void reportFailedApplication(String entryName, TsonSchemaValidationException error);
    }

    /**
     * The entry a fully-bound application denotes, closing it if this is the first sight of it -- the
     * on-demand half, reached from a supertype or refinement-source position during resolution rather than
     * from the batch pass afterwards. Both share this instance, so an application closed here and the same
     * one met later in a field land on one entry.
     */
    String closeApplication(TypeRef application) {
        return close(application).name();
    }

    /** One definition with every application inside it closed. */
    private TypeDefinition rewrite(TypeDefinition definition) {
        return MetaRefs.mapRefs(definition, this::close);
    }

    /**
     * One type-ref with its application closed, or itself when it carries no arguments. Arguments close
     * first, so {@code box<box<text>>} produces the inner entry before the outer one names it.
     *
     * <p>An application this pass cannot close <b>keeps its argument list</b>, rather than collapsing to its
     * bare head. The list is the evidence the author supplied arguments, and the linker reports on what it is
     * handed: a head stripped of its arguments in a {@code source} position -- the one slot whose lookup falls
     * back to the governing meta's structure namespace -- is found there and then faulted for supplying no
     * arguments, which is the opposite of what the author wrote. Keeping them lets the honest verdict through:
     * a name the type-name namespace does not hold is an unresolved reference, applied or not.
     */
    private TypeRef close(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeArgument> arguments = new ArrayList<>();
        for (TypeArgument argument : ref.arguments()) {
            arguments.add(argument instanceof TypeArgument.Ref nested
                    ? new TypeArgument.Ref(dereferenced(close(nested.ref())))
                    : argument);
        }
        String entry = instantiate(ref.name(), arguments);
        return entry == null ? new TypeRef(ref.name(), arguments, ref.annotations()) : TypeRef.of(entry);
    }

    /**
     * A type argument named by its own type rather than by a name for it: a bare reference is replaced with
     * the entry at the end of its chain.
     *
     * <p><b>Because a reference is a pure rename, and an application of one denotes the same type.</b>
     * {@code user_id => uuid} makes {@code user_id} and {@code uuid} interchangeable at every position (§7.2
     * compares "after reference flattening of both"), so {@code box<user_id>} and {@code box<uuid>} are one
     * type and must be one entry. Without this they were two, which left the model saying the arguments were
     * the same type while the applications were not -- interchangeable at a scalar position and refused one
     * layer of application up.
     *
     * <p><b>An author who wants two boxes told apart has two spellings that say so</b>, and this is what
     * makes them mean something: {@code user_id => !uuid ^ {}} is a refinement, IS-A {@code uuid} and not its
     * siblings, and {@code user_id => !uuid_type {}} is a fresh type related to neither. Both are ordinary
     * entries rather than references, so neither is dereferenced here.
     *
     * <p><b>What this normalises is identity, not provenance.</b> The minted entry's {@code source} becomes
     * the canonical application, and the name the author wrote survives where they wrote it -- at the use
     * site, which states it as written. The two facts have one home each.
     *
     * <p><b>Known wrong for a reference carrying {@code @bytes_encoding}</b> (`SPEC-FEEDBACK.md` #32): such
     * an alias is not a pure rename -- values at its positions are spelled in another alphabet -- so
     * dereferencing it loses the directive. It is accepted here rather than special-cased, because the fix
     * is to decide what a directive on a reference means at all, not to make identity guess.
     */
    private TypeRef dereferenced(TypeRef ref) {
        if (!ref.arguments().isEmpty()) {
            return ref; // an application, already closed above to the entry it denotes
        }
        String terminal = ReferenceChain.terminal(ref.name(), namespace::getTypeDefinition);
        return terminal.equals(ref.name()) ? ref : new TypeRef(terminal, ref.arguments(), ref.annotations());
    }

    /**
     * The entry name a fully-bound application denotes, creating the entry on first sight, or {@code null}
     * when the head names nothing in scope -- that is {@code TsonSchemaLinker}'s verdict to give as an
     * unresolved reference, not this pass's to guess at.
     */
    private String instantiate(String head, List<TypeArgument> arguments) {
        TypeDefinition template = namespace.getTypeDefinition(head);
        if (template == null) {
            return null; // unresolved head -- the linker's verdict, not this pass's
        }
        List<String> parameters = template.parameters();
        if (parameters.isEmpty()) {
            throw new TsonSchemaValidationException("'" + head + "' declares no type parameters, so '"
                    + head + "<...>' applies arguments to something that takes none (§5.10); drop the "
                    + "argument list");
        }
        if (parameters.size() != arguments.size()) {
            throw new TsonSchemaValidationException("'" + head + "' takes " + parameters.size()
                    + " type argument" + (parameters.size() == 1 ? "" : "s") + " " + parameters + ", but "
                    + arguments.size() + " " + (arguments.size() == 1 ? "was" : "were") + " applied (§5.10)");
        }
        arguments = byParameterKind(head, template, parameters, arguments);
        String name = DerivedName.ofApplication(head, arguments);
        if (aliasClosing.contains(name)) {
            throw new TsonSchemaValidationException("'" + head + "<...>' is a reference template whose own "
                    + "body applies it again, so composing it never reaches a type with a body (§5.10). The "
                    + "chain begins " + chain() + ". A reference template must eventually name a declared "
                    + "type; recursion belongs in a record, tuple or choice body, where a field can carry it");
        }
        if (materialised.containsKey(name) || !closing.add(name)) {
            // Already built, or under construction -- the knot-tying case. Either way it must be *this*
            // application, not another that derived the same name.
            minted.claim(name, DerivedName.canonicalApplication(head, arguments));
            return name;
        }
        minted.claim(name, DerivedName.canonicalApplication(head, arguments));
        if (closing.size() > MAX_CLOSING_DEPTH) {
            closing.remove(name);
            // Named for the *outermost* head, which is the one the author wrote; the head in hand here is
            // whichever link happened to tip the depth over.
            throw new TsonSchemaValidationException("'" + heads.get(0) + "<...>' does not close: "
                    + "materialising it needs more than " + MAX_CLOSING_DEPTH + " nested instantiations and "
                    + "each one differs from the last, so the arguments are growing rather than repeating and "
                    + "there is no finite set of types to build (§5.10). The chain begins " + chain()
                    + ". A recursive template must reach an argument it has already been applied to");
        }
        heads.add(head);
        try {
            // Every shape reaches here, so every shape gets the memo, the depth backstop and one publish
            // path. An open *instance* used to short-circuit ahead of all three, which left a template
            // applying itself (`weird => <T> [weird<T>]`) recursing to a StackOverflowError instead of
            // tying the knot.
            // §5.10's partial application mints nothing at all: the alias *is* the application it names
            // with some arguments still open, so closing it composes the two argument lists and hands back
            // whatever that denotes -- `uuid_pair<int32>` is the entry `pair<text, int32>` already produced.
            // "No intermediate entry per alias hop" is the rule, which is why this returns a name rather
            // than making one, and why it is the head that tells the three cases apart rather than the body.
            if (template.body() instanceof HeldBody open
                    && REFERENCE.equals(open.application().typeRef().orElseThrow())) {
                aliasClosing.add(name);
                return closeHeldAlias(head, template, open, bind(parameters, arguments));
            }
            // A record template's closure is the instantiation itself, where every other held form closes to
            // a synthetic the instantiation then references -- see closeHeldRecord.
            if (template.body() instanceof HeldBody open
                    && RECORD.equals(open.application().typeRef().orElseThrow())) {
                TypeDefinition instantiation =
                        closeHeldRecord(head, template, open, arguments, bind(parameters, arguments));
                materialised.put(name, instantiation);
                publish.accept(name, instantiation);
                return name;
            }
            if (template.body() instanceof HeldBody open) {
                String formName = closeHeldTemplate(head, template, open, bind(parameters, arguments));
                if (generated.contains(head)) {
                    // A generated head closing its own intermediate form: the form entry *is* the answer, and
                    // an instantiation naming this head would carry an internal name into identity.
                    return formName;
                }
                TypeDefinition alias = instantiationOf(head, arguments, formName);
                materialised.put(name, alias);
                publish.accept(name, alias);
                return name;
            }
            // Every open entry's body is held -- a record, composition or refinement template, a sugar
            // form's lift, an alias, and an error placeholder alike (WireForm.heldEmptyRecord). The
            // three branches above are the whole of §12.1's open form, told apart by the head they carry
            // rather than by what shape the body arrived in. So this is a broken invariant, not an author
            // error and not a gap.
            throw new IllegalStateException("'" + head + "' declares type parameters but its body is a "
                    + template.body().getClass().getSimpleName() + " -- every open entry's body is held, and "
                    + "nothing else can be substituted into");
        } finally {
            closing.remove(name);
            aliasClosing.remove(name);
            heads.remove(heads.size() - 1);
        }
    }

    /** The first few links of the closing chain, for the termination guard's message. */
    private String chain() {
        List<String> shown = closing.stream().limit(4).toList();
        return String.join(" -> ", shown) + (closing.size() > shown.size() ? " -> ..." : "");
    }

    /** Each parameter of the applied signature against the argument applied for it, in order. */
    private static Map<String, TypeArgument> bind(List<String> parameters, List<TypeArgument> arguments) {
        Map<String, TypeArgument> bindings = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            bindings.put(parameters.get(i), arguments.get(i));
        }
        return bindings;
    }

    // ── Closing an open instance (§5.10, D7) ─────────────────────────────────────────────────────

    /**
     * The entry an application of an <b>open instance</b> denotes -- a template whose held body is a
     * constructor application rather than a record. Substituting turns its bindings concrete, and the result
     * is no longer a template at all: it is the constructor body those bindings always described, so it is
     * bound through that constructor's own reader and the entry carries an ordinary body.
     *
     * <p><b>Two entries come out of it, because one cannot carry two identities.</b> The body itself is a
     * closed <em>synthetic</em>, named for the form and sourced to the constructor it builds (§8.2) -- an
     * open synthetic's own name is internal, so keying it on the application would make identity depend on an
     * unstable name, and would leave {@code [text]} written directly and {@code [T]} closed to {@code text}
     * on two entries for one type. But the same closure is also an <em>instantiation</em> of the template,
     * and §8.2 keys that on the flattened application. So this publishes the synthetic and returns a
     * reference entry pointing at it, whose {@code source} is the application.
     *
     * <p>Without the second entry nothing in resolver output records that {@code grid<pixel, 3>} was ever
     * written: the field would name the array directly, and the template's name would vanish. The record
     * template shape gets this for free, since substituting a record yields a record -- structurally distinct
     * from any synthetic, so it can be the instantiation itself.
     */
    private String closeHeldTemplate(String head, TypeDefinition template, HeldBody open,
            Map<String, TypeArgument> bindings) {
        String target = open.application().typeRef().orElseThrow();
        Closed closed = closeHeld(head, template, open, bindings);
        // Named before the entry is built and from the wire slots as written, which is what keeps one type on
        // one entry: the desugar phase lifts innermost-first, so a form it writes already names the entry its
        // inner form became, and a form closed here has to agree with it or `[[pixel; 3]; 3]` written out and
        // `grid<pixel, 3>` closed would be two entries for one type.
        List<RecordValue.Field> fields =
                closed.wire() instanceof RecordValue record ? record.fields() : List.of();
        String formName = DerivedName.ofBinding(target, fields);
        minted.claim(formName, DerivedName.canonicalBinding(target, fields));
        if (namespace.getTypeDefinition(formName) != null) {
            return formName; // already built, here or by the desugar phase -- one entry per form, schema-wide
        }
        TypeDefinition definition = new TypeDefinition(Optional.of(TypeRef.of(target)), template.kind(),
                List.of(), false, List.of(), List.of(), Optional.empty(), closed.body());
        materialised.put(formName, definition);
        synthetics.add(formName);
        publish.accept(formName, definition);
        return formName;
    }

    /**
     * A held <b>alias</b> closed: §5.10's partial application, which mints no entry of its own.
     *
     * <p>The first two steps are every held body's -- substitute the parameters, then close the application
     * standing in a slot. What differs is what is left afterwards: nothing to build. {@code
     * uuid_pair<int32>} composed to {@code pair<text, int32>}, and closing that already produced the entry,
     * so its name is the answer. §5.10 is explicit that an alias hop mints no intermediate entry; the origin
     * survives in the composed entry's own {@code source}.
     *
     * <p><b>The knot-tying memo cannot serve this path</b>, which is why {@link #aliasClosing} exists. That
     * memo answers a recursive application with the name of the entry under construction, and this
     * constructs none -- so a self-applying alias ({@code loop => <B> loop<B>}) would be handed a name
     * nothing ever defines. {@link #close} checks {@code aliasClosing} before reaching here and reports the
     * cycle instead.
     */
    private String closeHeldAlias(String head, TypeDefinition template, HeldBody open,
            Map<String, TypeArgument> bindings) {
        CoreValue substituted = WireForm.substitute(open.application().coreValue(), head, template.parameters(),
                bindings);
        CoreValue closed = closeApplications(substituted);
        CoreValue target = closed instanceof RecordValue record ? WireForm.field(record, TARGET).orElse(null) : null;
        if (!(target instanceof TokenValue token)) {
            throw new IllegalStateException("'" + head + "<...>' is an alias whose target did not close to a "
                    + "name: " + target + " -- SchemaDesugarer writes `!reference { target: <type_ref> }` and "
                    + "closeApplications reduces an application there to the entry it denotes");
        }
        return token.text();
    }

    /**
     * A <b>record</b> template's closure, which is the instantiation entry itself rather than a synthetic
     * with a reference to it. Substituting a record yields a record -- what the author declared, not a form
     * derived from a sugar spelling -- so there is nothing for the extra hop to record, and the entry carries
     * the application in its own {@code source} the way §8.2 says every instantiation does.
     *
     * <p><b>Which is why the two shapes part company here and nowhere earlier.</b> Everything up to this
     * point is common: one held body, one substitution, one set of closed inner applications. What differs is
     * only what the result <em>is</em> -- a form that needs a name of its own, or the type the author named
     * by writing the application.
     */
    private TypeDefinition closeHeldRecord(String head, TypeDefinition template, HeldBody open,
            List<TypeArgument> arguments, Map<String, TypeArgument> bindings) {
        Closed closed = closeHeld(head, template, open, bindings);
        return new TypeDefinition(Optional.of(new TypeRef(head, arguments)), template.kind(), List.of(),
                template.constructor(), template.supertypes(), template.subtypes(), Optional.empty(),
                fixRoutedValues(closed.body()));
    }

    /** A held body substituted, its inner applications closed, and read back through its constructor. */
    private Closed closeHeld(String head, TypeDefinition template, HeldBody open,
            Map<String, TypeArgument> bindings) {
        String target = open.application().typeRef().orElseThrow();
        // One walk does what three steps used to: a parameter in a slot, a parameter inside an application a
        // slot holds (`tree<p0>` becoming `tree<text>`), and a parameter inside a collection are all the same
        // thing here -- a token in a tree -- because the body was never read against the constructor's
        // vocabulary in the first place.
        CoreValue substituted = WireForm.substitute(open.application().coreValue(), head, template.parameters(), bindings);
        CoreValue wire = closeApplications(substituted);
        try {
            return new Closed(wire, metaReader.read(target, new DataValue(List.of(), Optional.of(target), wire)));
        } catch (TsonReadException e) {
            // The bindings a template defers are checked here and nowhere else (§8.2): `<T, N> [T; N]` is a
            // fine declaration, and `vector<text, "two">` is where it stops being one.
            throw new TsonSchemaValidationException("'" + head + "<...>' substitutes into a body that is not "
                    + "valid data for '" + target + "', the constructor's own constraint vocabulary -- "
                    + e.getMessage(), e);
        }
    }

    /** A closed held body, and the wire form it was read from -- the one an entry name derives from. */
    private record Closed(CoreValue wire, Top body) {
    }

    /**
     * §5.7's fixation, applied where the section says it happens: "fixation happens downstream, where values
     * are concrete". A field routed by {@code = P} is held as {@code state: REQUIRED} with the parameter
     * standing in {@code value}, and a REQUIRED field carrying a value is that and nothing else -- a closed
     * REQUIRED field has none, which is what {@code REQUIRED_FIXED} means. Once substitution has made the
     * value concrete the field takes the state its literal spelling would have had. A {@code ~ P} default
     * arrives as {@code REQUIRED_DEFAULT} and stays one: data may still override it.
     */
    private static Top fixRoutedValues(Top body) {
        if (!(body instanceof RecordBody record)) {
            return body;
        }
        return new RecordBody(record.supertypes(), record.fields().stream()
                .map(field -> field.state() == FieldState.REQUIRED && field.value().isPresent()
                        ? field.withState(FieldState.REQUIRED_FIXED)
                        : field)
                .toList(), record.groups());
    }

    /**
     * What a closed form is called once every application inside it is an entry name -- the derivation {@link
     * #closeHeldTemplate} already names by, offered to {@link SyntheticMerge} so the other lift channel's
     * products can be re-derived against it.
     *
     * <p>Both channels aim at one rule: name the binding record with every inner form reduced to its entry
     * name. {@code SchemaDesugarer} reaches it by lifting innermost-first, and cannot where an inner form is
     * an <em>application</em>, which has no entry until this pass runs. So the name settles here, after
     * materialisation, which is the moment [TSON-SCHEMA] §8.2 names -- "identity settles after Pass 2, when
     * references have resolved".
     *
     * <p>Safe to call once {@link #materialise} has returned: every application this reaches was closed then,
     * so {@link #close} answers from its memo rather than minting anything.
     */
    String closedFormName(String head, List<RecordValue.Field> fields) {
        CoreValue wire = closeApplications(new RecordValue(fields));
        return DerivedName.ofBinding(head,
                wire instanceof RecordValue record ? record.fields() : List.of());
    }

    /**
     * Every application still written in {@code type_ref}'s record form, closed to a bare reference to the
     * entry it denotes -- the inverse of the shape {@code SchemaDesugarer} writes when a slot holds one.
     *
     * <p>It runs on the wire value rather than on the body read from it because the <em>name</em> depends on
     * it: {@code DerivedName.ofBinding} reads the slots as written, and the desugar phase expands
     * innermost-first, so by the time it names an outer form the inner one is already a bare name. Closing
     * here in the same order is what makes the two phases agree on what a form is called.
     */
    private CoreValue closeApplications(CoreValue value) {
        return switch (value) {
            case RecordValue record when WireForm.isApplication(record) ->
                    new TokenValue(close(WireForm.typeRefOf(record)).name(), TokenForm.UNQUOTED);
            case RecordValue record -> new RecordValue(record.fields().stream()
                    .map(field -> new RecordValue.Field(field.name(),
                            WireForm.rescope(field.value(), closeApplications(field.value().value().coreValue()))))
                    .toList());
            case ArrayValue array -> new ArrayValue(array.elements().stream()
                    .map(element -> WireForm.rescope(element, closeApplications(element.value().coreValue()))).toList());
            // A map slot holds applications like any other: `{ S => [box<text>] }` writes one in the array
            // its value names, and a key type could take one too. Both halves descend, as they do in
            // WireForm.substitute, which walks the same shape one step earlier.
            case MapValue map -> new MapValue(map.entries().stream()
                    .map(entry -> new MapValue.MapEntry(
                            WireForm.retyped(entry.key(), closeApplications(entry.key().coreValue())),
                            WireForm.rescope(entry.value(), closeApplications(entry.value().value().coreValue()))))
                    .toList());
            default -> value;
        };
    }

    /**
     * The instantiation entry for an application whose closure is a synthetic: a reference to that synthetic,
     * sourced to the application itself. {@code Reference} bodies are collapsed by the compiler, so the hop
     * costs nothing at read time -- what it buys is that §8.2's "instantiation entries are keyed on the
     * flattened application recorded in {@code source}" is true of this template shape too, not only of the
     * record one.
     */
    private static TypeDefinition instantiationOf(String head, List<TypeArgument> arguments, String formName) {
        return new TypeDefinition(Optional.of(new TypeRef(head, arguments)), TypeKind.REFERENCE, List.of(),
                false, List.of(), List.of(), Optional.empty(), new Reference(TypeRef.of(formName)));
    }

    /**
     * The arguments reclassified by the kind of the parameter each binds ([TSON-SCHEMA] §5.10).
     *
     * <p>§12.1 decides an argument's channel by the shape of the token that spells it, so an unquoted
     * non-numeric argument always arrives as a reference. That is the right default with nothing else known,
     * but §5.10 says an argument is "read by the position it lands in" -- and once the parameter's kind is
     * inferred, the position is known before substitution rather than after. An argument binding a
     * <b>value</b> parameter becomes the token it always was: {@code e<c>} against
     * {@code e => <M> !enum {{ members: [a b M] }}} records {@code value: c}, so nothing downstream asks the
     * namespace for a type called {@code c}.
     *
     * <p>Only a bare reference converts. One carrying arguments is an application, which no value parameter
     * could bind (§5.10 confines value parameters to scalars), and is left for the position to refuse.
     */
    private List<TypeArgument> byParameterKind(String head, TypeDefinition template,
                                                List<String> parameters, List<TypeArgument> arguments) {
        Map<String, ParameterKinds.Kind> kinds = parameterKinds.get(head);
        if (kinds == null) {
            kinds = kindsOnDemand.computeIfAbsent(head, ignored -> ParameterKinds.inferOne(template, metaTypes));
        }
        if (kinds == null || kinds.isEmpty()) {
            return arguments;
        }
        List<TypeArgument> bound = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            bound.add(arguments.get(i) instanceof TypeArgument.Ref ref && ref.ref().arguments().isEmpty()
                    && kinds.get(parameters.get(i)) == ParameterKinds.Kind.VALUE
                            ? new TypeArgument.Value(new Token(ref.ref().name(), Token.Form.UNQUOTED))
                            : arguments.get(i));
        }
        return bound;
    }

}
