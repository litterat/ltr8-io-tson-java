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
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.Param;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
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
 * §5.10 materialisation: closes the applications a resolved schema holds, over the resolved form, not the AST. An
 * application reaches here as a {@link TypeRef} carrying {@code arguments}, so closing is a walk over {@code
 * schema.meta} values ({@link MetaRefs}).
 *
 * <p><b>Two operations share the spelling</b> ([TSON-SCHEMA] §5.10, §5.3). Over a constructor, {@code
 * meta.array<text>} constructs: the binding record {@code [text]} writes, built by the constructor's own reader
 * ({@link #construction}), and held in place at the slot that wrote it, {@code { of: !meta.array { element_type:
 * text } }} ({@link TypeInline}). Over an open record, {@code page<order>} substitutes: the record's parameters are
 * replaced by the arguments on the model and each field's application closed in turn ({@link #closeOpenRecord}). A
 * reference held inside an open record, {@code meta.array<T>} or {@code box<T>}, closes the same way once the record
 * holding it is applied and its parameter substituted.
 *
 * <p><b>Nothing is minted.</b> An application of an open record closes only into the declaration that names it,
 * {@code page_order => page<order>} ({@link #closeApplicationInto}), the record under that name with the application
 * recorded ({@code @meta.application}, §8.2). Anywhere else -- at a slot of a closed record, left by substitution in
 * another record's body, at a refinement source -- an application of an open record would close in place, which this
 * library does not do yet: it is reported as not yet supported ({@link UnsupportedOperationException}). The one
 * nested application that closes is the one a declaration is closing itself: {@code next: node<text>} inside {@code
 * node_text => node<text>} names {@code node_text}, so a recursive open record closes into its declaration.
 *
 * <p><b>Where a name is required</b> -- an argument of an open record's application, a map's key, a reference's {@code
 * of}, an alias's target -- a construction has nowhere to stand: it is refused, and declared under a name first. An
 * argument of a constructor's application lands at a type slot of the construction, so a construction there is
 * written in place in turn: {@code meta.map<text meta.array<T>>}, which an open record holds for {@code {text =>
 * [T]}}, closes to a map whose value is the array in place.
 */
final class TemplateMaterialiser {

    /**
     * Every entry visible to this schema -- local declarations and merged {@code !!import}s alike. A
     * <em>getter</em> rather than a fixed map, because an application closed on demand during resolution
     * (at a refinement source) may name a head that has not been resolved yet; the getter is
     * {@code SchemaResolver}'s own memo, so asking for it resolves it, with the circular-composition guard
     * still in front.
     */
    private final DefinitionGetter namespace;

    /** The constructor an open record's applications are built with. */
    private static final String RECORD = KernelNames.of("record");

    /**
     * The application each instantiation this pass closed is the instantiation of, by entry name (§8.2) -- shared
     * with {@code SchemaResolver}, which keys the applied-parent fold on it and writes it on the entry's key.
     */
    private final Map<String, TypeRef> applications;

    /**
     * The facts of every entry this pass closes into a declaration -- the constructor its value applies and the
     * application it closes. {@code SchemaResolver}'s own map, shared.
     */
    private final Map<String, TsonSchema.EntryFacts> facts;

    /**
     * The declaration each application being closed into one is closing into, keyed on §8.2's canonical
     * application, for as long as the closing runs: a recursive application reached while substituting names the
     * declaration instead of closing again ({@link #closeApplicationInto}).
     */
    private final Map<String, String> closingInto = new LinkedHashMap<>();

    /**
     * Every application this pass closed into a declaration, to that declaration. Held beside the entries, never
     * written ({@link #closedApplications}).
     */
    private final Map<TypeRef, String> closedApplications = new LinkedHashMap<>();

    /**
     * Each instantiation entry's application as it was written, its nested arguments unreduced: what {@code
     * @meta.application} states on the entry's key, so a reader and a message see the reference the author wrote.
     */
    private final Map<String, TypeRef> writtenApplications = new LinkedHashMap<>();

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
     * it, a composition operand, or an argument nested in one -- is judged by its arguments' chains, and an
     * argument is a name at a type position, which orders nothing ([TSON-SCHEMA] §3.4.1): judging it then would
     * make the declaration wait on its argument.
     */
    private final Set<DeferredCheck> deferred = new LinkedHashSet<>();

    private record DeferredCheck(String declaration, String head, List<TypeRef> arguments) {
    }

    TemplateMaterialiser(DefinitionGetter namespace, ConstructionReader constructionReader,
                         Function<String, Top> importedTypes, Supplier<String> resolving,
                         Map<String, TsonSchema.EntryFacts> facts, Map<String, TypeRef> applications,
                         Predicate<String> kernel) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.applications = Objects.requireNonNull(applications, "applications");
        this.constructionReader = Objects.requireNonNull(constructionReader, "constructionReader");
        this.importedTypes = Objects.requireNonNull(importedTypes, "importedTypes");
        this.resolving = Objects.requireNonNull(resolving, "resolving");
    }

    /**
     * Runs every argument check deferred while declarations were being made ready, reporting a failure against the
     * declaration whose resolution closed the application. One verdict per declaration, as resolution itself gives.
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
     * Closes every application reachable from {@code entries}, rewritten in place: a constructor's to the
     * construction at the slot that wrote it, and one this pass cannot close reported against the entry that wrote
     * it, which is left as it was.
     */
    void materialise(Map<String, Top> entries, MaterialisationFailureReporter reporter) {
        MetaRefs.Rewrite rewrite = slotRewrite();
        for (Map.Entry<String, Top> entry : entries.entrySet()) {
            try {
                entry.setValue(MetaRefs.mapRefsKeepingChain(entry.getValue(), rewrite));
            } catch (SchemaValidationException | UnsupportedOperationException e) {
                if (reporter == null) {
                    throw e;
                }
                // Reported against the entry that wrote the application, and left as it was: an entry still
                // naming an open template is one the linker reports again, but that second complaint is
                // about the same line and does not invent a new problem.
                reporter.reportFailedApplication(entry.getKey(), e);
            }
        }
    }

    /** Where an application this pass cannot close is reported, entry by entry. */
    @FunctionalInterface
    interface MaterialisationFailureReporter {
        void reportFailedApplication(String entryName, RuntimeException error);
    }

    /**
     * The entry a fully-bound application at a refinement source denotes -- the on-demand half, reached during
     * resolution rather than from the batch pass afterwards. A source is a name, and an application of an open
     * record nothing declares has none, so it is reported as not yet supported.
     */
    String closeApplication(TypeRef application) {
        return closeName(application).name();
    }

    /**
     * The entry a declaration naming a fully-bound application <b>is</b> -- see {@link
     * ApplicationCloser#closeApplicationInto}. Closes the application into {@code declaredName} and hands the
     * definition back for the caller to own. An open record's closure is a record ({@link #closeOpenRecord}). An
     * unresolvable or mis-applied head returns {@code null} for the caller to handle.
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
        // before anything is derived from the list.
        checkArguments(head, template, application.arguments());
        List<TypeRef> arguments = new ArrayList<>();
        for (TypeRef argument : application.arguments()) {
            arguments.add(dereferenced(closeArgument(argument)));
        }
        String canonical = DerivedName.canonicalApplication(head, arguments);
        if (closingInto.putIfAbsent(canonical, declaredName) != null) {
            return null; // a second declaration of one application inside the first's closing: the ordinary path
        }
        try {
            note(declaredName, RECORD);
            Top closed = closeOpenRecord((RecordBody) template, bind(parameters, arguments));
            closedApplications.putIfAbsent(plain(application), declaredName);
            return instantiation(declaredName, closed, TypeRef.of(head, arguments), application);
        } finally {
            closingInto.remove(canonical);
        }
    }

    /**
     * What a reference at a type slot closes to: itself where it carries no arguments, the construction written in
     * place where it applies a constructor, and otherwise an application of an open record ({@link #instantiate}).
     */
    private TypeExpr closeSlot(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        Optional<TypeInline> constructed = construction(ref);
        if (constructed.isPresent()) {
            return constructed.get();
        }
        return closeApplicationOfRecord(ref);
    }

    /**
     * What a reference at a slot that holds a name closes to: itself where it carries no arguments, and otherwise an
     * application of an open record ({@link #instantiate}). A construction has no name, so one here is refused.
     */
    private TypeRef closeName(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        if (constructs(ref)) {
            throw new SchemaValidationException("'" + ref.text() + "' is a construction, and it stands where a "
                    + "name is required -- a map's key, a reference's 'of', an alias's target or a parent -- where "
                    + "nothing is written in place (§5.3). Declare it under a name ('my_" + ref.segments().getLast()
                    + " => " + ref.text() + "') and write that name");
        }
        return closeApplicationOfRecord(ref);
    }

    /**
     * An argument of an application, closed: a name or a parameter ([TSON-SCHEMA] §5.10). A construction is refused,
     * to be declared under a name first, and an application of an open record is closed as at a name.
     */
    private TypeRef closeArgument(TypeRef argument) {
        if (!argument.arguments().isEmpty() && constructs(argument)) {
            throw new SchemaValidationException("'" + argument.text() + "' is a construction passed as an argument, "
                    + "and an argument is a name or a parameter (§5.10): declare it under a name ('my_"
                    + argument.segments().getLast() + " => " + argument.text() + "') and pass that name");
        }
        return closeName(argument);
    }

    /**
     * An application of an open record at a slot or a name, which keeps its argument list where its head names
     * nothing this pass can close: the list is the evidence the author supplied arguments, and the linker reports on
     * what it is handed.
     */
    private TypeRef closeApplicationOfRecord(TypeRef ref) {
        List<TypeRef> arguments = new ArrayList<>();
        for (TypeRef argument : ref.arguments()) {
            arguments.add(dereferenced(closeArgument(argument)));
        }
        String entry = instantiate(templateHead(ref.name()), arguments, ref);
        return entry == null ? TypeRef.of(ref.name(), arguments, ref.annotations()) : TypeRef.of(entry);
    }

    /** Whether {@code application}'s head, past its alias hops, is a constructor ({@link ConstructionSugar}). */
    private boolean constructs(TypeRef application) {
        String constructor = AliasChain.terminal(application.name(), this::lookup);
        return ConstructionSugar.constructs(constructor, lookup(constructor));
    }

    /**
     * The construction an application over a <b>constructor</b> denotes, written in place, or empty where the head,
     * past its alias hops, is no constructor ({@link ConstructionSugar}). {@code meta.array<text>} is the
     * construction {@code !meta.array { element_type: text }}, built by the constructor's own reader from the binding
     * record its arguments write, each argument a name. Only a container, a tuple, a choice or a reference is written
     * in place ({@link TypeInline}); any other construction is declared under a name.
     *
     * <p><b>No {@code @meta.application}, and no entry.</b> The value states its constructor and its arguments, and
     * nothing is substituted, so there is nothing to record beside it.
     */
    private Optional<TypeInline> construction(TypeRef application) {
        String constructor = AliasChain.terminal(application.name(), this::lookup);
        Top definition = lookup(constructor);
        if (!ConstructionSugar.constructs(constructor, definition)) {
            return Optional.empty();
        }
        RecordValue wire = ConstructionSugar.payload(application, (RecordBody) definition, this::lookup);
        Top body;
        try {
            body = constructionReader.read(constructor, new DataValue(List.of(), Optional.of(constructor), wire));
        } catch (ReadException e) {
            throw DefinitionResolver.payloadFailure("'" + application.text() + "' constructs a body that",
                    "'" + application.text() + "' constructs '!" + constructor + " { ... }', which is not valid "
                            + "data for '" + constructor + "' -- " + e.getMessage(), e);
        }
        if (!(body instanceof Type type) || !TypeInline.inlinable(type)) {
            throw new SchemaValidationException("'" + application.text() + "' constructs a "
                    + (body instanceof Type ? "type" : "value") + " that is no container, tuple, choice or reference, "
                    + "and only those are written where they are used (§5.2): declare it under a name ('my_"
                    + application.segments().getLast() + " => " + application.text() + "') and write that name");
        }
        // Each argument landed at a slot of the construction, read as the reference it was written as; closing the
        // body closes each one where it stands -- a nested constructor's application, which an open record holds
        // for a nested form over its parameter ({@code {text => [T]}}), in place at its type slot.
        return Optional.of(new TypeInline((Type) MetaRefs.mapRefsKeepingChain(type.withChain(TypeChain.EMPTY),
                slotRewrite())));
    }

    /** What {@link #materialise} applies to every reference: a slot's closed in place, a name's to a name. */
    private MetaRefs.Rewrite slotRewrite() {
        return new MetaRefs.Rewrite() {
            @Override
            public TypeRef atName(TypeRef reference) {
                return closeName(reference);
            }

            @Override
            public TypeExpr atSlot(TypeRef reference) {
                return closeSlot(reference);
            }
        };
    }

    /**
     * The template an application's head names: the head itself, or, where it is an alias of a template ({@code
     * ref_of => ref}), the template at the end of its alias chain, the alias being the same entry under
     * another name ([TSON-SCHEMA] §8.3).
     */
    private String templateHead(String head) {
        String terminal = AliasChain.terminal(head, namespace::definition);
        return !terminal.equals(head) && OpenEntry.isOpen(namespace.definition(terminal)) ? terminal : head;
    }

    /**
     * A type argument named by its own type rather than by a name for it: a bare reference is replaced with
     * the entry at the end of its chain. A reference is a pure rename ({@code user_id => uuid}), so an
     * application of one denotes the same type (§7.2 compares "after alias flattening of both"); a refinement or a
     * fresh type is an entry of its own and is not dereferenced.
     */
    private TypeRef dereferenced(TypeRef ref) {
        if (!ref.arguments().isEmpty()) {
            return ref;
        }
        String terminal = AliasChain.terminal(ref.name(), namespace::definition);
        return terminal.equals(ref.name()) ? ref : TypeRef.of(terminal, ref.arguments(), ref.annotations());
    }

    /**
     * The entry a fully-bound application of an open record denotes, or {@code null} where the head names nothing in
     * scope -- that is {@code TsonSchemaLinker}'s verdict to give as an unresolved reference, not this pass's to guess
     * at. The one entry it can name is the declaration closing this very application (a recursion); anything else
     * would close in place, not yet supported.
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
        String owner = closingInto.get(DerivedName.canonicalApplication(head, arguments));
        if (owner != null) {
            // The declaration closing this application reached it again while substituting: the recursion names
            // the declaration, so the record closes into it.
            return owner;
        }
        throw new UnsupportedOperationException("'" + written.text() + "' applies the open record '" + head
                + "' where no declaration is closing it, and closing an application in place is not yet supported "
                + "([TSON-SCHEMA] §5.10): declare it ('my_" + head.substring(head.lastIndexOf('.') + 1) + " => "
                + written.text() + "') and write that name where it is used");
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
     * discriminators}, so an abstract template's application is an abstract record (§5.10). The chain is computed
     * after substitution, from the parents as written ({@link Ancestry#chainOf}): a parent applied to the record's own
     * parameter stays in {@code parents} for {@code SchemaResolver}'s fold to turn into the edge to the entry the
     * closed application denotes. Parents are substituted and not closed: an operand denotes no entry (§8.2). The
     * result is a record without {@code parameters}, with no subtypes ({@code subtypes} is linking's), and its fields
     * carry no source position.
     */
    private RecordBody closeOpenRecord(RecordBody open, Map<String, TypeRef> bindings) {
        AnnotatedMap<String, RecordField> substituted = Substitution.fields(open, bindings);
        AnnotatedMap<String, RecordField> fields = new AnnotatedMap<>();
        substituted.forEach((name, field) -> fields.put(name,
                field.withType(switch (field.type()) {
                    case TypeRef reference -> switch (closeSlot(reference)) {
                        case TypeRef closed -> closed;
                        case TypeInline inline -> inline;
                    };
                    // A type written in place holds no parameter and no application: it is closed already.
                    case TypeInline inline -> inline;
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
     * application under a declared name. Recorded in {@link #applications}, with {@code written}, the application as
     * written, which {@code SchemaResolver} writes onto the entry's key as {@code @meta.application}.
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

    /** Every application this pass closed into a declaration, to that declaration. */
    Map<TypeRef, String> closedApplications() {
        return new LinkedHashMap<>(closedApplications);
    }

    /** The application {@code name}'s entry closes, as written, where it is an instantiation entry. */
    Optional<TypeRef> writtenApplication(String name) {
        return Optional.ofNullable(writtenApplications.get(name));
    }

    /**
     * §5.10's call-site check: each argument must name a type that IS-A the bound of the parameter it binds
     * ({@link #checkBounds}), a verdict at the application rather than inside the substituted body.
     *
     * <p>A check asked for while a declaration is being made ready -- an application closed during resolution -- is
     * deferred to {@link #checkDeferred}, so judging an argument never makes the declaration wait on it.
     *
     * <p><b>Shared with the operand path</b>, through {@link ApplicationCloser#checkArguments}: a composition operand
     * absorbs by value and closes nothing, so it never reaches this pass.
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
     * argument, and a name nothing declares (the linker's verdict).
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
