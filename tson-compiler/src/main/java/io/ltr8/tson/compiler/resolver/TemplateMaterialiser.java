package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;

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
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * §5.10 materialisation: closes a template application by substituting its arguments into the template's
 * recorded open form, and replaces the application with a reference to the entry that results.
 *
 * <p><b>It runs over the resolved form, not the AST.</b> An application reaches here as a
 * {@link TypeRef} carrying {@code arguments} -- the one thing that shape means (a closed form is always an
 * entry named by a bare reference) -- so substitution is a walk over {@code schema.meta} values, and the
 * entry it produces is named from what the closing produced. Doing it over the AST instead would reuse {@code
 * SchemaDesugarer}'s injection machinery and put type-level work back into a phase that is purely syntactic.
 *
 * <p><b>A held body closes by one process, whatever wrote it.</b> {@code <T> [T]} and {@code <T> { x: T }}
 * are both an application with a parameter standing in a slot -- {@code !array { element_type: T }} and
 * {@code !record { fields: [ { name: x  type: T } ] }} -- so both substitute by the same walk and are read
 * back through their own constructor's reader. See {@link #closeHeld}. <b>An application nothing declares is
 * its expansion</b> (§5.10): the closure is a closed <em>synthetic</em> named for its binding record, a record
 * ({@link #mintedRecord}) like any other form ({@link #closeHeldTemplate}), so two applications closing to one
 * form are one entry. A declaration naming an application is that entry under its own name ({@link
 * #closeApplicationInto}).
 *
 * <p><b>An alias closes by a third path and mints nothing.</b> §5.10's partial application,
 * {@code uuid_pair => <B> pair<uuid, B>}, holds {@code !alias { target: pair<uuid, B> }} like any other
 * open entry, but it <em>is</em> the application it names with some arguments still open -- so closing it
 * composes the two argument lists and hands back what that denotes, minting no entry of its own (§5.10: "no
 * intermediate entry per alias hop"). See {@link #closeHeldAlias}.
 *
 * <p><b>So the three cases are told apart by the constructor head, not by the kernel shape.</b> Every open
 * entry has a {@link HeldBody} -- a record template's tree written from the open record, a sugar form's lift,
 * an alias, and an error placeholder alike. {@code alias} closes to a name, everything else to a
 * synthetic.
 *
 * <p><b>Knot-tying, and the one instantiation entry.</b> The memo entry is registered <em>before</em> the body
 * is substituted, under a name derived from the application ({@link DerivedName#ofApplication}), so a
 * recursive application reached during substitution ({@code tree<T>} inside {@code tree}, which becomes
 * {@code tree<text>} once {@code T} is bound) finds the entry under construction and references it by name
 * rather than recursing forever. Such an expansion names itself, so its content cannot be written without a
 * name: it keeps the instantiation entry the knot was tied on, keyed on the application and recording it
 * ({@code @application}, §8.2) -- the record itself, or a reference to the synthetic for any other form.
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


    /** The constructor a record template's tree applies. */
    private static final String RECORD = "record";

    /** The constructor a held alias carries -- §5.10's partial application, which mints no entry. */
    private static final String ALIAS = "alias";

    /** {@code alias}'s own member, the one an alias closes through. */
    private static final String TARGET = "target";

    /** The entries produced, keyed by their derived internal name, in creation order. */
    private final Map<String, Top> materialised = new LinkedHashMap<>();

    /**
     * The application each instantiation this pass closed is the instantiation of, by entry name (§8.2) -- shared
     * with {@code SchemaResolver}, which keys the applied-parent fold on it and writes it on the entry's key.
     */
    private final Map<String, TypeRef> applications;

    /**
     * The facts of every entry this pass mints or closes into a declaration -- the constructor its value applies
     * and the application it closes. {@code SchemaResolver}'s own map, shared so a minted entry's facts are
     * there the moment it is published.
     */
    private final Map<String, TsonSchema.EntryFacts> facts;

    /** The constructor an entry's value applies, through the schema's facts and then its own class. */
    private final Function<String, Optional<String>> localConstructors;

    /** The same, of an imported entry. */
    private final Function<String, Optional<String>> metaConstructors;

    /** §8.2's freshness MUST over the names this class mints -- see {@link MintedNames}. */
    private final MintedNames minted = new MintedNames();

    /**
     * Which of {@link #materialised} are <b>synthetic</b> entries rather than instantiation entries -- the
     * closed forms {@link #closeHeldTemplate} mints, which are indistinguishable from the entry a
     * directly-written {@code [pixel; 1920]} lifts to and are the same entry when both appear (§8.2).
     *
     * <p>§8.2 marks only these: an instantiation entry carries no {@code @synthetic}, it being the closure of
     * an application where a synthetic is a constructor's instance. The caller reads this set to put the
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
     * Which <b>declared</b> name owns the entry for an application, keyed on §8.2's canonical application
     * (§8.2). Populated by {@link #closeApplicationInto} and read by {@link
     * #instantiate} before it derives a name of its own, so it answers two questions with one map.
     *
     * <p><b>Knot-tying</b>: a recursive application inside a declaration-owned closure -- {@code use =>
     * tree<text>} over {@code tree => <T> { children: [tree<T>; 1..] }} -- re-derives the same canonical
     * form, and without this would mint a content-named entry beside {@code use} for the very recursion
     * {@code use} names. <b>Reuse</b>: a use site writing the same application resolves to the declaration
     * rather than minting, which is the half of "do not create the derived entry" that reaches field
     * positions.
     *
     * <p>It is not a dedup table across declarations. Two declarations naming one application each own an
     * entry, the second finding the first here only if it resolves later -- and either way the resolver is
     * free to produce two structurally equal entries, which is what two hand-written records with the same
     * fields already are.
     */
    private final Map<String, String> ownedBy = new LinkedHashMap<>();

    /**
     * The entry each minted application denotes, by its application-derived name: the synthetic its closing
     * produced. An application nothing declares is its expansion, so a later sight of it names the same
     * synthetic rather than closing it again.
     */
    private final Map<String, String> expansions = new LinkedHashMap<>();

    /**
     * The same, keyed on §8.2's canonical application, for the applications no entry records: a minted
     * expansion carries no {@code @application}, so the applied-parent fold reads the edge {@code & box<int32>}
     * reaches from here ({@link #expansionsByApplication}).
     */
    private final Map<String, String> expansionsByApplication = new LinkedHashMap<>();

    /**
     * The applications whose closing reached themselves -- the knot tied on the application-derived name while
     * the entry was under construction. Such an expansion names itself, so its content cannot be written without
     * a name, and the application keeps an entry of its own.
     */
    private final Set<String> tied = new LinkedHashSet<>();

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
     * <p><b>It is a bare constant rather than the stated policy</b>, and it is [TSON-SCHEMA] §11.5's
     * materialisation-depth limit at that section's own default. {@code LimitsPolicy} states that limit as
     * {@code maxMaterialisationDepth}, beside the other four §11.5 limits, and this is the one of the five
     * that anything bounds; it does not yet read the policy's value, so a configured depth is reported and
     * not applied here.
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
    private final BiConsumer<String, Top> publish;

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
    private Map<String, Map<String, ParameterTypes.Kind>> parameterKinds = Map.of();

    /**
     * The same question answered one template at a time, for an application closed before the batch pass
     * could run -- a composition supertype or a refinement source, both of which close during resolution's
     * own driving loop. Memoised because a template is typically applied more than once.
     */
    private final Map<String, Map<String, ParameterTypes.Kind>> kindsOnDemand = new LinkedHashMap<>();

    /** Whether a name is the meta-kernel's own, for telling a constructor from a family member. */
    private final Predicate<String> kernel;

    /** The imported entries, where a constructor and the type its slot declares are read from. */
    private final Function<String, Top> metaTypes;

    /** The local declaration resolution is inside, or {@code null} -- who an early check answers to. */
    private final Supplier<String> resolving;

    /**
     * The argument checks {@link #byParameterKind} ran before the parameters were stamped, replayed by
     * {@link #recheckEarly} once they are. An application closed during resolution -- a declaration naming it,
     * a composition operand, a refinement source, or an argument nested in one -- meets a local template whose
     * parameters carry no written or inherited bound yet, so its check passes vacuously until then.
     */
    private final Set<EarlyCheck> early = new LinkedHashSet<>();

    /** Whether {@link #parameterKinds(Map)} has run, after which every check sees stamped parameters. */
    private boolean stamped;

    private record EarlyCheck(String declaration, String head, List<TypeArgument> arguments) {
    }

    TemplateMaterialiser(DefinitionGetter namespace, BiConsumer<String, Top> publish,
            DefinitionMetaReader metaReader, Set<String> generated,
            Function<String, Top> metaTypes, Supplier<String> resolving,
            Map<String, TsonSchema.EntryFacts> facts, Map<String, TypeRef> applications,
            Function<String, Optional<String>> localConstructors, Function<String, Optional<String>> metaConstructors,
            Predicate<String> kernel) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.applications = Objects.requireNonNull(applications, "applications");
        this.localConstructors = Objects.requireNonNull(localConstructors, "localConstructors");
        this.metaConstructors = Objects.requireNonNull(metaConstructors, "metaConstructors");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.metaReader = Objects.requireNonNull(metaReader, "metaReader");
        this.generated = Objects.requireNonNull(generated, "generated");
        this.metaTypes = Objects.requireNonNull(metaTypes, "metaTypes");
        this.resolving = Objects.requireNonNull(resolving, "resolving");
    }

    /**
     * The inferred kinds, once {@code SchemaResolver} has them -- see {@link #parameterKinds}. The parameters
     * are stamped by then, so from here on a check sees them.
     */
    void parameterKinds(Map<String, Map<String, ParameterTypes.Kind>> kinds) {
        this.parameterKinds = kinds;
        this.stamped = true;
    }

    /**
     * Replays every argument check that ran before the parameters were stamped, against the stamped template,
     * reporting a failure against the declaration whose resolution closed the application. One verdict per
     * declaration, as resolution itself gives: {@code boxed<boxed<int32>>} fails on its inner argument, and the
     * outer one -- whose argument is that inner entry -- is not judged a second time.
     */
    void recheckEarly(BiConsumer<String, SchemaValidationException> reporter) {
        Set<String> refused = new LinkedHashSet<>();
        for (EarlyCheck check : early) {
            Top template = namespace.definition(check.head());
            if (refused.contains(check.declaration()) || !OpenEntry.isOpen(template)) {
                continue;
            }
            try {
                classified(check.head(), template, OpenEntry.parameterNames(template), check.arguments());
            } catch (SchemaValidationException e) {
                refused.add(check.declaration());
                reporter.accept(check.declaration(), e);
            }
        }
        early.clear();
    }

    /**
     * Closes every application reachable from {@code entries}, returning the instantiation entries it
     * produced; {@code entries} is rewritten in place, each application replaced by a bare reference.
     *
     * <p>The returned map is separate from {@code entries} because the caller decides where the new entries
     * land -- they are local to this schema and carry no source position, being named by derivation rather
     * than declared.
     */
    Map<String, Top> materialise(Map<String, Top> entries, MaterialisationFailureReporter reporter) {
        TemplateMaterialiser pass = this;
        for (Map.Entry<String, Top> entry : entries.entrySet()) {
            try {
                entry.setValue(MetaRefs.mapRefsKeepingChain(entry.getValue(), pass::close));
            } catch (SchemaValidationException e) {
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
        void reportFailedApplication(String entryName, SchemaValidationException error);
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

    /**
     * The entry a declaration naming a fully-bound application <b>is</b> -- see {@link
     * ApplicationCloser#closeApplicationInto}. Closes the application into {@code declaredName} and hands the
     * definition back for the caller to own: nothing is published under a derived name, and nothing is
     * claimed in {@link #minted}, whose ledger is §8.2's freshness MUST over <em>internal</em> names.
     *
     * <p>Every shape but two closes here. A <b>record</b> template's closure is a record, so {@link
     * #closeHeldRecord} answers directly. Every other held form -- an open instance such as {@code arr
     * => <T> !array { element_type: T }} -- substitutes to a construction the declaration simply <em>is</em>,
     * with no synthetic standing between them. §5.10's partial application
     * and an unresolvable or mis-applied head return {@code null} for the caller to handle.
     */
    Top closeApplicationInto(String declaredName, TypeRef application) {
        String head = application.name();
        Top template = namespace.definition(head);
        if (!OpenEntry.isOpen(template)) {
            return null; // unresolved, or a head taking no parameters -- reported through the ordinary path
        }
        List<String> parameters = OpenEntry.parameterNames(template);
        if (parameters.isEmpty() || parameters.size() != application.arguments().size()) {
            return null; // arity is `instantiate`'s to report, in its own words
        }
        HeldBody open = HeldBody.of(template);
        String constructor = open.application().typeRef().orElseThrow();
        if (ALIAS.equals(constructor)) {
            return null; // §5.10: an alias hop mints no entry, so there is none for a declaration to own
        }
        // §8.2 keys an instantiation on the *canonical* application -- "arguments compared after following
        // alias chains" -- so an argument naming a pure rename is replaced by the entry at the end of it
        // before anything is derived from the list. `close` does this for a minted entry; without it here,
        // `by_alias => box<user_id>` and `by_target => box<uuid>` would record two applications for one type,
        // and the name, the ownership key and the recorded application would each disagree with the minted path.
        List<TypeArgument> classified =
                byParameterKind(head, template, parameters, application.arguments());
        List<TypeArgument> arguments = new ArrayList<>();
        for (TypeArgument argument : classified) {
            arguments.add(argument instanceof TypeArgument.Ref nested
                    ? new TypeArgument.Ref(dereferenced(close(nested.ref())))
                    : argument);
        }
        ownedBy.put(DerivedName.canonicalApplication(head, arguments), declaredName);
        heads.add(head);
        try {
            Map<String, TypeArgument> bindings = bind(parameters, arguments);
            note(declaredName, constructor);
            Top closed = RECORD.equals(constructor) ? closeHeldRecord(head, template, open, bindings)
                    : closeHeld(head, template, open, bindings).body();
            return instantiation(declaredName, closed, new TypeRef(head, arguments));
        } finally {
            heads.remove(heads.size() - 1);
        }
    }

    /**
     * One type-ref with its application closed, or itself when it carries no arguments. Arguments close
     * first, so {@code box<box<text>>} produces the inner entry before the outer one names it.
     *
     * <p>An application this pass cannot close <b>keeps its argument list</b>, rather than collapsing to its
     * bare head. The list is the evidence the author supplied arguments, and the linker reports on what it is
     * handed: a head stripped of its arguments is found as a bare name and then faulted for supplying no
     * arguments, which is the opposite of what the author wrote. Keeping them lets the honest verdict through:
     * a name the schema's namespace does not hold is an unresolved reference, applied or not.
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
     * compares "after alias flattening of both"), so {@code box<user_id>} and {@code box<uuid>} are one
     * type and must be one entry. Without this they were two, which left the model saying the arguments were
     * the same type while the applications were not -- interchangeable at a scalar position and refused one
     * layer of application up.
     *
     * <p><b>An author who wants two boxes told apart has two spellings that say so</b>, and this is what
     * makes them mean something: {@code user_id => !uuid ^ {}} is a refinement, IS-A {@code uuid} and not its
     * siblings, and {@code user_id => !uuid_type {}} is a fresh type related to neither. Both are ordinary
     * entries rather than references, so neither is dereferenced here.
     *
     * <p><b>What this normalises is identity, not provenance.</b> The minted entry is keyed on the canonical
     * application, and the name the author wrote survives where they wrote it -- at the use site, which states
     * it as written. The two facts have one home each.
     *
     * <p><b>Dereferencing loses nothing, and that is a property of the type system rather than luck.</b>
     * Everything that decides how a value at a position is read belongs to the type at the end of the
     * chain, the alphabet a {@code bytes} value is spelled in included: it is a facet of {@code bytes_type}
     * ([TSON-SCHEMA] §5.5), so it travels with the type and a reference cannot carry one of its own.
     */
    private TypeRef dereferenced(TypeRef ref) {
        if (!ref.arguments().isEmpty()) {
            return ref; // an application, already closed above to the entry it denotes
        }
        String terminal = AliasChain.terminal(ref.name(), namespace::definition);
        return terminal.equals(ref.name()) ? ref : new TypeRef(terminal, ref.arguments(), ref.annotations());
    }

    /**
     * The entry name a fully-bound application denotes, creating the entry on first sight, or {@code null}
     * when the head names nothing in scope -- that is {@code TsonSchemaLinker}'s verdict to give as an
     * unresolved reference, not this pass's to guess at.
     */
    private String instantiate(String head, List<TypeArgument> arguments) {
        Top template = namespace.definition(head);
        if (template == null) {
            return null; // unresolved head -- the linker's verdict, not this pass's
        }
        List<String> parameters = OpenEntry.parameterNames(template);
        if (parameters.isEmpty()) {
            throw new SchemaValidationException("'" + head + "' declares no type parameters, so '"
                    + head + "<...>' applies arguments to something that takes none (§5.10); drop the "
                    + "argument list");
        }
        if (parameters.size() != arguments.size()) {
            throw new SchemaValidationException("'" + head + "' takes " + parameters.size()
                    + " type argument" + (parameters.size() == 1 ? "" : "s") + " " + parameters + ", but "
                    + arguments.size() + " " + (arguments.size() == 1 ? "was" : "were") + " applied (§5.10)");
        }
        arguments = byParameterKind(head, template, parameters, arguments);
        String owner = ownedBy.get(DerivedName.canonicalApplication(head, arguments));
        if (owner != null) {
            // A declaration owns this application's entry (§8.2), so there is nothing
            // to mint: a recursive reference inside its own closure ties the knot on the declared name, and a
            // use site writing the same application names the declaration instead of a derived entry.
            return owner;
        }
        String name = DerivedName.ofApplication(head, arguments);
        String expansion = expansions.get(name);
        if (expansion != null) {
            return expansion;
        }
        if (aliasClosing.contains(name)) {
            throw new SchemaValidationException("'" + head + "<...>' is an alias template whose own "
                    + "body applies it again, so composing it never reaches a type with a body (§5.10). The "
                    + "chain begins " + chain() + ". An alias template must eventually name a declared "
                    + "type; recursion belongs in a record, tuple or choice body, where a field can carry it");
        }
        if (materialised.containsKey(name) || !closing.add(name)) {
            // Already built, or under construction -- the knot-tying case. Either way it must be *this*
            // application, not another that derived the same name.
            if (!materialised.containsKey(name)) {
                tied.add(name);
            }
            minted.claim(name, DerivedName.canonicalApplication(head, arguments));
            return name;
        }
        minted.claim(name, DerivedName.canonicalApplication(head, arguments));
        if (closing.size() > MAX_CLOSING_DEPTH) {
            closing.remove(name);
            // Named for the *outermost* head, which is the one the author wrote; the head in hand here is
            // whichever link happened to tip the depth over.
            throw new SchemaValidationException("'" + heads.get(0) + "<...>' does not close: "
                    + "materialising it needs more than " + MAX_CLOSING_DEPTH + " nested instantiations and "
                    + "each one differs from the last, so the arguments are growing rather than repeating and "
                    + "there is no finite set of types to build (§5.10). The chain begins " + chain()
                    + ". A recursive template must reach an argument it has already been applied to");
        }
        heads.add(head);
        try {
            // Every shape reaches here, so every shape gets the memo, the depth backstop and one publish
            // path. A shape that short-circuited ahead of all three would leave a template applying itself
            // (`weird => <T> [weird<T>]`) recursing to a StackOverflowError instead of tying the knot.
            // §5.10's partial application mints nothing at all: the alias *is* the application it names
            // with some arguments still open, so closing it composes the two argument lists and hands back
            // whatever that denotes -- `uuid_pair<int32>` is the entry `pair<text, int32>` already produced.
            // "No intermediate entry per alias hop" is the rule, which is why this returns a name rather
            // than making one, and why it is the head that tells the three cases apart rather than the body.
            // The held body is parsed once here and reused by all three branches: this is the only place a
            // closure needs the tree, and the parse is thrown away with it.
            if (OpenEntry.isOpen(template)) {
                HeldBody open = HeldBody.of(template);
                String constructor = open.application().typeRef().orElseThrow();
                if (ALIAS.equals(constructor)) {
                    aliasClosing.add(name);
                    return closeHeldAlias(head, template, open, bind(parameters, arguments));
                }
                // A record template's closure is a record, named for its content like every other closed form
                // unless it names its own application -- see closeHeldRecord and mintedRecord.
                if (RECORD.equals(constructor)) {
                    Top closed = closeHeldRecord(head, template, open, bind(parameters, arguments));
                    refuseMintedMember(head, arguments, closed);
                    if (!tied.contains(name)) {
                        return mintedRecord(name, DerivedName.canonicalApplication(head, arguments), closed);
                    }
                    note(name, RECORD);
                    Top instantiation = instantiation(name, closed, new TypeRef(head, arguments));
                    materialised.put(name, instantiation);
                    publish.accept(name, instantiation);
                    return name;
                }
                String formName = closeHeldTemplate(head, template, open, bind(parameters, arguments));
                if (generated.contains(head)) {
                    // A generated head closing its own intermediate form: the form entry *is* the answer, and
                    // an instantiation naming this head would carry an internal name into identity.
                    return formName;
                }
                if (!tied.contains(name)) {
                    // An application nothing declares is its expansion: the use site names the synthetic.
                    expansions.put(name, formName);
                    return formName;
                }
                // The expansion names this application, so it is named by the application: a reference to
                // the synthetic, which the compiler collapses, so it costs nothing at read time.
                Top alias = new Alias(TypeRef.of(formName));
                note(name, ALIAS);
                alias = instantiation(name, alias, new TypeRef(head, arguments));
                materialised.put(name, alias);
                publish.accept(name, alias);
                return name;
            }
            // Every open entry has a held tree (OpenEntry, HeldBody.of) -- a record template, a sugar form's
            // lift, an alias, and an error placeholder alike. The three branches above are the whole of
            // §12.1's open form, told apart by the head they carry rather than by which kernel shape holds the
            // entry. So this is a broken invariant, not an author error and not a gap.
            throw new IllegalStateException("'" + head + "' declares type parameters but its body is a "
                    + template.getClass().getSimpleName() + " -- every open entry has a held tree, and "
                    + "nothing else can be substituted into");
        } finally {
            closing.remove(name);
            aliasClosing.remove(name);
            heads.remove(heads.size() - 1);
        }
    }

    /**
     * A minted record application as its expansion: a closed synthetic record, named by its binding record as
     * every closed form is, so two applications closing to one record -- {@code box<int32>} and {@code
     * crate<int32>} over two templates with one field list -- are one entry. Built once; a later application
     * with the same content names the entry already there.
     */
    private String mintedRecord(String name, String canonical, Top closed) {
        List<RecordValue.Field> fields = closed instanceof RecordBody record
                && WireForm.heldRecord(record, HeldBody::annotationValue).coreValue() instanceof RecordValue wire
                ? wire.fields() : List.of();
        String formName = DerivedName.ofBinding(RECORD, fields);
        minted.claim(formName, DerivedName.canonicalBinding(RECORD, fields));
        expansions.put(name, formName);
        expansionsByApplication.putIfAbsent(canonical, formName);
        if (namespace.definition(formName) == null) {
            note(formName, RECORD);
            materialised.put(formName, closed);
            synthetics.add(formName);
            publish.accept(formName, closed);
        }
        return formName;
    }

    /**
     * A minted record whose chain names a declared type is a family member with no declared name (§3.3.4,
     * §8.2): a read reports the member it selects, a tag names it and a binding maps it, all by a name an
     * application at a use site does not have. Its chain names one through a supertype, or through a parent
     * application a declaration owns ({@code & result<text>} beside {@code rt => result<text>}), which is the
     * edge {@code SchemaResolver}'s fold would otherwise add. Refused here, where the application is in hand to
     * name, against the declaration that wrote it. A constructor -- a record IS-A the kernel's {@code top} -- is
     * no member of anything.
     */
    private void refuseMintedMember(String head, List<TypeArgument> arguments, Top closed) {
        if (!(closed instanceof RecordBody record) || EntryKind.isConstructor(head, record, kernel)) {
            return;
        }
        List<String> onto = new ArrayList<>(record.supertypes());
        record.parents().stream().filter(parent -> !parent.arguments().isEmpty())
                .map(parent -> ownedBy.get(DerivedName.canonicalApplication(parent.name(), parent.arguments())))
                .filter(Objects::nonNull).forEach(onto::add);
        if (onto.isEmpty()) {
            return;
        }
        String written = head + arguments.stream().map(argument -> switch (argument) {
            case TypeArgument.Ref ref -> ref.ref().name();
            case TypeArgument.Value value -> value.value().text();
        }).collect(java.util.stream.Collectors.joining(", ", "<", ">"));
        String base = onto.stream()
                .filter(member -> onto.stream().noneMatch(other -> !other.equals(member)
                        && TypeChain.supertypesOf(namespace.definition(other)).contains(member)))
                .findFirst().orElse(onto.getFirst());
        throw new SchemaValidationException("'" + written + "' composes onto '" + base + "', so it is a member of "
                + "that family, and a member is declared: a read reports the member it selects, a tag names it and "
                + "a binding maps it, all by a name an application at a use site does not have (§3.3.4, §8.2). "
                + "Declare it -- 'my_name => " + written + "' -- and use that name in place of the application");
    }

    /**
     * The minted record each canonical application closed to, for {@code SchemaResolver}'s applied-parent fold:
     * an application at a composition operand reaches the entry a use site's application of it denotes.
     */
    Map<String, String> expansionsByApplication() {
        return Map.copyOf(expansionsByApplication);
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

    // ── Closing an open instance (§5.10) ─────────────────────────────────────────────────────────

    /**
     * The entry an application of an <b>open instance</b> denotes -- a template whose held body is a
     * constructor application rather than a record. Substituting turns its bindings concrete, and the result
     * is no longer a template at all: it is the constructor body those bindings always described, so it is
     * bound through that constructor's own reader and the entry carries an ordinary body.
     *
     * <p><b>The closure is a closed <em>synthetic</em></b>, named for the form and built by its constructor
     * (§8.2), so {@code [text]} written directly and {@code [T]} closed to {@code text} are one entry. An
     * application nothing declares is that expansion, and the use site names the synthetic. The one exception
     * is an expansion that names its own application -- {@code nested => <T> ( T | [nested<T>] )} -- whose
     * content cannot be written without a name: {@link #instantiate} then keeps an alias entry under the
     * application-derived name, which the knot was tied on.
     */
    private String closeHeldTemplate(String head, Top template, HeldBody open,
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
        if (namespace.definition(formName) != null) {
            return formName; // already built, here or by the desugar phase -- one entry per form, schema-wide
        }
        Top definition = closed.body();
        note(formName, target);
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
     * so its name is the answer. §5.10 is explicit that an alias hop mints no intermediate entry.
     *
     * <p><b>The knot-tying memo cannot serve this path</b>, which is why {@link #aliasClosing} exists. That
     * memo answers a recursive application with the name of the entry under construction, and this
     * constructs none -- so a self-applying alias ({@code loop => <B> loop<B>}) would be handed a name
     * nothing ever defines. {@link #close} checks {@code aliasClosing} before reaching here and reports the
     * cycle instead.
     */
    private String closeHeldAlias(String head, Top template, HeldBody open,
            Map<String, TypeArgument> bindings) {
        CoreValue substituted = WireForm.substitute(open.application().coreValue(), head,
                OpenEntry.parameterNames(template), bindings);
        CoreValue closed = closeApplications(substituted);
        CoreValue target = closed instanceof RecordValue record ? WireForm.field(record, TARGET).orElse(null) : null;
        if (!(target instanceof TokenValue token)) {
            throw new IllegalStateException("'" + head + "<...>' is an alias whose target did not close to a "
                    + "name: " + target + " -- SchemaDesugarer writes `!alias { target: <reference> }` and "
                    + "closeApplications reduces an application there to the entry it denotes");
        }
        return token.text();
    }

    /**
     * A <b>record</b> template's closure: the record body itself, which a declaration owns under its name and
     * which {@link #mintedRecord} names for its content where nothing declares it.
     *
     * <p><b>It is minted with no subtypes, like every resolved entry.</b> {@code subtypes} is linking's,
     * derived one phase later over the closed namespace ({@code TsonSchemaLinker}), which is the invariant
     * {@code SchemaResolver}'s own merge rests on. Taking the template's list instead would hand each
     * instantiation the whole family -- {@code box<text>} claiming {@code box<int32>} -- and, where the
     * template arrived already linked through an {@code !!import}, itself. That last is what makes it
     * load-bearing rather than untidy: §8.2 names a minted entry by a function of its resolved form alone,
     * so two schemas closing one application mint one entry and §2.2.3 unifies them. An entry shaped by
     * *which* schema closed it is not a function of the form, and the unification fails as a name collision.
     */
    private Top closeHeldRecord(String head, Top template, HeldBody open, Map<String, TypeArgument> bindings) {
        Closed closed = closeHeld(head, template, open, bindings);
        Top body = asMember(closed.body());
        // A held body's chain is the parents as written, so closing it is where the chain is completed
        // (Ancestry). A parent applied to the template's own parameter stays in `parents` beside its template's
        // ancestors, for SchemaResolver's fold to turn into the edge to the entry the closed application
        // denotes -- `ok<text>` IS-A `result<text>` and not `result<int32>`, whatever order the declarations
        // were written in. A declaration carrying a removal wrote no parent, its chain being empty (§5.9).
        return body instanceof RecordBody record
                ? record.withChain(Ancestry.chainOf(record.parents(), namespace)) : body;
    }

    /** Records the constructor {@code name}'s value applies -- the tag resolved output writes. */
    private void note(String name, String constructor) {
        TsonSchema.EntryFacts known = facts.getOrDefault(name, TsonSchema.EntryFacts.NONE);
        facts.put(name, known.withConstructor(Optional.of(constructor)));
    }

    /**
     * {@code value} as the instantiation of {@code application} (§8.2), whatever kind of entry it is: a record
     * template's member, a namespace entry, the hop to the synthetic a non-record template closes to. Recorded in
     * {@link #applications}, which {@code SchemaResolver} writes onto the entry's key as {@code @application}.
     */
    private Top instantiation(String name, Top value, TypeRef application) {
        applications.put(name, application);
        return value;
    }

    /** A held body substituted, its inner applications closed, and read back through its constructor. */
    private Closed closeHeld(String head, Top template, HeldBody open,
            Map<String, TypeArgument> bindings) {
        String target = open.application().typeRef().orElseThrow();
        // One walk covers all three cases: a parameter in a slot, a parameter inside an application a slot
        // holds (`tree<p0>` becoming `tree<text>`), and a parameter inside a collection are all the same
        // thing here -- a token in a tree -- because the body was never read against the constructor's
        // vocabulary in the first place.
        CoreValue substituted = WireForm.substitute(open.application().coreValue(), head,
                OpenEntry.parameterNames(template), bindings);
        CoreValue wire = closeApplicationsOutsideSupertypes(substituted);
        try {
            return new Closed(wire, metaReader.read(target, new DataValue(List.of(), Optional.of(target), wire)));
        } catch (ReadException e) {
            // The bindings a template defers are checked here and nowhere else (§8.2): `<T, N> [T; N]` is a
            // fine declaration, and `vector<text, "two">` is where it stops being one.
            throw DefinitionResolver.payloadFailure("'" + head + "<...>' substitutes into a body that",
                    "'" + head + "<...>' substitutes into a body that is not valid data for '" + target
                            + "', the constructor's own constraint vocabulary -- " + e.getMessage(), e);
        }
    }

    /**
     * The held body's applications closed -- <b>except the ones standing in its chain's {@code parents}</b>,
     * which are left exactly as written.
     *
     * <p>A composition operand denotes no entry (§8.2, and {@code
     * DefinitionResolver}'s own supertype branch): composition needs the operand's <em>fields</em>, which
     * substitution has already produced, and nothing else. Closing it here would mint the entry that branch
     * exists to avoid -- {@code vip => <T> customer & box<T>} closed at {@code vip<text>} would produce a
     * {@code box<text>} nothing named, beside the {@code text_box} a declaration may well have named.
     *
     * <p><b>The resolved-form walk exempts this channel too</b> ({@code
     * MetaRefs.mapRefsKeepingChain}), and the two walks must agree: the operand is kept as written,
     * and resolving it to the entry it denotes belongs to {@code SchemaResolver}'s fold, which runs after every
     * declaration and so cannot make the answer depend on declaration order.
     */
    private CoreValue closeApplicationsOutsideSupertypes(CoreValue value) {
        if (!(value instanceof RecordValue record)) {
            return closeApplications(value);
        }
        return new RecordValue(record.fields().stream()
                .map(field -> WireForm.CHAIN.equals(field.name()) ? field
                        : new RecordValue.Field(field.name(), WireForm.rewritten(field.value(),
                                closeApplications(field.value().coreValue()))))
                .toList());
    }

    /** A closed held body, and the wire form it was read from -- the one an entry name derives from. */
    private record Closed(CoreValue wire, Top body) {
    }

    /**
     * A closed record template as its member: <b>where a family's selectors stop.</b> Which fields a family
     * dispatches <em>on</em> is the base's statement ({@code record.discriminators}), and whoever pins them is a
     * member and carries the values instead. The template's list is the base's; the application closing it has
     * pinned them, substitution having put each argument in the {@code value} of a field already FIXED where it
     * was written (§5.7), so the member names none.
     */
    private static Top asMember(Top body) {
        if (!(body instanceof RecordBody record)) {
            return body;
        }
        return new RecordBody(record.chain(), record.fields(), record.groups(), closedExtension(record),
                java.util.List.of(), record.nameType());
    }

    /**
     * A closed member's own {@code extension}: the template's, except that <b>SEALED does not travel</b>.
     *
     * <p>ABSTRACT is a claim about the marked type alone and holds of every instantiation identically, which
     * is how {@code abstract} on a template reaches them all (#504, and {@code AbstractTemplateFamilyTest}
     * pins it). <b>Dispatching on members does not travel.</b> Closing has pinned those fields and left the
     * member naming no selectors of its own, the selectors belonging to the base (§5.2) -- so a member of a
     * sealed family is an ordinary
     * concrete record, exactly as in a hand-written family, and a base that dispatches on members closes to
     * OPEN rather than carrying its abstractness into every one of them.
     */
    private static RecordExtensionType closedExtension(RecordBody record) {
        return record.extension() == RecordExtensionType.ABSTRACT && !record.discriminators().isEmpty()
                ? RecordExtensionType.OPEN : record.extension();
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
     * Every application still written in a slot ({@link WireForm#applicationIn}), closed to a bare reference to the
     * entry it denotes -- the inverse of the shape {@code SchemaDesugarer} writes when a slot holds one.
     *
     * <p>It runs on the wire value rather than on the body read from it because the <em>name</em> depends on
     * it: {@code DerivedName.ofBinding} reads the slots as written, and the desugar phase expands
     * innermost-first, so by the time it names an outer form the inner one is already a bare name. Closing
     * here in the same order is what makes the two phases agree on what a form is called.
     */
    private CoreValue closeApplications(CoreValue value) {
        return switch (value) {
            case TokenValue token when WireForm.applicationIn(token).isPresent() ->
                    new TokenValue(close(WireForm.applicationIn(token).get()).name(), TokenForm.UNQUOTED);
            case RecordValue record -> new RecordValue(record.fields().stream()
                    .map(field -> new RecordValue.Field(field.name(),
                            WireForm.rewritten(field.value(), closeApplications(field.value().coreValue()))))
                    .toList());
            case ArrayValue array -> new ArrayValue(array.elements().stream()
                    .map(element -> WireForm.rewritten(element, closeApplications(element.coreValue()))).toList());
            // A map slot holds applications like any other: `{ S => [box<text>] }` writes one in the array
            // its value names, and a key type could take one too. Both halves descend, as they do in
            // WireForm.substitute, which walks the same shape one step earlier.
            case MapValue map -> new MapValue(map.entries().stream()
                    .map(entry -> new MapValue.MapEntry(
                            WireForm.retyped(entry.key(), closeApplications(entry.key().coreValue())),
                            WireForm.rewritten(entry.value(), closeApplications(entry.value().coreValue()))))
                    .toList());
            default -> value;
        };
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
     *
     * <p><b>Then each argument is checked against the parameter it binds</b> ({@link #checkArguments}), which is
     * what makes a wrong argument a verdict at the application rather than inside the substituted body.
     *
     * <p><b>Shared with the operand path</b>, through {@link ApplicationCloser#byParameterKind}: a
     * composition operand absorbs by value and mints nothing, so it never reaches this pass and would
     * otherwise keep §12.1's token-shape reading of every argument.
     */
    List<TypeArgument> byParameterKind(String head, Top template,
                                                List<String> parameters, List<TypeArgument> arguments) {
        List<TypeArgument> classified = classified(head, template, parameters, arguments);
        String declaration = resolving.get();
        if (!stamped && declaration != null) {
            early.add(new EarlyCheck(declaration, head, List.copyOf(arguments)));
        }
        return classified;
    }

    private List<TypeArgument> classified(String head, Top template,
                                          List<String> parameters, List<TypeArgument> arguments) {
        Map<String, ParameterTypes.Kind> kinds = parameterKinds.get(head);
        if (kinds == null) {
            kinds = kindsOnDemand.computeIfAbsent(head, ignored -> ParameterTypes.inferOne(template, metaTypes));
        }
        if (kinds == null || kinds.isEmpty()) {
            checkArguments(head, template, arguments);
            return arguments;
        }
        List<TypeArgument> bound = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            bound.add(arguments.get(i) instanceof TypeArgument.Ref ref && ref.ref().arguments().isEmpty()
                    && kinds.get(parameters.get(i)) == ParameterTypes.Kind.VALUE
                            ? new TypeArgument.Value(new Token(ref.ref().name(), Token.Form.UNQUOTED))
                            : arguments.get(i));
        }
        checkArguments(head, template, bound);
        return bound;
    }

    /**
     * Each argument against the parameter it binds ({@code template_param}, [TSON-SCHEMA] §5.10): a type argument
     * must name a type that IS-A the parameter's {@code bound}, and a value argument must be a value of the
     * parameter's {@code type} -- or of the type an earlier parameter's argument names, where the type is that
     * parameter.
     *
     * <p>An argument this cannot judge is left for the position to refuse after substitution, as every argument
     * was before: an application as an argument (its entry is not closed yet), a name nothing declares (the
     * linker's verdict), and a parameter whose type has no scalar reading.
     */
    private void checkArguments(String head, Top template, List<TypeArgument> arguments) {
        List<TemplateParam> parameters = OpenEntry.parameters(template);
        if (!OpenEntry.isOpen(template) || parameters.size() != arguments.size()) {
            return;
        }
        for (int i = 0; i < arguments.size(); i++) {
            TemplateParam parameter = parameters.get(i);
            TypeArgument argument = arguments.get(i);
            if (parameter.isTypeParameter()) {
                if (parameter.bound().isPresent() && argument instanceof TypeArgument.Ref ref
                        && ref.ref().arguments().isEmpty()) {
                    checkBound(head, parameter, ref.ref().name(), parameter.bound().get().name());
                }
            } else if (argument instanceof TypeArgument.Value value) {
                checkValue(head, parameter, value.value().text(), valueType(parameters, arguments, parameter),
                        ParameterTypes.readsInStructure(template, namespace::definition));
            }
        }
    }

    private void checkBound(String head, TemplateParam parameter, String argument, String bound) {
        String terminal = AliasChain.terminal(argument, this::lookup);
        Top target = lookup(terminal);
        if (target == null) {
            return; // an unresolved argument -- the linker's verdict
        }
        // By name, so a core type and the kernel original it copies are one bound, as they are at the declaration.
        String boundTerminal = AliasChain.terminal(bound, this::lookup);
        List<String> chain = TypeChain.supertypesOf(target);
        boolean admitted = terminal.equals(boundTerminal) || chain.contains(boundTerminal) || chain.contains(bound);
        if (!admitted) {
            throw new SchemaValidationException("'" + head + "<...>' binds '" + parameter.name() + "' to '"
                    + argument + "', which is not a type that IS-A " + bound + " -- '" + head + "' declares '"
                    + parameter.name() + ": " + bound + "' (§5.10)");
        }
    }

    /** The type a value parameter's argument is read as, following a type that names an earlier parameter. */
    private static String valueType(List<TemplateParam> parameters, List<TypeArgument> arguments,
                                    TemplateParam parameter) {
        String type = parameter.type().name();
        for (int i = 0; i < parameters.size(); i++) {
            if (parameters.get(i).name().equals(type)) {
                return arguments.get(i) instanceof TypeArgument.Ref ref && ref.ref().arguments().isEmpty()
                        ? ref.ref().name() : null;
            }
        }
        return type;
    }

    /**
     * {@code argument} as a value of {@code type}, read among the imported entries where the template applies a
     * constructor ({@link ParameterTypes#readsInStructure}) -- the constructor's reader reads the argument, and its
     * slot's type is in its declaring schema's closure -- and in the schema's namespace otherwise.
     */
    private void checkValue(String head, TemplateParam parameter, String argument, String type, boolean structural) {
        if (type == null) {
            return;
        }
        Function<String, Top> resolve = structural ? metaTypes : this::lookup;
        String terminal = AliasChain.terminal(type, resolve::apply);
        Top definition = resolve.apply(terminal);
        // An enum's argument is matched in its name type's form, which an import may hold.
        Optional<AtomType<?>> parser = definition == null ? Optional.empty() : AtomParsers.forType(definition,
                NameType.form(definition, (structural ? metaConstructors : localConstructors).apply(terminal),
                        namespace::definition, metaTypes));
        if (parser.isEmpty()) {
            return; // no scalar reading -- the substituted body's own position judges it
        }
        try {
            parser.get().read(argument);
        } catch (AtomTypeException e) {
            throw new SchemaValidationException("'" + head + "<...>' binds '" + parameter.name() + "' to '"
                    + argument + "', which is not a value of " + type + ": " + e.getMessage() + " (§5.10)");
        }
    }

    /** A name in the schema's own namespace first, then among its imported entries. */
    private Top lookup(String name) {
        Top local = namespace.definition(name);
        return local != null ? local : metaTypes.apply(name);
    }

}
