package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ReferenceValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Param;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.SiblingRef;

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
 * <p><b>Two operations share the spelling</b> ([TSON-SCHEMA] §5.10, §5.3). Over an open record, {@code result_of<text>}
 * substitutes: the record's parameters are replaced by the arguments on the model, each field's application closed in
 * turn, and the result is a closed record ({@link #closeOpenRecord}). Over a constructor, {@code meta.array<text>}
 * constructs: the binding record {@code [text]} writes, built by the constructor's own reader ({@link #construction}).
 * A reference held inside an open record, {@code meta.array<T>} or {@code box<T>}, closes the same way once the record
 * holding it is applied and its parameter substituted. <b>An application nothing declares is its expansion</b> (§5.10):
 * the closure is a closed <em>synthetic</em> named for its binding record ({@link #mintedRecord}), so two applications
 * closing to one form are one entry. A declaration naming an application is that entry under its own name ({@link
 * #closeApplicationInto}).
 *
 * <p><b>Knot-tying, and the one instantiation entry.</b> The memo entry is registered <em>before</em> the body
 * is substituted, under a name derived from the application ({@link DerivedName#ofApplication}), so a
 * recursive application reached during substitution ({@code tree<T>} inside {@code tree}, which becomes
 * {@code tree<text>} once {@code T} is bound) finds the entry under construction and references it by name
 * rather than recursing forever. Such an expansion names itself, so its content cannot be written without a
 * name: it keeps the instantiation entry the knot was tied on, the record itself, keyed on the application and
 * recording it ({@code @meta.application}, §8.2).
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


    /** The constructor an open record's applications are built with. */
    private static final String RECORD = KernelNames.of("record");

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

    /** §8.2's freshness MUST over the names this class mints -- see {@link MintedNames}. */
    private final MintedNames minted = new MintedNames();

    /**
     * Which of {@link #materialised} are <b>synthetic</b> entries rather than instantiation entries -- the
     * closed forms {@link #construction} and {@link #mintedRecord} mint, which are indistinguishable from the entry a
     * directly-written {@code [pixel]} lifts to and are the same entry when both appear (§8.2).
     *
     * <p>§8.2 marks only these: an instantiation entry carries no {@code @meta.synthetic}, it being the closure of
     * an application where a synthetic is a constructor's instance. The caller reads this set to put the
     * marker on the right keys.
     */
    private final Set<String> synthetics = new LinkedHashSet<>();

    /** Applications currently being closed, for the knot-tying memo and the termination guard's chain. */
    private final Set<String> closing = new LinkedHashSet<>();

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
     * expansion carries no {@code @meta.application}, so the applied-parent fold reads the edge {@code & box<int32>}
     * reaches from here ({@link #expansionsByApplication}).
     */
    private final Map<String, String> expansionsByApplication = new LinkedHashMap<>();

    /**
     * Every application this pass closed, as it was met, to the entry it closed to -- an expansion's included, which
     * records no {@code @meta.application}. Held beside the entries, never written: it is what a message names an
     * expansion by, a form no author named ({@link #closedApplications}).
     */
    private final Map<TypeRef, String> closedApplications = new LinkedHashMap<>();

    /**
     * Each instantiation entry's application as it was written, its nested arguments unreduced ({@code foo<bar<text>>}
     * where {@link #applications} holds {@code foo<bar_text_…>}): what {@code @meta.application} states on the entry's key,
     * so a reader and a message see the reference the author wrote.
     */
    private final Map<String, TypeRef> writtenApplications = new LinkedHashMap<>();

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
     * How a construction becomes an ordinary constructor body -- the constructor's own compiled reader, the same one
     * a written {@code !meta.array { ... }} binds through. Using it is what makes an argument the slot refuses an
     * ordinary read error rather than a check this class would have to grow.
     */
    private final ConstructionReader constructionReader;

    /** Whether a name is the kernel's own, for telling a constructor from a family member. */
    private final Predicate<String> kernel;

    /** The imported entries, where an argument's or a bound's name is read when the schema does not hold it. */
    private final Function<String, Top> importedTypes;

    /** The local declaration being made ready, or {@code null} once every one is -- who a deferred check answers to. */
    private final Supplier<String> resolving;

    /**
     * The argument checks {@link #checkArguments} deferred while declarations were being made ready, run by
     * {@link #checkDeferred} once every one is. An application closed during resolution -- a declaration naming
     * it, a composition operand, a refinement source, or an argument nested in one -- is judged by its arguments'
     * chains, and an argument is a name at a type position, which orders nothing ([TSON-SCHEMA] §3.4.1): judging it
     * then would make the declaration wait on its argument.
     */
    private final Set<DeferredCheck> deferred = new LinkedHashSet<>();

    private record DeferredCheck(String declaration, String head, List<TypeRef> arguments) {
    }

    TemplateMaterialiser(DefinitionGetter namespace, BiConsumer<String, Top> publish,
                         ConstructionReader constructionReader,
                         Function<String, Top> importedTypes, Supplier<String> resolving,
                         Map<String, TsonSchema.EntryFacts> facts, Map<String, TypeRef> applications,
                         Predicate<String> kernel) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.applications = Objects.requireNonNull(applications, "applications");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.constructionReader = Objects.requireNonNull(constructionReader, "constructionReader");
        this.importedTypes = Objects.requireNonNull(importedTypes, "importedTypes");
        this.resolving = Objects.requireNonNull(resolving, "resolving");
    }

    /**
     * Runs every argument check deferred while declarations were being made ready, reporting a failure against the
     * declaration whose resolution closed the application. One verdict per declaration, as resolution itself gives:
     * {@code boxed<boxed<int32>>} fails on its inner argument, and the outer one -- whose argument is that inner
     * entry -- is not judged a second time.
     */
    void checkDeferred(BiConsumer<String, SchemaValidationException> reporter) {
        Set<String> refused = new LinkedHashSet<>();
        for (DeferredCheck check : deferred) {
            Top template = namespace.definition(check.head());
            if (refused.contains(check.declaration()) || !OpenEntry.isOpen(template)) {
                continue;
            }
            try {
                checkBounds(check.head(), template, check.arguments());
            } catch (SchemaValidationException e) {
                refused.add(check.declaration());
                reporter.accept(check.declaration(), e);
            }
        }
        deferred.clear();
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
     * derived {@code @meta.synthetic} marker (§8.2). Everything else it returned is an instantiation entry, which
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
     * <p>An open record's closure is a record ({@link #closeOpenRecord}). An unresolvable or mis-applied head returns
     * {@code null} for the caller to handle.
     */
    Top closeApplicationInto(String declaredName, TypeRef application) {
        String head = templateHead(application.name());
        Top template = namespace.definition(head);
        if (!OpenEntry.isOpen(template)) {
            return null; // unresolved, or a head taking no parameters -- reported through the ordinary path
        }
        List<String> parameters = OpenEntry.parameterNames(template);
        if (parameters.size() != application.arguments().size()) {
            return null; // arity is `instantiate`'s to report, in its own words
        }
        // §8.2 keys an instantiation on the *canonical* application -- "arguments compared after following
        // alias chains" -- so an argument naming a pure rename is replaced by the entry at the end of it
        // before anything is derived from the list. `close` does this for a minted entry; without it here,
        // `by_alias => box<user_id>` and `by_target => box<uuid>` would record two applications for one type,
        // and the name, the ownership key and the recorded application would each disagree with the minted path.
        checkArguments(head, template, application.arguments());
        List<TypeRef> arguments = new ArrayList<>();
        for (TypeRef argument : application.arguments()) {
            arguments.add(dereferenced(close(argument)));
        }
        ownedBy.put(DerivedName.canonicalApplication(head, arguments), declaredName);
        heads.add(head);
        try {
            note(declaredName, RECORD);
            Top closed = closeOpenRecord((RecordBody) template, bind(parameters, arguments));
            closedApplications.putIfAbsent(plain(application), declaredName);
            return instantiation(declaredName, closed, TypeRef.of(head, arguments), application);
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
        String constructed = construction(ref);
        if (constructed != null) {
            return TypeRef.of(constructed);
        }
        List<TypeRef> arguments = new ArrayList<>();
        for (TypeRef argument : ref.arguments()) {
            arguments.add(dereferenced(close(argument)));
        }
        String entry = instantiate(templateHead(ref.name()), arguments, ref);
        if (entry != null) {
            closedApplications.putIfAbsent(plain(ref), entry);
        }
        return entry == null ? TypeRef.of(ref.name(), arguments, ref.annotations()) : TypeRef.of(entry);
    }

    /**
     * The entry an application over a <b>constructor</b> denotes, building it on first sight, or {@code null} where
     * the head, past its alias hops, is no constructor ({@link ConstructionSugar}). {@code meta.array<text>} is the
     * construction {@code !meta.array { element_type: text }}, so it is the closed synthetic {@code [text]} lifts
     * to: one binding record, named by its content ({@link DerivedName#ofBinding}) and built by the constructor's
     * own reader, the arguments as written and each application among them closed first. Identity is the
     * constructor's, so an application through an alias of it names the same entry.
     *
     * <p><b>No {@code @meta.application}, no instantiation entry, no ownership.</b> The value states its
     * constructor and its arguments, and nothing is substituted, so there is nothing to record beside it; and a use
     * site names the content-keyed entry whatever a declaration elsewhere constructs, as {@code [text]} does.
     */
    private String construction(TypeRef application) {
        String constructor = AliasChain.terminal(application.name(), this::lookup);
        Top definition = lookup(constructor);
        if (!ConstructionSugar.constructs(constructor, definition)) {
            return null;
        }
        CoreValue wire = closeApplications(ConstructionSugar.payload(application, (RecordBody) definition,
                this::lookup));
        List<RecordValue.Field> fields = wire instanceof RecordValue record ? record.fields() : List.of();
        String formName = DerivedName.ofBinding(constructor, fields);
        minted.claim(formName, DerivedName.canonicalBinding(constructor, fields));
        if (namespace.definition(formName) == null) {
            Top body;
            try {
                body = constructionReader.read(constructor, new DataValue(List.of(), Optional.of(constructor), wire));
            } catch (ReadException e) {
                throw DefinitionResolver.payloadFailure("'" + application.text() + "' constructs a body that",
                        "'" + application.text() + "' constructs '!" + constructor + " { ... }', which is not valid "
                                + "data for '" + constructor + "' -- " + e.getMessage(), e);
            }
            note(formName, constructor);
            materialised.put(formName, body);
            synthetics.add(formName);
            publish.accept(formName, body);
        }
        closedApplications.putIfAbsent(plain(application), formName);
        return formName;
    }

    /**
     * The template an application's head names: the head itself, or, where it is an alias of a template ({@code
     * ref_of => ref}), the template at the end of its alias chain, the alias being the same entry under
     * another name ([TSON-SCHEMA] §8.3). Identity is the template's, so {@code ref_of<order>} and {@code
     * meta.ref<order>} close to one entry.
     */
    private String templateHead(String head) {
        String terminal = AliasChain.terminal(head, namespace::definition);
        return !terminal.equals(head) && OpenEntry.isOpen(namespace.definition(terminal)) ? terminal : head;
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
     * siblings, and {@code user_id => !net.uuid_type {}} is a fresh type related to neither. Both are ordinary
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
        return terminal.equals(ref.name()) ? ref : TypeRef.of(terminal, ref.arguments(), ref.annotations());
    }

    /**
     * The entry name a fully-bound application denotes, creating the entry on first sight, or {@code null}
     * when the head names nothing in scope -- that is {@code TsonSchemaLinker}'s verdict to give as an
     * unresolved reference, not this pass's to guess at.
     */
    private String instantiate(String head, List<TypeRef> arguments, TypeRef written) {
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
        checkArguments(head, template, arguments);
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
            // The memo, the depth backstop and one publish path: a template applying itself (`tree<T>` inside
            // `tree`) ties the knot on the entry under construction rather than recursing. An open record's
            // closure is a record, named for its content like every other closed form unless it names its own
            // application -- see closeOpenRecord and mintedRecord.
            RecordBody closed = closeOpenRecord((RecordBody) template, bind(parameters, arguments));
            refuseMintedMember(head, arguments, closed);
            if (!tied.contains(name)) {
                return mintedRecord(name, DerivedName.canonicalApplication(head, arguments), closed);
            }
            note(name, RECORD);
            Top instantiation = instantiation(name, closed, TypeRef.of(head, arguments), written);
            materialised.put(name, instantiation);
            publish.accept(name, instantiation);
            return name;
        } finally {
            closing.remove(name);
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
                && WireForm.recordWire(record, WireForm::annotationValue).coreValue() instanceof RecordValue wire
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
    private void refuseMintedMember(String head, List<TypeRef> arguments, Top closed) {
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
        String written = head + arguments.stream().map(TypeRef::name)
                .collect(java.util.stream.Collectors.joining(", ", "<", ">"));
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
    private static Map<String, TypeRef> bind(List<String> parameters, List<TypeRef> arguments) {
        Map<String, TypeRef> bindings = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            bindings.put(parameters.get(i), arguments.get(i));
        }
        return bindings;
    }

    // ── Closing an open record (§5.10) ───────────────────────────────────────────────────────────

    /**
     * An open record applied: its fields and parents substituted on the model ({@link Substitution}), each field's type
     * closed -- the applications in it, a held {@code meta.array<T>} or {@code box<T>} among them, closed now that the
     * parameter is bound -- and the record's own facts kept as they are written: its {@code extension} and its {@code
     * discriminators}, so an abstract template's application is an abstract record, the base of a family of its own
     * over the declarations that compose it (§5.10). The chain is computed after substitution, from the parents as
     * written ({@link Ancestry#chainOf}): a parent applied to the record's own parameter stays in {@code parents} beside
     * its template's ancestors, for {@code SchemaResolver}'s fold to turn into the edge to the entry the closed
     * application denotes -- {@code ok<text>} IS-A {@code result<text>} and not {@code result<int32>}, whatever order
     * the declarations were written in. Parents are substituted and not closed: an operand denotes no entry (§8.2).
     * The result is a record without {@code parameters}.
     *
     * <p><b>It is minted with no subtypes, like every resolved entry.</b> {@code subtypes} is linking's, derived one
     * phase later over the closed namespace ({@code TsonSchemaLinker}). Taking the open record's list instead would
     * hand each instantiation the whole family, and §8.2 names a minted entry by a function of its resolved form alone,
     * so two schemas closing one application must mint one entry. A member's field carries no source position, the
     * open record's being no member's.
     */
    private RecordBody closeOpenRecord(RecordBody open, Map<String, TypeRef> bindings) {
        AnnotatedMap<String, RecordField> substituted = Substitution.fields(open, bindings);
        AnnotatedMap<String, RecordField> fields = new AnnotatedMap<>();
        substituted.forEach((name, field) -> fields.put(name,
                field.withType(switch (field.type()) {
                    case TypeRef reference -> close(reference);
                    // A sibling names a field of the record, and has no application to close.
                    case SiblingRef sibling -> sibling;
                }).withPosition(Optional.empty()), substituted.getAnnotations(name)));
        return new RecordBody(Ancestry.chainOf(Substitution.parents(open, bindings), namespace),
                AnnotatedMap.copyOf(fields), open.groups(), open.extension(), open.discriminators(), open.nameType());
    }

    /** Records the constructor {@code name}'s value applies -- the tag resolved output writes. */
    private void note(String name, String constructor) {
        TsonSchema.EntryFacts known = facts.getOrDefault(name, TsonSchema.EntryFacts.NONE);
        facts.put(name, known.withConstructor(Optional.of(constructor)));
    }

    /**
     * {@code value} as the instantiation of {@code application} (§8.2): the closed record of an open record's
     * application, under a declared name or the application's own where its expansion names itself. Recorded in
     * {@link #applications}, with {@code written}, the application as written, which {@code SchemaResolver} writes
     * onto the entry's key as {@code @meta.application}.
     */
    private Top instantiation(String name, Top value, TypeRef application, TypeRef written) {
        applications.put(name, application);
        writtenApplications.put(name, asWritten(written));
        return value;
    }

    /**
     * {@code reference} as {@code @meta.application} states it: as written, a nested application unreduced and itself
     * stated this way, and a bare name followed through its alias chain, since §8.2 compares arguments after alias
     * flattening.
     */
    private TypeRef asWritten(TypeRef reference) {
        if (reference.arguments().isEmpty()) {
            return plain(dereferenced(reference));
        }
        return TypeRef.of(reference.name(), reference.arguments().stream().map(this::asWritten).toList());
    }

    /** {@code reference} with nothing but its name and arguments: no annotations, no closure. */
    private static TypeRef plain(TypeRef reference) {
        return TypeRef.of(reference.name(), reference.arguments());
    }

    /** Every application this pass closed, to the entry it closed to -- see {@link #closedApplications}. */
    Map<TypeRef, String> closedApplications() {
        return new LinkedHashMap<>(closedApplications);
    }

    /** The application {@code name}'s entry closes, as written, where it is an instantiation entry. */
    Optional<TypeRef> writtenApplication(String name) {
        return Optional.ofNullable(writtenApplications.get(name));
    }

    /**
     * What a closed form is called once every application inside it is an entry name -- the derivation {@link
     * #construction} already names by, offered to {@link SyntheticMerge} so the other lift channel's
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
            case ReferenceValue(TypeRef applied) ->
                    new TokenValue(close(applied).name(), TokenForm.UNQUOTED);
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
     * §5.10's call-site check: each argument must name a type that IS-A the bound of the parameter it binds
     * ({@link #checkBounds}), a verdict at the application rather than inside the substituted body.
     *
     * <p>A check asked for while a declaration is being made ready -- an application closed during resolution -- is
     * deferred to {@link #checkDeferred}, so judging an argument never makes the declaration wait on it.
     *
     * <p><b>Shared with the operand path</b>, through {@link ApplicationCloser#checkArguments}: a composition operand
     * absorbs by value and mints nothing, so it never reaches this pass.
     */
    void checkArguments(String head, Top template, List<TypeRef> arguments) {
        String declaration = resolving.get();
        if (declaration != null) {
            deferred.add(new DeferredCheck(declaration, head, List.copyOf(arguments)));
            return;
        }
        checkBounds(head, template, arguments);
    }

    /**
     * Each argument against the bound of the parameter it binds ({@code meta.param}, [TSON-SCHEMA] §5.10).
     * An argument this cannot judge is left for the position to refuse after substitution: an application as an
     * argument (its entry is not closed yet), and a name nothing declares (the linker's verdict).
     */
    private void checkBounds(String head, Top template, List<TypeRef> arguments) {
        List<Param> parameters = OpenEntry.parameters(template);
        if (!OpenEntry.isOpen(template) || parameters.size() != arguments.size()) {
            return;
        }
        for (int i = 0; i < arguments.size(); i++) {
            Param parameter = parameters.get(i);
            TypeRef argument = arguments.get(i);
            if (parameter.bound().isPresent() && argument.arguments().isEmpty()) {
                checkBound(head, parameter, argument.name(), parameter.bound().get().name());
            }
        }
    }

    private void checkBound(String head, Param parameter, String argument, String bound) {
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

    /** A name in the schema's own namespace first, then among its imported entries. */
    private Top lookup(String name) {
        Top local = namespace.definition(name);
        return local != null ? local : importedTypes.apply(name);
    }

}
