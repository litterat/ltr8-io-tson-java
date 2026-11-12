package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
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
 * <p><b>Nothing is minted.</b> A declaration naming an application of an open record, {@code page_order =>
 * page<order>}, is the record under that name with the application recorded ({@link #closeApplicationInto}, {@code
 * @meta.application}, §8.2). At a type slot -- of a closed record, or left by substitution in another record's body --
 * an application closes in place to the substituted record, {@code { of: !meta.record { … } }} ({@link TypeInline}):
 * no name, no chain, nothing recorded, and never looked up to a declaration ({@link #instantiate}). The one nested
 * application that names a declaration is the one a declaration is closing itself: {@code next: node<text>} inside
 * {@code node_text => node<text>} names {@code node_text}, so a recursion, direct or through records in place, ties to
 * the declaration it closes (§5.10.1). Two applications are refused: one that would put in place a member of a
 * declared type ({@code k: dog_of<text>} over {@code dog_of => <T> pet & { … }}, §5.2), and a recursive one with no
 * declaration closing it ({@code f: node<text>}, §5.10.1).
 *
 * <p><b>Where a name is required</b> -- an argument of an open record's application, a map's key, a reference's {@code
 * of}, an alias's target -- an application closes nowhere: a construction and an application of an open record are
 * each refused there, and declared under a name first ([TSON-SCHEMA] §4.2, §5.3). An argument of a constructor's
 * application lands at a type slot of the construction, so a construction there is written in place in turn: {@code
 * meta.map<text meta.array<T>>}, which an open record holds for {@code {text => [T]}}, closes to a map whose value is
 * the array in place.
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
     * The application each declaration this pass closes an application into records, by entry name (§8.2) -- shared
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
     * Each application closing in place, keyed on its canonical application, against the application as written,
     * for as long as it closes: one reached again inside its own closing is a recursion no declaration ties, and is
     * refused (§5.10.1) rather than expanded without end. A stack, so two sibling slots closing one application are
     * not a recursion.
     */
    private final Map<String, TypeRef> closingInPlace = new LinkedHashMap<>();

    /**
     * The application each declaration naming one closes into it, as written, its nested arguments unreduced: what
     * {@code @meta.application} states on the entry's key, so a reader and a message see the reference the author
     * wrote.
     */
    private final Map<String, TypeRef> writtenApplications = new LinkedHashMap<>();

    /**
     * How a construction becomes an ordinary constructor body -- the constructor's own compiled reader, the same one
     * a written {@code !meta.array { ... }} binds through. Using it is what makes an argument the slot refuses an
     * ordinary read error rather than a check this class would have to grow.
     */
    private final ConstructionReader constructionReader;

    /** Which open records are refused at their own declaration for a recursion they hold (§5.10.1). */
    private final HeldRecursion heldRecursion;

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

    /**
     * The applied-parent half of {@link #refuseMembership} for each record put in place while declarations were being
     * made ready, run by {@link #checkDeferred} once every one is: whether an applied parent is a declared application
     * is known only once every declaration has closed, and asking earlier would make the verdict depend on the order
     * the declarations stand in.
     */
    private final List<DeferredMembership> deferredMemberships = new ArrayList<>();

    private record DeferredMembership(String declaration, RecordBody template, Map<String, TypeRef> bindings,
                                      TypeRef written) {
    }

    TemplateMaterialiser(DefinitionGetter namespace, ConstructionReader constructionReader,
                         Function<String, Top> importedTypes, Supplier<String> resolving,
                         Map<String, TsonSchema.EntryFacts> facts, Map<String, TypeRef> applications,
                         Predicate<String> kernel, HeldRecursion heldRecursion) {
        this.heldRecursion = Objects.requireNonNull(heldRecursion, "heldRecursion");
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
        for (DeferredMembership check : deferredMemberships) {
            try {
                refuseAppliedMembership(check.template(), check.bindings(), check.written());
            } catch (SchemaValidationException e) {
                if (refused.add(check.declaration())) {
                    reporter.accept(check.declaration(), e);
                }
            }
        }
        deferredMemberships.clear();
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
                entry.setValue(MetaRefs.mapRefs(entry.getValue(), rewrite));
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
     * The name a fully-bound application at a refinement source leaves -- reached during resolution rather than from
     * the batch pass afterwards. A construction there is refused, as at any slot that holds a name, and so is an
     * application of a head that takes no parameters; one whose head names nothing is left whole for the linker. An
     * application of an open record, through its alias hops or not, is absorbed by value and never reaches here
     * ({@code DefinitionResolver.openOperand}).
     */
    String closeApplication(TypeRef application) {
        if (application.arguments().isEmpty()) {
            return application.name();
        }
        if (constructs(application)) {
            throw nameRequired(application);
        }
        return switch (closeApplicationOfRecord(application)) {
            case TypeRef named -> named.name();
            case TypeInline inline -> throw new IllegalStateException("'" + application.text() + "' closed in place "
                    + "at a refinement source, where an application of an open record is absorbed by value");
        };
    }

    /**
     * The entry a declaration naming a fully-bound application <b>is</b> -- see {@link
     * ApplicationCloser#closeApplicationInto}. Closes the application into {@code declaredName} and hands the
     * definition back for the caller to own. An open record's closure is a record ({@link #closeOpenRecord}). An
     * unresolvable or mis-applied head returns {@code null} for the caller to handle.
     */
    Top closeApplicationInto(String declaredName, TypeRef application) {
        String head = templateHead(application.name());
        Top template = closable(head, namespace.definition(head));
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
            arguments.add(dereferenced(closeArgument(argument, application)));
        }
        String canonical = CanonicalApplication.of(head, arguments);
        if (closingInto.putIfAbsent(canonical, declaredName) != null) {
            return null; // a second declaration of one application inside the first's closing: the ordinary path
        }
        try {
            note(declaredName, RECORD);
            Top closed = closeOpenRecord((RecordBody) template, bind(parameters, arguments));
            return instantiation(declaredName, closed, TypeRef.of(head, arguments), application);
        } finally {
            closingInto.remove(canonical);
        }
    }

    /**
     * What a reference at a type slot closes to: itself where it carries no arguments, the construction written in
     * place where it applies a constructor, and otherwise an application of an open record, closed in place or into
     * the declaration closing it ({@link #instantiate}).
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
     * What a reference at a slot that holds only a name closes to ([TSON-SCHEMA] §4.2): itself. An application closes
     * nowhere there -- a construction has no name, and an application of an open record closes in place only at a
     * type slot -- so either is refused, and the form is declared and named. A head naming nothing is left for the
     * linker to report as unresolved.
     */
    private TypeRef closeName(TypeRef ref) {
        if (!ref.arguments().isEmpty() && (constructs(ref) || appliesAnOpenRecord(ref))) {
            throw nameRequired(ref);
        }
        return ref;
    }

    /**
     * An argument of an open record's application: a name or a parameter ([TSON-SCHEMA] §5.3). A construction and an
     * application of an open record are refused, to be declared under a name first. A head naming nothing is left for
     * the linker to report as unresolved.
     */
    private TypeRef closeArgument(TypeRef argument, TypeRef application) {
        if (!argument.arguments().isEmpty() && (constructs(argument) || appliesAnOpenRecord(argument))) {
            throw argumentNotAName(argument, application);
        }
        if (argument.arguments().isEmpty() && appliesAnOpenRecord(argument)) {
            // A template is no type, and no position names one bare, an argument included (§5.10).
            throw new SchemaValidationException("'" + argument.name() + "', passed as an argument of '"
                    + application.text() + "', is a template, and a template is not a type -- write it with its "
                    + "arguments, declared under a name, and pass that name (§5.10)");
        }
        return argument;
    }

    /** Whether {@code application}'s head, past its alias hops, is an open record. */
    private boolean appliesAnOpenRecord(TypeRef application) {
        return OpenEntry.isOpen(lookup(AliasChain.terminal(application.name(), this::lookup)));
    }

    /**
     * The refusal of an application where only a name may stand -- a map's key, a reference's {@code of}, a
     * parameter's bound, an alias's target, a refinement source ([TSON-SCHEMA] §4.2) -- naming the declaration to
     * write instead.
     */
    static SchemaValidationException nameRequired(TypeRef written) {
        return new SchemaValidationException("'" + written.text() + "' is an application, and it stands where only a "
                + "name may stand -- a map's key, a reference's 'of', a parameter's bound or an alias's target -- where "
                + "an application closes nowhere (§4.2): declare it ('" + suggestedName(written) + " => "
                + written.text() + "') and write that name");
    }

    /**
     * The refusal of an application or a construction passed as an argument of an open record's {@code application}
     * ([TSON-SCHEMA] §5.3), naming the declaration to write instead.
     */
    static SchemaValidationException argumentNotAName(TypeRef argument, TypeRef application) {
        return new SchemaValidationException("'" + argument.text() + "' is passed as an argument of '"
                + application.text() + "', and an argument of an open record's application is a name or a parameter "
                + "(§5.3): declare it ('" + suggestedName(argument) + " => " + argument.text()
                + "') and pass that name");
    }

    /**
     * A name for a declaration of {@code application}, for a message: the head, then each argument, as [TSON-SCHEMA]
     * §5.10.1 writes {@code node_text => node<text>}.
     */
    static String suggestedName(TypeRef application) {
        StringBuilder name = new StringBuilder(application.segments().getLast());
        for (TypeRef argument : application.arguments()) {
            name.append('_').append(argument.arguments().isEmpty() ? argument.segments().getLast() : "my");
        }
        return name.toString();
    }

    /**
     * An application of an open record at a slot or a name, which keeps its argument list where its head names
     * nothing this pass can close: the list is the evidence the author supplied arguments, and the linker reports on
     * what it is handed.
     */
    private TypeExpr closeApplicationOfRecord(TypeRef ref) {
        List<TypeRef> arguments = new ArrayList<>();
        for (TypeRef argument : ref.arguments()) {
            arguments.add(dereferenced(closeArgument(argument, ref)));
        }
        TypeExpr closed = instantiate(templateHead(ref.name()), arguments, ref);
        return closed == null ? TypeRef.of(ref.name(), arguments, ref.annotations()) : closed;
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
        if (!(body instanceof Type type) || !TypeInline.constructible(type)) {
            throw new SchemaValidationException("'" + application.text() + "' constructs a "
                    + (body instanceof Type ? "type" : "value") + " that is no container, tuple, choice or reference, "
                    + "and only those are written where they are used (§5.2): declare it under a name ('my_"
                    + application.segments().getLast() + " => " + application.text() + "') and write that name");
        }
        // Each argument landed at a slot of the construction, read as the reference it was written as; closing the
        // body closes each one where it stands -- a nested constructor's application, which an open record holds
        // for a nested form over its parameter ({@code {text => [T]}}), in place at its type slot.
        return Optional.of(new TypeInline(constructor,
                (Type) MetaRefs.mapRefs(type.withChain(TypeChain.EMPTY), slotRewrite())));
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

    /** The open record an application's head names, past its alias hops ({@link Ancestry#templateHead}). */
    private String templateHead(String head) {
        return Ancestry.templateHead(head, namespace);
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
     * What a fully-bound application of an open record at a type slot closes to ([TSON-SCHEMA] §5.10): the
     * declaration closing this very application, where one is (a recursion, §5.10.1), and otherwise the substituted
     * record in place, with no name, no chain and nothing recorded. {@code null} where the head names nothing in
     * scope -- that is {@code TsonSchemaLinker}'s verdict to give as an unresolved reference, not this pass's to
     * guess at.
     *
     * <p>Two applications are refused first, being what a record in place may not be: one that would be a member of a
     * declared type (§5.2), and one that reaches itself while it closes in place, with no declaration to tie to
     * (§5.10.1). Every recursion through records in place ends at a declaration being closed or is refused: regularity
     * passes each parameter through unchanged, and an argument is a name, so a cycle repeats an application exactly.
     */
    private TypeExpr instantiate(String head, List<TypeRef> arguments, TypeRef written) {
        Top template = closable(head, namespace.definition(head));
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
        String canonical = CanonicalApplication.of(head, arguments);
        String owner = closingInto.get(canonical);
        if (owner != null) {
            // The declaration closing this application reached it again while substituting: the recursion names
            // the declaration, so the record closes into it.
            return TypeRef.of(owner);
        }
        Map<String, TypeRef> bindings = bind(parameters, arguments);
        refuseMembership(head, (RecordBody) template, bindings, written);
        TypeRef repeated = closingInPlace.get(canonical);
        if (repeated != null) {
            throw new SchemaValidationException("'" + repeated.text() + "' applies the recursive open record '"
                    + head + "' where no declaration is closing it, so its recursion has nothing to tie to "
                    + "(§5.10.1): declare it ('" + suggestedName(repeated) + " => " + repeated.text()
                    + "') and write that name where it is used");
        }
        closingInPlace.put(canonical, written);
        try {
            RecordBody closed = closeOpenRecord((RecordBody) template, bindings);
            return TypeInline.closing(plain(written), closed.withChain(TypeChain.EMPTY));
        } finally {
            closingInPlace.remove(canonical);
        }
    }

    /**
     * {@code template}, or its placeholder where the open record is refused at its own declaration for a recursion it
     * holds: the refusal is reported there once, and closing an application of it would only report it again against
     * the applier, whose remedy it is not (§5.10.1).
     */
    private Top closable(String head, Top template) {
        return OpenEntry.isOpen(template) && heldRecursion.refusedLocally(head)
                ? SchemaResolver.unresolved(OpenEntry.parameterNames(template)) : template;
    }

    /**
     * Refuses an application of the open record {@code head} that would close in place to a member of a declared type
     * ([TSON-SCHEMA] §5.2): one whose template reaches a declared type through its written parents, or whose applied
     * parent, substituted, is an application a declaration names. A member is read, tagged and bound by name, so it is
     * declared. The second is judged once every declaration has closed ({@link #checkDeferred}) where this runs while
     * declarations are being made ready.
     */
    private void refuseMembership(String head, RecordBody template, Map<String, TypeRef> bindings, TypeRef written) {
        TypeChain.supertypesOf(template).stream().findFirst().ifPresent(member -> {
            throw notAMember(written, member);
        });
        String declaration = resolving.get();
        if (declaration != null) {
            deferredMemberships.add(new DeferredMembership(declaration, template, Map.copyOf(bindings), written));
            return;
        }
        refuseAppliedMembership(template, bindings, written);
    }

    /** The applied-parent half of {@link #refuseMembership}. */
    private void refuseAppliedMembership(RecordBody template, Map<String, TypeRef> bindings, TypeRef written) {
        for (TypeRef parent : Substitution.parents(template, bindings)) {
            if (parent.arguments().isEmpty()) {
                continue;
            }
            TypeRef canonical = TypeRef.of(templateHead(parent.name()),
                    parent.arguments().stream().map(this::dereferenced).toList());
            Optional<String> member = applications.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(canonical)).map(Map.Entry::getKey).findFirst();
            if (member.isPresent()) {
                throw notAMember(written, member.get());
            }
        }
    }

    private static SchemaValidationException notAMember(TypeRef written, String member) {
        return new SchemaValidationException("'" + written.text() + "' would close in place to a record IS-A '"
                + member + "', a member of a declared type that no declaration names, and a member is read, tagged "
                + "and bound by its name (§5.2): declare it ('" + suggestedName(written) + " => " + written.text()
                + "') and write that name where it is used");
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
                    // A type written in place holds no parameter, and may still hold an application of an open
                    // record this pass has not reached in the template (`[box<text>; 2..]`): closed where it stands.
                    case TypeInline inline -> inline.withOf(
                            (Type) MetaRefs.mapRefs(inline.of(), slotRewrite()));
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

    /** The application {@code name}'s entry closes, as written, where it is a declared application. */
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
     * An argument this cannot judge is left for others to refuse: an application as an argument ({@link
     * #closeArgument}), and a name nothing declares (the linker's verdict).
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
