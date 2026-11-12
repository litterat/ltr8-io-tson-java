package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.compiler.ast.schema.SchemaMapDef;
import io.ltr8.tson.schema.SchemaPaths;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.base.MissingBindingException;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.compiler.TsonDiagnostics;
import io.ltr8.tson.compiler.TsonCompiledSchemaLoader;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonTypeReader;
import io.ltr8.tson.compiler.SchemaLocation;
import io.ltr8.tson.compiler.SchemaPositions;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.TsonCompiledVocabulary;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.reader.NamespaceInstanceReader;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonLinkedSchema;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.TsonSchemaRegistry;
import io.ltr8.tson.base.SchemaRefusalException;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;

import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Param;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.NamespaceBody;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves a whole {@link SchemaDocument} into a {@link TsonSchema}: header-directive validation
 * ({@code !!id}/{@code !!import}), merging every {@code !!import}'s entries into the
 * schema's one namespace (Part 2 §3.3) through this resolver's own {@link TsonCompiledSchemaLoader}, and
 * handing each local declaration off to a {@link DefinitionResolver} for the actual, per-declaration
 * resolution work (Part 2 §4, §8's <code>{meta.type_name =&gt; top}</code> shape, an entry being its value). A
 * construction head and an annotation type are read through the compiled form of the schema that declared them
 * ({@link Vocabulary}).
 *
 * <p>A {@link TsonCompiledSchemaLoader} is required: a document-level resolution that can't validate
 * its own {@code !!id} or reach its own imports isn't a degraded version of this job, it's a
 * different one -- per-declaration resolution against a hand-built namespace, which is exactly what
 * {@link DefinitionResolver} is for directly.
 *
 * <p>{@link #resolveSchema(SchemaDocument)} builds a fresh {@link DefinitionResolver} per call, not a
 * reused field, since a single instance of this class resolves documents with different closures across
 * separate calls, and the readers a call reads through are its closure's. The namespace ({@code namespace}, this
 * method's own local, import-seeded map) is built before the {@link DefinitionResolver} it
 * feeds and reaches it through a {@link WaitOrder} over that same map rather than a copy -- so the resolver sees
 * each newly-added entry immediately, and asking for one not yet ready makes it ready, in wait order.
 */
public final class SchemaResolver {

    private final TsonCompiledSchemaLoader loader;

    /** [TSON-SCHEMA] §11.5's limits this resolver counts: the wait depth ({@link WaitOrder}). */
    private final LimitsPolicy limits;

    /**
     * @param loader consulted by {@link #resolveSchema(SchemaDocument)} to resolve a document's own
     *               {@code !!import} targets -- fetching, resolving, registering, and
     *               compiling each as needed rather than requiring them to already exist somewhere.
     *               Required.
     *
     *               <p>Deliberately the {@link TsonCompiledSchemaLoader} interface, not the concrete
     *               registry that implements it -- and a loader rather than a plain "look it up, throw
     *               if missing" lookup, because the kernel is never resolved from a document: it is
     *               pre-loaded ([TSON-SCHEMA] §3.4), and the loader's implementation ({@code
     *               TsonCompiledVocabularyRegistry}) answers for it from its bootstrap.
     */
    public SchemaResolver(TsonCompiledSchemaLoader loader) {
        this(loader, loader.limits());
    }

    /**
     * {@link #SchemaResolver(TsonCompiledSchemaLoader)} resolving under {@code limits} rather than the loader's own:
     * how a registry resolves the library's bundled schemas, under the defaults whatever the deployment configured.
     */
    public SchemaResolver(TsonCompiledSchemaLoader loader, LimitsPolicy limits) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Resolves every declaration in {@code document}'s body in wait order (§3.4.1, {@link WaitOrder}), never source
     * order -- so a declaration may compose, refine or apply one declared later in the same schema, or read a value
     * or an annotation against one, with entries waiting on each other refused naming the cycle and a chain of
     * waits past §11.5's wait depth refused at its head -- and carries {@code document}'s own header directives
     * (§2.2: {@code !!id}?/{@code !!import}*) straight into the result's {@link TsonSchema#id()}/{@link
     * TsonSchema#imports()}.
     *
     * <p><b>Two things are validated up front, before any declaration is resolved</b>, rather than
     * silently proceeding (or failing confusingly deep inside some unrelated declaration): (1)
     * {@code document.id()} must be present -- optional in the grammar ([TSON-DATA] §2.2), required of a schema
     * registered under its own name (§2.2.1: "publishing a schema... REQUIRES !!id"), which is what this resolver
     * is about to do with {@code document}; §10.1's registration of an id-less schema under an application-supplied
     * identity is not a route this resolver offers; (2) that {@code !!id} must be a well-formed canonical-identity candidate
     * ({@link CanonicalIdentity#validate}) -- the same check {@link TsonSchemaRegistry#register} would run anyway,
     * just surfaced here, before resolution work is spent on a document that could never actually be registered.
     * Both are the author's error, so both throw {@link SchemaValidationException} naming the actual problem,
     * which the collecting front door reports as a schema error against the document as a whole.
     *
     * <p><b>Every {@code !!import} is merged into the one namespace</b>
     * ({@link TsonSchema#namespaceSources}) -- each import's own URI is validated the same way {@code !!id} is,
     * then resolved via this resolver's own {@link TsonCompiledSchemaLoader} (fetched/bootstrapped on demand) and
     * its entries merged in, *before* any local declaration is resolved. That map ({@code namespace}, exposed to
     * {@link DefinitionResolver} through a {@link WaitOrder}) is where every name resolves: a construction head, a
     * composition supertype, a refinement source, an annotation type. net.tn's own {@code net.ipv4_type =>
     * meta.atom & atom.specification & {...}}, composing with two kernel entries it has only through its
     * import of meta.tn, would fail to resolve at all without this. Collision
     * handling mirrors {@code TsonSchemaLinker.mergeImports}'s own rule exactly: a name declared by
     * more than one import, or by an import *and* a local declaration, is a {@link
     * SchemaValidationException} -- checked as each import is merged in, and again as each local
     * declaration is about to be resolved, so a collision is caught at the earliest point either side
     * of it becomes known. <b>Merged entries keep their home namespace</b>, same as {@code
     * TsonSchemaLinker}'s own note on this: an imported entry is copied in exactly as its own schema
     * resolved it, never re-resolved or re-materialized against the importer. The result's own {@link
     * TsonSchema#entries()} is local-only -- imported entries are visible *during* resolution but are
     * never part of what this method itself returns; a caller that wants the merged whole gets it
     * from {@code TsonSchemaRegistry.register}'s own eventual output instead.
     */
    public TsonSchema resolveSchema(SchemaDocument document) {
        return resolveSchema(document, SchemaPositions.none());
    }

    /**
     * {@link #resolveSchema(SchemaDocument)} with each declaration's own source position, so every resolved
     * entry's facts record where it was declared -- {@code TsonSchemaParser#declarationPositions()} for the
     * same document is what a caller passes.
     *
     * <p><b>Identity-keyed, which is why {@code SchemaDesugarer} shares structure <em>and</em> carries
     * positions across the rewrites it cannot avoid.</b> The map comes from an {@code IdentityHashMap}, so
     * only a declaration the parser itself built is found in it as given. Structural sharing keeps that true
     * for everything with no sugar in it ({@code SchemaDesugarerTest}'s {@code assertSame} is the invariant),
     * and {@code SchemaDesugarer.schemaMap} re-registers the position of every declaration it does rebuild --
     * without which any record holding a single {@code [T]} field would resolve with no position at all. An
     * <em>injected</em> declaration still has none, which is correct: it has no source text of its own.
     *
     * <p>The position reaches a diagnostic two ways: {@link TsonSchema#positionOf} is what a read-time
     * {@code Diagnostic}'s {@code schemaPosition} is populated from, so a value error can say where the type
     * it violated was declared; and it is what schema-side reporting will attach its own problems to.
     */
    public TsonSchema resolveSchema(SchemaDocument document,
                                    SchemaPositions declarationPositions) {
        return resolve(document, declarationPositions, null);
    }

    /**
     * {@link #resolveSchema(SchemaDocument, SchemaPositions)} reporting each declaration that fails to resolve through
     * {@code receiver} instead of throwing at the first one ([TSON-DATA] §8.1: implementations SHOULD
     * "continue processing after an error to report multiple issues in a single pass"). Every declaration is
     * attempted; a failed one is reported and replaced with a placeholder so its siblings and dependents still
     * resolve, and the schema comes back with as much resolved as could be.
     *
     * <p><b>The result is only trustworthy if nothing was reported.</b> A schema that produced diagnostics
     * contains placeholder entries and must not be linked, registered or compiled -- the caller checks the
     * receiver and stops, which is the phase boundary javac and Swift both draw (javac attributes every entry
     * before {@code shouldStopPolicyIfError} blocks the next phase; Swift never reaches SILGen after a Sema
     * error). The fail-fast overloads make the first failure an exception and so keep that impossible by
     * construction.
     *
     * <p>Three exception types become a diagnostic, and the type picks the code: a
     * {@link SchemaValidationException} -- the schema is wrong -- is a schema error, an
     * {@code UnsupportedOperationException} is {@code NOT_IMPLEMENTED}, and a {@link BindMismatchException}
     * is {@code BIND_MISMATCH}. A gap is collected beside the ordinary problems rather than propagating,
     * since thrown out of a per-declaration phase it would take every other declaration's verdict with it;
     * it is the code that keeps it from reading as a verdict on the author's schema.
     *
     * <p><b>The fail-fast overloads do not route through {@link DiagnosticsReceiver#throwing()}</b>, which
     * would raise {@code ReadException} -- a schema that fails to resolve is not a read failure, and the
     * CLI's own exit codes turn on that distinction. They rethrow the original instead, unwrapped and with
     * its stack intact.
     *
     * @param receiver where a failed declaration is reported; must not be {@code null}
     */
    public TsonSchema resolveSchema(SchemaDocument document,
                                    SchemaPositions declarationPositions,
                                    DiagnosticsReceiver receiver) {
        Objects.requireNonNull(receiver, "receiver");
        return resolve(document, declarationPositions, receiver);
    }

    /** The shared body; {@code receiver} is {@code null} for the fail-fast overloads, which rethrow instead. */
    private TsonSchema resolve(SchemaDocument document,
                               SchemaPositions declarationPositions,
                               DiagnosticsReceiver receiver) {
        String id = document.id().orElseThrow(() -> new SchemaValidationException(
                "!!id is required to register this schema under its own name, but is absent -- an id-less schema "
                        + "is a development artifact ([TSON-SCHEMA] §2.2.1, §10.1), and this schema has no name to "
                        + "be registered under"));
        CanonicalIdentity.validate(id);
        for (String importUri : document.imports()) {
            CanonicalIdentity.canonicalize(importUri);
        }

        IdentifierPolicy names = loader.identifierPolicy();
        Map<String, Annotations> nameAnnotations = new LinkedHashMap<>();
        // Every entry's facts -- an import's as its schema recorded them, a local one's as resolution learns
        // them -- one map the resolver and the materialiser both write, so a constructor's tag is found
        // wherever the entry came from.
        Map<String, TsonSchema.EntryFacts> facts = new LinkedHashMap<>();
        // Which names denote the kernel's own entries: a kind and a constructor are judged by where a base
        // kind is defined, never by its spelling (§4.1), so a schema's own `namespace` stays an ordinary record.
        Set<String> kernelNames = new LinkedHashSet<>();
        // Which application each entry is the instantiation of (§8.2): an import's as its key states it, a local
        // one's as materialisation closes it. Keyed on the entry, whatever its kind; written on the local keys as
        // `@meta.application` where the entry map is assembled.
        Map<String, io.ltr8.tson.schema.meta.TypeRef> applications = new LinkedHashMap<>();
        // Which schema declared each imported entry: the compiled reader a construction head or an annotation is read
        // through is that schema's.
        Map<String, String> origins = new LinkedHashMap<>();
        Map<String, Top> namespace = mergeImports(document, nameAnnotations, facts, kernelNames, origins);
        nameAnnotations.forEach((name, annotations) -> TsonSchema.applicationOf(annotations)
                .ifPresent(application -> applications.put(name, application)));
        // The kernel's own entries are the kernel's, kinds and constructors judged by origin, and its resolution reads
        // every head through its pre-loaded stand-in (§3.4).
        boolean isKernel = TsonBundledSchemas.isKernel(id);
        Predicate<String> kernel = name -> isKernel || kernelNames.contains(name);

        // Expand the sugar forms before anything reads a declaration, so resolution below only ever sees a
        // bare reference or `!C value` (§5.3/§5.6 define these as desugarings; §3.3.1 names their targets).
        // Returns `document` itself when there is no sugar to expand.
        //
        // Desugaring reports through the same receiver resolution does, rather than throwing at the first bad
        // sugar form. It needs no phase gate of its own: it runs inside this method, so whatever it reports is
        // already behind the one gate the caller checks after this call returns. The reporter is built here
        // because everything Diagnostic.ofSchemaError wants -- the canonical schema id, the identity-keyed
        // position table -- is this method's, not an AST-to-AST rewrite's.
        // A mutable identity-keyed copy, because desugaring rewrites the declarations that contain sugar and
        // has to carry each one's position onto the node it produced -- see SchemaDesugarer.schemaMap. Every
        // position lookup below goes through this copy, so a rewritten declaration is located like any other.
        SchemaPositions positions = declarationPositions.copy();
        Problems problems = new Problems(CanonicalIdentity.canonicalize(id), positions, receiver);
        // Desugaring rewrites each sugar form in place and adds no declaration, so the declarations below are
        // exactly the ones the author wrote.
        SchemaDocument desugared = SchemaDesugarer.desugar(document,
                problems.collecting() ? problems::report : null, positions);
        Map<String, SchemaMap.Declaration> declarations = desugared.body().declarations();

        // Local-vs-import collisions, up front (local names are already unique -- SchemaMap dedupes them). A schema
        // map of one path is open: this document's contribution to an imported one is no collision, its members
        // colliding by path as any name does.
        for (Map.Entry<String, SchemaMap.Declaration> local : declarations.entrySet()) {
            String name = local.getKey();
            if (namespace.containsKey(name) && !(local.getValue().typeDef() instanceof SchemaMapDef
                    && SchemaPaths.isNestedMap(namespace.get(name)))) {
                throw new SchemaValidationException("'" + name
                        + "' collides with an entry of the same name brought in by !!import");
            }
            if (local.getValue().typeDef() instanceof SchemaMapDef) {
                // This document's own map is resolved here, holding its own members; the imports' members stay
                // keyed under their paths.
                namespace.remove(name);
                origins.remove(name);
                facts.remove(name);
            }
        }

        // Every declaration is made ready in wait order (§3.4.1), on demand: an entry waits on
        // its head, its refinement source and operands, an annotation's type and every type a read of its value
        // reaches, each made ready first, whichever is written first. A name at a type position (a field's, a
        // variant's, an element's) is carried as a name, judged by the linker, and orders nothing. The imports are
        // ready before anything starts; a value read against one of this document's own entries is read through
        // readers compiled from the entries ready so far.
        OnDemand onDemand = new OnDemand(declarations, Set.copyOf(namespace.keySet()), problems, facts);
        WaitOrder waitOrder = new WaitOrder(CanonicalIdentity.canonicalize(id), namespace, declarations,
                limits.maxWaitDepth(),
                (pending, tree) -> loader.compileReady(readySoFar(document, namespace, origins, facts), pending,
                        tree));
        waitOrder.readyWith(onDemand);
        Vocabulary vocabulary = isKernel ? new Vocabulary(loader.loadVocabulary(id))
                : new Vocabulary(namespace, origins, waitOrder);
        // Where a construction head and an annotation type resolve: the whole namespace, made ready in wait order --
        // except in the kernel, whose own resolution reads every head through its pre-loaded stand-in (§3.4).
        DefinitionGetter heads = isKernel ? vocabulary::definition : waitOrder;
        // Each body a read produced, with the references it read by name ([TSON-SCHEMA] §5.12) and the dependent
        // fields' values it held as written (§5.2), keyed by the body itself: the read hooks know the constructor
        // and the value, not the entry, and the body is the one thing that reaches the entry unchanged.
        Map<Object, HeldInBody> heldByName = new IdentityHashMap<>();
        // One materialiser for the whole schema, created before the driving loop because resolution itself asks
        // it for applications on demand: a declaration naming an application closes into its own name as it
        // resolves, and an absorbed operand's arguments are checked against their bounds. Sharing the instance
        // is what lets the batch pass below see what those closings recorded.
        // A construction builds whatever its head builds: a type where the head is a constructor, and a value entry
        // where it is an ordinary type.
        ConstructionReader reads = new ConstructionReader() {

            @Override
            public Top read(String written, io.ltr8.tson.compiler.ast.DataValue value) {
                // An alias of a constructor is applied as its target is (§3.3.1).
                String type = heads.definition(written) == null ? written
                        : AliasChain.terminal(written, heads::definition);
                if (!type.equals(written) && value.typeRef().filter(written::equals).isPresent()) {
                    value = new io.ltr8.tson.compiler.ast.DataValue(value.annotations(), Optional.of(type),
                            value.coreValue());
                }
                Top head = heads.definition(type);
                if (head != null && !EntryKind.isConstructor(type, head, kernel)) {
                    return new ValueEntry(type, readValue(type, value));
                }
                return constructed(type, head, value);
            }

            /**
             * An instance of the constructor {@code type}: the schema model's body where the head is the kernel's
             * or net.tn's, whose names always bind to it. Any other constructor's instance builds no body this
             * library models, so it is held as the value it is, as a value entry's payload is ([TSON-SCHEMA] §4.1):
             * as the object the bind context binds the head to, or, where none does, as a tree, so the schema loads
             * and validates without host classes and a schema obtained to bind refuses it. A constructor whose
             * instances are types is the exception: a type's body is one of the kernel's, and an instance of a
             * constructor of types no factory backs is a gap.
             */
            private Top constructed(String type, Top head, io.ltr8.tson.compiler.ast.DataValue value) {
                Object body;
                try {
                    body = vocabulary.instance(type, value, heldByName, false);
                } catch (MissingBindingException unbound) {
                    if (buildsTypes(head, kernel)) {
                        throw unmodelledType(type, unbound);
                    }
                    return new ValueEntry(type, vocabulary.instance(type, value, heldByName, true));
                }
                if (body instanceof Top top) {
                    return top;
                }
                if (buildsTypes(head, kernel)) {
                    throw unmodelledType(type, null);
                }
                return new ValueEntry(type, body);
            }

            @Override
            public Object readValue(String type, io.ltr8.tson.compiler.ast.DataValue value) {
                return vocabulary.value(type, value, names, heldByName);
            }
        };
        HeldRecursion heldRecursion = new HeldRecursion(name -> {
            Top visible = waitOrder.definition(name);
            return visible != null ? visible : heads.definition(name);
        }, declarations::containsKey);
        TemplateMaterialiser materialiser = new TemplateMaterialiser(waitOrder, reads,
                heads::definition, waitOrder::current, facts, applications, kernel, heldRecursion);

        // The same compiled reader serves both hooks; they differ in what the caller does with the result,
        // which is why they are separate types rather than one Object-returning one.
        DefinitionResolver resolver = new DefinitionResolver(reads,
                // An annotation names an ordinary entry, not a constructor (§6), so this goes through the
                // compiled schema's own reader for that name rather than the constructor vocabulary. Its value is
                // as opaque to the linker as a value entry's, so it is read under the policy too.
                (type, value) -> vocabulary.annotation(type, value, names, heldByName),
                heads, waitOrder, new ApplicationCloser() {

                    @Override
                    public String closeApplication(io.ltr8.tson.schema.meta.TypeRef application) {
                        return materialiser.closeApplication(application);
                    }

                    /** An operand absorbed by value gets the same bound check an application closing does. */
                    @Override
                    public void checkArguments(String head, Top template,
                            List<io.ltr8.tson.schema.meta.TypeRef> arguments) {
                        materialiser.checkArguments(head, template, arguments);
                    }

                    /**
                     * A declaration naming a fully-bound application is the entry it denotes (§8.2), so the
                     * application closes here, into the declaration's own name.
                     */
                    @Override
                    public Top closeApplicationInto(String declaredName,
                            io.ltr8.tson.schema.meta.TypeRef application) {
                        return materialiser.closeApplicationInto(declaredName, application);
                    }
                }, positions, facts, kernel, kernel);
        onDemand.resolveWith(resolver);

        waitOrder.readyAll(name -> false);
        // Every entry is ready: the judgements that walk a name to what it names run now (§3.4.1's sixth rule).
        resolver.judgeDeferred((name, error) -> {
            if (!problems.collecting()) {
                throw error;
            }
            if (!onDemand.failed(name)) {
                problems.report(declarations.get(name), error);
            }
        });

        // §5.10 materialisation, after every declaration has resolved and before anything reads the result:
        // a template application reaches here as a type-ref carrying arguments, and closing it needs the
        // template's own *resolved* open form, which only exists once the driving loop above has run. It adds no
        // entry: an application closes in place, or into the declaration naming it.
        Map<String, Top> resolvedLocals = new LinkedHashMap<>();
        for (String name : declarations.keySet()) {
            resolvedLocals.put(name, namespace.get(name));
        }

        // §5.10's regularity boundary, before anything closes: a template that grows its argument on every
        // recursive step has no finite set of types to build, and catching it at the declaration means a
        // broken template is rejected even if nobody ever applies it.
        Set<String> irregular = TemplateRegularity.check(resolvedLocals, problems.collecting()
                ? (name, error) -> problems.report(declarations.get(name), error) : null);

        // A condemned template is replaced before materialisation, on the same terms as a declaration that
        // failed to resolve: the verdict is in, and closing an application of one would never end, an irregular
        // application never repeating to tie or be refused. Both maps, because the two are read by
        // different halves: `materialise` walks `resolvedLocals`, while the application's head is looked up
        // through the getter over `namespace`.
        condemn(irregular, resolvedLocals, namespace);
        // §5.10's parameters, before anything closes, each declaration once: that a written bound names a type, and
        // that a parameter passed to a bounded parameter is written with a bound IS-A it. Here because a callee's
        // bound comes from its declaration, ready only now. A refused template is condemned on the same terms as an
        // irregular one: the verdict is in, and closing an application of it only reports the consequence against
        // whichever entry applied it.
        Set<String> refusedParameters = ParameterTypes.check(namespace, declarations.keySet(), heads::definition,
                (name, error) -> {
                    if (!problems.collecting()) {
                        throw error;
                    }
                    problems.report(declarations.get(name), "'" + name + "': " + error.getMessage(), error);
                });
        condemn(refusedParameters, resolvedLocals, namespace);
        // §5.10.1's held recursion, over the open records as the two checks above left them, each declaration once:
        // one holding where it closes in place an application of a recursive open record whose recursion does not
        // pass back through it could never be closed. Its appliers closed against its placeholder while declarations
        // were made ready, so this is the one report, and condemning it keeps materialisation from a second.
        heldRecursion.forget();
        Set<String> heldRecursive = new LinkedHashSet<>();
        for (String name : declarations.keySet()) {
            heldRecursion.refusal(name).ifPresent(error -> {
                if (!problems.collecting()) {
                    throw error;
                }
                problems.report(declarations.get(name), error);
                heldRecursive.add(name);
            });
        }
        condemn(heldRecursive, resolvedLocals, namespace);
        // The bound checks of the applications closed while declarations were being made ready, which an argument
        // could not order (§3.4.1), run now that every argument is ready -- before anything else closes. A
        // declaration that already failed has its verdict, so a check is not a second one.
        materialiser.checkDeferred((name, error) -> {
            if (!problems.collecting()) {
                throw error;
            }
            if (!onDemand.failed(name)) {
                problems.report(declarations.get(name), error);
            }
        });

        materialiser.materialise(resolvedLocals,
                problems.collecting() ? (name, error) -> problems.report(declarations.get(name), error) : null);
        republish(namespace, resolvedLocals);
        appliedParentEdges(namespace, resolvedLocals, applications);
        closeChains(namespace, resolvedLocals);

        // §6's name-position annotations. Binding one can fail the way a definition's can (an annotation type
        // §3.3.3 cannot reach), and this loop runs outside the memoized getter that catches those -- so it
        // catches its own, once per name, leaving the entry intact and unannotated rather than losing the
        // whole schema to a bad @doc.
        for (String name : declarations.keySet()) {
            try {
                nameAnnotations.put(name, resolver.annotationsFor(name, declarations.get(name).nameAnnotations()));
            } catch (SchemaValidationException | UnsupportedOperationException
                     | BindMismatchException e) {
                if (!problems.collecting()) {
                    throw e;
                }
                problems.report(declarations.get(name), e);
                nameAnnotations.put(name, Annotations.empty());
            }
        }

        // No reference is flattened here, at a use site or anywhere else. An alias is a hop, not a
        // rewrite: resolved output states the chain the author wrote, every use site keeps the name it
        // names, and a processor collapses the chain when it compiles readers -- after linking, where the
        // whole namespace is present and the walk is done once per entry rather than once per output.
        // See design/resolver-vocabulary-and-bootstrap.md and [TSON-SCHEMA] §8.3.

        // §6: an annotation written before the declared name binds to the *name*, not to the value, and "the
        // resolver does not hoist annotations from key to value". A resolved schema is a {type_name => top}, so
        // the name is this map's key -- which is where they go. The two sets stay separate: the annotations
        // written on the value are the entry's facts. Bound above; this only places them.
        AnnotatedMap<String, Top> localOnly = new AnnotatedMap<>();
        for (String name : declarations.keySet()) {
            localOnly.put(name, resolvedLocals.get(name), nameAnnotations.getOrDefault(name, Annotations.empty()));
        }
        // §8.2's derived key annotation: every entry that closes an application of an open record carries
        // `@meta.application` on its key, which is where resolved output states a declared application and where
        // ingest reads it back as stated. A construction carries none: its value states its constructor.
        // The application is stated as written, its nested arguments unreduced.
        for (String name : List.copyOf(localOnly.keySet())) {
            io.ltr8.tson.schema.meta.TypeRef application = materialiser.writtenApplication(name)
                    .orElse(applications.get(name));
            if (application != null) {
                localOnly.put(name, localOnly.get(name),
                        TsonSchema.withApplication(localOnly.getAnnotations(name), application));
            }
        }
        Map<String, List<TsonSchema.ValueByName>> valuesByName = new LinkedHashMap<>();
        Map<String, List<TsonSchema.HeldValue>> heldValues = new LinkedHashMap<>();
        Map<String, TsonSchema.EntryFacts> localFacts = new LinkedHashMap<>();
        localOnly.forEach((name, definition) -> {
            HeldInBody held = heldByName.get(definition instanceof ValueEntry value ? value.value() : definition);
            if (held != null && !held.byName().isEmpty()) {
                valuesByName.put(name, held.byName());
            }
            if (held != null && !held.asWritten().isEmpty() && definition instanceof ValueEntry) {
                heldValues.put(name, held.asWritten());
            }
            TsonSchema.EntryFacts known = facts.get(name);
            if (known != null) {
                localFacts.put(name, known);
            }
        });
        return new TsonSchema(id, document.imports(), localOnly, false, valuesByName, heldValues, localFacts);
    }

    /**
     * One run's diagnostic vocabulary: the canonical schema id, the identity-keyed position table, and the
     * receiver -- everything a phase needs to state a problem, so that no phase states it a sixth way.
     *
     * <p><b>A declaration rather than a name</b> is what {@link #report} takes, because the name alone does
     * not locate anything: a position comes from the declaration node, through the identity-keyed table a
     * rewritten declaration is carried onto. Every caller has the node, and asking for it is what stops the
     * lookup being spelled out at each site.
     *
     * <p><b>The code is chosen by the project's own exception classification</b>, which has four outcomes and not
     * two. A {@code SchemaValidationException} is the author's error and an {@code UnsupportedOperationException}
     * is this library's gap -- one says fix your schema, the other says this could not be checked. A {@link
     * BindMismatchException} is neither: it says the reading application is wired wrong, and it is the one a
     * caller most easily acts on, the message naming one of their own classes. Collapsing it into either of the
     * others is what {@code MissingBindingException} exists to prevent -- read as a library gap, a missing line of
     * configuration is what a downstream service maps to a 501. A {@link SchemaRefusalException} is the author's
     * schema declined under the processor's policy rather than called wrong -- its identifier policy or its limits --
     * and keeps the rule's code.
     *
     * <p>All four are reported per declaration and all four leave a placeholder, so one failing
     * declaration does not cost every other declaration its verdict.
     */
    private record Problems(String schemaId, SchemaPositions positions, DiagnosticsReceiver receiver) {

        /** {@code false} for the fail-fast overloads, whose callers rethrow rather than collect. */
        boolean collecting() {
            return receiver != null;
        }

        void report(SchemaMap.Declaration declaration, RuntimeException error) {
            report(declaration, error.getMessage(), error);
        }

        /** The same, for a caller that has composed its own message rather than taking the exception's. */
        void report(SchemaMap.Declaration declaration, String message, RuntimeException error) {
            Optional<SourcePosition> position = positions.of(declaration);
            receiver.report(switch (error) {
                case BindMismatchException mismatch ->
                        TsonDiagnostics.ofSchemaBindMismatch(schemaId, declaration.name(), mismatch, position);
                case SchemaRefusalException refusal ->
                        TsonDiagnostics.ofSchemaRefusal(schemaId, declaration.name(), refusal.code(), message, position);
                case UnsupportedOperationException ignored ->
                        TsonDiagnostics.ofSchemaGap(schemaId, declaration.name(), message, position);
                default -> TsonDiagnostics.ofSchemaError(schemaId, declaration.name(), message, position);
            });
        }
    }

    /**
     * Replaces each named entry with the placeholder {@link #unresolved} builds, keeping its position and its
     * type parameters -- the same treatment a declaration that failed to resolve gets, applied to a template
     * whose verdict is already in.
     *
     * <p><b>Both maps, because the two halves are read by different phases</b>: {@code materialise} walks the
     * locals, while an application's head is looked up through the getter over the namespace. Leaving one
     * behind would close applications against the condemned template through whichever map was missed.
     */
    private static void condemn(Set<String> names, Map<String, Top> resolvedLocals, Map<String, Top> namespace) {
        for (String name : names) {
            Top placeholder = unresolved(OpenEntry.parameterNames(resolvedLocals.get(name)));
            resolvedLocals.put(name, placeholder);
            namespace.put(name, placeholder);
        }
    }

    /**
     * §5.8's IS-A edge for a <b>parent written as an application</b>, derived once every declaration has
     * resolved, and the chains made complete through it.
     *
     * <p>Composition needs the operand's <em>fields</em> and nothing else, so a closed declaration and a closed
     * template both keep the application in their chain's {@code parents} and absorb it by value (§5.8). The
     * edge is the half that has to wait: the entry an application denotes is whichever declaration names the
     * same application, and asking that <em>during</em> materialisation would make the answer depend on
     * declaration order -- {@code text_box} declared before the composition found, declared after it not. A
     * closed chain is completed once every parent is a type, and this is that moment.
     *
     * <p><b>Nothing is created here.</b> The pass derives an index over entries that already exist, keyed on
     * §8.2's canonical application so two spellings of one application agree -- the application each declared
     * application's key records, an imported one's included. Where no declaration names the application there is no
     * name to record, and that is the right answer rather than a gap: nobody declared that type, so nothing can
     * be declared as it. {@link #closeChains} then drops every application left standing.
     *
     * <p><b>Complete means transitively closed</b> (TypeChain): an entry IS-A every entry its supertypes are
     * IS-A, so a declaration composing {@code okt => ok<text>} reaches the edge {@code okt} gained here, though
     * it holds no application of its own. The closure runs over every local chain, to a fixed point.
     *
     * <p><b>§5.9 needs no guard here.</b> A declaration carrying a removal keeps no parent at all, so a
     * subtracted entry has no applied parent for this pass to find and an empty chain for it to close.
     */
    private static void appliedParentEdges(Map<String, Top> namespace, Map<String, Top> resolvedLocals,
                                           Map<String, io.ltr8.tson.schema.meta.TypeRef> applications) {
        Map<String, String> byApplication = new LinkedHashMap<>();
        namespace.keySet().forEach(entryName -> Optional.ofNullable(applications.get(entryName))
                .filter(application -> !application.arguments().isEmpty())
                .ifPresent(application -> byApplication.putIfAbsent(
                        CanonicalApplication.of(application.name(), application.arguments()),
                        entryName)));
        // <b>To a fixed point</b>, because the edge is transitive and each pass can only see the chains the
        // previous one left. `great => <T> ok<T> & { … }` over `ok => <T> result<T> & { … }` needs `ok_of`'s own
        // edge to `result_of` to exist before `great_of` can inherit it. Bounded by the entry count: each round
        // adds at least one name to some chain or stops.
        for (int round = 0; round <= namespace.size(); round++) {
            boolean changed = fold(resolvedLocals, byApplication, namespace);
            republish(namespace, resolvedLocals);
            if (!changed) {
                return;
            }
        }
    }

    /**
     * Every closed chain with the applications the fold left standing dropped: an application no declaration
     * names denotes an entry nothing can be declared as, so it reaches no edge, and a closed type's parents are
     * not part of it ([TSON-SCHEMA] §4.1). A template keeps its written parents.
     */
    private static void closeChains(Map<String, Top> namespace, Map<String, Top> resolvedLocals) {
        // An open record keeps the parents as written: they may still name its own parameters, and
        // closing each application is what completes that application's chain from them.
        resolvedLocals.replaceAll((name, definition) -> definition instanceof Type type && !type.parents().isEmpty()
                && !OpenEntry.isOpen(definition)
                ? type.withChain(type.chain().withParents(List.of()))
                : definition);
        republish(namespace, resolvedLocals);
    }

    /** One round of {@link #appliedParentEdges} over one map -- {@code true} when any chain grew. */
    private static boolean fold(Map<String, Top> entries, Map<String, String> byApplication,
                                 Map<String, Top> namespace) {
        boolean changedAny = false;
        // <b>To a fixed point within the map, not merely across rounds.</b> A pass visits entries in map
        // order while mutating them, so `great_of` can be reached before `ok_of` has grown the edge it
        // inherits -- and both live in this same map, which an outer round cannot fix. Each inner pass
        // republishes, because `completed` reads an owner's ancestors out of the namespace.
        for (int pass = 0; pass <= entries.size(); pass++) {
            boolean changed = false;
            for (Map.Entry<String, Top> entry : entries.entrySet()) {
                Top folded = completed(entry.getValue(), byApplication, namespace);
                if (folded != entry.getValue()) {
                    entry.setValue(folded);
                    namespace.put(entry.getKey(), folded);
                    changed = true;
                }
            }
            if (!changed) {
                return changedAny;
            }
            changedAny = true;
        }
        return changedAny;
    }

    /**
     * One chain made complete: a type's every applied parent's owning declaration folded in, and for any chain
     * every member's own supertypes. The application stays in {@code parents} until {@link #closeChains}, so a
     * later round still finds it when the owner's chain has grown. {@code definition} itself where nothing
     * grew.
     */
    private static Top completed(Top definition, Map<String, String> byApplication, Map<String, Top> namespace) {
        if (!(definition instanceof Type type)) {
            return definition;
        }
        Set<String> chain = new LinkedHashSet<>(type.supertypes());
        for (io.ltr8.tson.schema.meta.TypeRef parent : type.parents()) {
            if (parent.arguments().isEmpty()) {
                continue;
            }
            // A parent written through an alias of its open record is that open record's application (§8.3).
            String head = Ancestry.templateHead(parent.name(), namespace::get);
            String owner = byApplication.get(CanonicalApplication.of(head, parent.arguments()));
            if (owner != null) {
                chain.add(owner);
            }
        }
        for (String member : List.copyOf(chain)) {
            chain.addAll(Ancestry.closedNames(namespace.get(member)));
        }
        return chain.size() == type.supertypes().size() ? definition
                : TypeChain.withChain(definition, type.chain().withSupertypes(List.copyOf(chain)));
    }

    /**
     * Puts the local entries back into the namespace, which every later phase and the on-demand getter both read
     * through. Called after each pass that rewrites them: the namespace also holds the imported entries, so it
     * cannot simply be replaced.
     */
    private static void republish(Map<String, Top> namespace, Map<String, Top> resolvedLocals) {
        namespace.putAll(resolvedLocals);
    }

    /**
     * How one declaration is made ready in this resolution's wait order ({@link WaitOrder}), and what a failure
     * leaves.
     *
     * <p><b>A failure is caught here, inside the order, rather than around the driving loop.</b> Resolution
     * follows waits rather than source order, so a failure often happens inside a nested resolve -- the loop would
     * attribute it to whichever declaration triggered it, then reach the real one and report it a second time. The
     * order makes it exactly once, against itself, and leaves a placeholder ({@link #unresolved}) for its dependents.
     * Same shape as {@code TsonSchemaCompiler.Compilation.resolve} substituting an {@code ErrorReader}.
     *
     * <p><b>The resolver arrives after construction</b> ({@link #resolveWith}) because the two are mutually
     * constructed: a {@link DefinitionResolver} looks up through the order, and the order resolves through it.
     */
    private static final class OnDemand implements WaitOrder.Readiness {

        private final Map<String, SchemaMap.Declaration> declarations;
        private final Set<String> imported;
        private final Problems problems;
        private final Map<String, TsonSchema.EntryFacts> facts;

        /** The declarations whose resolution failed and was reported. */
        private final Set<String> failed = new LinkedHashSet<>();

        private DefinitionResolver resolver;

        OnDemand(Map<String, SchemaMap.Declaration> declarations, Set<String> imported,
                 Problems problems, Map<String, TsonSchema.EntryFacts> facts) {
            this.declarations = declarations;
            this.imported = imported;
            this.problems = problems;
            this.facts = facts;
        }

        /** Whether {@code name}'s resolution failed and was reported. */
        boolean failed(String name) {
            return failed.contains(name);
        }

        /** Supplies the resolver this readiness drives; called once, immediately after it is built. */
        void resolveWith(DefinitionResolver resolver) {
            this.resolver = Objects.requireNonNull(resolver, "resolver");
        }

        @Override
        public Top resolve(String name, SchemaMap.Declaration declaration) {
            refuseShadowingParameters(name, SchemaDesugarer.typeParams(declaration.typeDef()));
            Top resolved = resolver.resolve(declaration, problems.positions().of(declaration));
            refuseHeadAbstraction(name, resolved);
            return resolved;
        }

        /**
         * [TSON-SCHEMA] §5.10: within a template's body a parameter's name is read before the schema's, so a
         * parameter spelled like an entry the schema's namespace holds -- a type or a value entry, its own or an
         * import's -- makes that entry unreachable there, and is a resolver error at the declaration rather than a
         * silent capture. Judged by the name alone, before anything resolves, so no entry is waited on.
         */
        private void refuseShadowingParameters(String name, List<String> parameters) {
            for (String parameter : parameters) {
                if (declarations.containsKey(parameter) || imported.contains(parameter)) {
                    throw new SchemaValidationException("'" + name + "': parameter '" + parameter + "' shadows the "
                            + "entry '" + parameter + "' this schema's namespace holds -- inside a template a "
                            + "parameter's name is read before the schema's, so the entry would be unreachable there "
                            + "(§5.10). Rename the parameter");
                }
            }
        }

        @Override
        public Top failed(String name, SchemaMap.Declaration declaration, RuntimeException error) {
            if (!(error instanceof SchemaValidationException || error instanceof UnsupportedOperationException
                    || error instanceof BindMismatchException) || !problems.collecting()) {
                throw error;
            }
            problems.report(declaration, error);
            failed.add(name);
            facts.put(name, TsonSchema.EntryFacts.at(problems.positions().of(declaration)));
            return unresolved(SchemaDesugarer.typeParams(declaration.typeDef()));
        }
    }

    /**
     * The entries of this document ready so far, as a schema of its own over the same imports: what the readers a
     * value read against one of them comes from are compiled over.
     */
    private static TsonSchema readySoFar(SchemaDocument document, Map<String, Top> namespace,
                                         Map<String, String> origins, Map<String, TsonSchema.EntryFacts> facts) {
        Map<String, Top> ready = new LinkedHashMap<>();
        Map<String, TsonSchema.EntryFacts> readyFacts = new LinkedHashMap<>();
        namespace.forEach((name, definition) -> {
            if (!origins.containsKey(name)) {
                ready.put(name, definition);
                readyFacts.put(name, facts.getOrDefault(name, TsonSchema.EntryFacts.NONE));
            }
        });
        return new TsonSchema(document.id().orElseThrow(), document.imports(), AnnotatedMap.of(ready), false,
                Map.of(), readyFacts);
    }

    /** Whether {@code head}'s instances are types: it composes the kernel's {@code type} ([TSON-SCHEMA] §4.1). */
    private static boolean buildsTypes(Top head, Predicate<String> kernel) {
        return head != null && TypeChain.supertypesOf(head).stream().anyMatch(
                name -> name.equals(KernelNames.of("type")) && kernel.test(name));
    }

    /**
     * An instance of {@code type}, a constructor of types the kernel and the bundled libraries do not declare: an entry
     * it builds is a type, a type's body is one of the kernel's, and this library has no factory for the bodies it
     * describes.
     */
    private static UnsupportedOperationException unmodelledType(String type, RuntimeException cause) {
        return new UnsupportedOperationException("'!" + type + "' builds types, and this library models the body of "
                + "a type built only by the bundled schemas' constructors -- an instance of a constructor of "
                + "types no reader factory backs cannot be held as a type", cause);
    }

    /**
     * §5.10 admits no head abstraction: a type parameter stands for a type, never for a template, so
     * {@code <T> { v: T<text> }} is no form. Refused here, over every application the open record writes,
     * because here is the last point at which the author's own spelling is still what fails.
     *
     * <p><b>It cannot wait for the linker</b>, which checks the rest of §5.10's arity rule off the same
     * accessor: materialisation runs first, and by then the parameter has been substituted away. What
     * arrives at the {@code meta.type_name} head is then whatever bound it, reported as an arity error against a
     * type the author passed as an argument and never applied, which names nothing the author did.
     */
    private static void refuseHeadAbstraction(String name, Top resolved) {
        if (!OpenEntry.isOpen(resolved)) {
            return;
        }
        List<String> parameters = OpenEntry.parameterNames(resolved);
        for (io.ltr8.tson.schema.meta.TypeRef application : OpenEntry.applications(resolved)) {
            if (parameters.contains(application.name())) {
                throw new SchemaValidationException("'" + name + "': '" + application.name()
                        + "' is a type parameter applied to arguments -- a parameter stands for a type, never "
                        + "for a template, and §5.10 admits no head abstraction, so '" + application.name()
                        + "<...>' is no form. Take the applied type as the parameter instead");
            }
        }
    }

    /**
     * The placeholder a declaration that failed to resolve leaves behind, so the declarations that reference
     * it -- and the ones merely queued after it -- still resolve instead of collapsing into a cascade of
     * consequences of one original error. The counterpart of {@code TsonSchemaCompiler}'s {@link
     * io.ltr8.tson.compiler.reader.ErrorReader} one phase later.
     *
     * <p><b>Producing one means a diagnostic has already been reported</b> (Swift's {@code ErrorType}
     * obligation). It is not a resolution and must never be linked, registered or compiled -- guaranteed
     * structurally rather than by inspection, because the only overload that can produce one ({@link
     * #resolveSchema(SchemaDocument, SchemaPositions, DiagnosticsReceiver)}) hands the caller a receiver whose report
     * count is the signal to stop at the phase boundary. Its facts keep the failed declaration's own position so
     * anything that does surface it can still point at the source.
     *
     * <p><b>It keeps the failed declaration's own type parameters.</b> Answering "how many type parameters?"
     * with zero is answering wrongly, not absorbing: a downstream {@code bl<text>} is then told that
     * {@code bl} "declares no type parameters ... drop the argument list", which is advice that would break
     * the schema further, the actual fix being upstream at the declaration that failed. With the arity
     * intact the application closes against an empty body and says nothing.
     *
     * <p><b>An empty record (an empty open record, for an open declaration), because the point is to absorb
     * rather than to be recognised</b> -- javac's model,
     * where the error type answers every question, rather than Swift's, where every questioner must first ask
     * whether it is looking at one. A dependent that composes with a failed declaration ({@code parent =>
     * child & { ... }}) then resolves cleanly, contributing no fields, instead of failing a second time and
     * reporting a problem that is purely a consequence of the first. Getting this wrong is not a small
     * mismatch: a `Sum`-bodied placeholder makes every dependent report too, which is the cascade the
     * placeholder exists to prevent.
     */
    static Top unresolved(List<String> parameters) {
        // An open placeholder is an empty open record, so it closes by the one process a template does.
        return parameters.isEmpty() ? RecordBody.of(RecordBody.fieldMap())
                : new RecordBody(TypeChain.EMPTY, parameters.stream().map(Param::new).toList(), new AnnotatedMap<>(),
                        List.of(), RecordExtensionType.OPEN, List.of(), Optional.empty());
    }

    /**
     * One already-resolved {@code DataValue} replayed through a compiled reader, judging names under {@code names},
     * as a read of a schema document's value ({@link TsonReadContext#readsSchemaDocument()}): a bare name at a
     * record position is a value by name here and nowhere else. The references the read produced by name, and the
     * dependent fields' values it held as written, are put in {@code heldByName} under the body it returns, for the
     * schema to carry to the linker.
     */
    private static Object read(TsonTypeReader<?> reader, io.ltr8.tson.compiler.ast.DataValue value,
                               IdentifierPolicy names, Map<Object, HeldInBody> heldByName,
                               boolean valueBody) {
        return read(reader, value, names, heldByName, valueBody, null);
    }

    /**
     * The same, telling {@code waits} each declaration the read reaches, so the entry it is read for waits on each
     * entry of this document its parts are read against ({@link WaitOrder#reached}).
     */
    private static Object read(TsonTypeReader<?> reader, io.ltr8.tson.compiler.ast.DataValue value,
                               IdentifierPolicy names, Map<Object, HeldInBody> heldByName,
                               boolean valueBody, WaitOrder waits) {
        List<TsonSchema.ValueByName> held = new ArrayList<>();
        List<TsonSchema.HeldValue> asWritten = new ArrayList<>();
        Object body = reader.read(TsonReadContext.schemaDocument(new ListEventSource(DataValueEvents.of(value)), names,
                new TsonReadContext.HeldByName() {

                    @Override
                    public void held(List<String> slot, TsonSchema.SlotType type,
                                     io.ltr8.tson.schema.meta.TypeRef reference,
                                     io.ltr8.tson.schema.meta.Ref<?> cell) {
                        held.add(new TsonSchema.ValueByName(slot, type, reference, Optional.ofNullable(cell)));
                    }

                    @Override
                    public void heldAsWritten(TsonSchema.HeldValue value) {
                        asWritten.add(value);
                    }

                    @Override
                    public void reached(SchemaLocation declaration) {
                        if (waits != null) {
                            waits.reached(declaration);
                        }
                    }
                }, valueBody));
        if ((!held.isEmpty() || !asWritten.isEmpty()) && body != null) {
            heldByName.put(body, new HeldInBody(List.copyOf(held), List.copyOf(asWritten)));
        }
        return body;
    }

    /**
     * What one read of a schema document's value hands the linker: the references it read by name, and the dependent
     * fields' values it held as written.
     */
    private record HeldInBody(List<TsonSchema.ValueByName> byName, List<TsonSchema.HeldValue> asWritten) {
    }

    /**
     * The compiled readers one schema's resolution reads through. For an imported entry, the compiled form of the
     * schema that declared it ({@link TsonCompiledSchemaLoader#vocabularyOf}): one namespace per schema (§3.3,
     * §3.3.1) puts every construction head and annotation type in the closure, and the closure is the
     * union of the imported schemas' own, so an entry's reader, compiled over its declaring schema's closure, reads
     * everything its body can name. Nothing is compiled per closure for those -- each declaring schema is compiled
     * once and cached by identity, and a schema that applies only the kernel's constructors reaches exactly the
     * kernel's.
     *
     * <p>For an entry of this document, readers compiled from the entries ready so far ({@link WaitOrder#read}): the
     * entry is made ready before the value read against it, and a read reaching another not yet ready makes that one
     * ready first. The kernel's own resolution reads through its pre-loaded stand-in for every name, its one
     * cycle being closed by pre-loading (§3.4).
     */
    private final class Vocabulary {

        /** The kernel's pre-loaded stand-in, answering for every name; {@code null} for every other schema. */
        private final TsonCompiledVocabulary self;
        private final Map<String, Top> imported;
        private final Map<String, String> origins;

        /** This document's entries made ready in wait order; {@code null} for the kernel, which reads through itself. */
        private final WaitOrder local;

        Vocabulary(TsonCompiledVocabulary self) {
            this.self = self;
            this.imported = self.schema().entries().entries();
            this.origins = Map.of();
            this.local = null;
        }

        /** Over the imported entries, which this resolution adds no local entry to, and this document's own. */
        Vocabulary(Map<String, Top> namespace, Map<String, String> origins, WaitOrder local) {
            this.self = null;
            this.imported = Map.copyOf(namespace);
            this.origins = Map.copyOf(origins);
            this.local = local;
        }

        /** Whether {@code name} is read through this document's own entries rather than an import's compile. */
        private boolean isLocal(String name) {
            return local != null && !origins.containsKey(name);
        }

        /** The compiled schema that declared {@code name}, an imported entry. */
        private TsonCompiledVocabulary of(String name) {
            if (self != null) {
                return self;
            }
            String origin = origins.get(name);
            if (origin == null) {
                throw new IllegalStateException("'" + name + "' is read against as an import, and no imported schema "
                        + "declares it");
            }
            return loader.vocabularyOf(origin);
        }

        /** An imported entry, or {@code null}: where the kernel's own resolution resolves a head. */
        Top definition(String name) {
            return imported.get(name);
        }

        /** The constructor an imported entry's value applies, as its declaring schema recorded it. */
        Optional<String> constructorOf(String name) {
            return self != null ? self.schema().constructorOf(name)
                    : Optional.ofNullable(imported.get(name)).map(TsonSchema::constructorOf);
        }

        /**
         * An instance of the constructor {@code name}, {@code !name { ... }}: its binding record read under no policy,
         * in object-binding mode, or in tree mode where {@code tree} is true.
         */
        Object instance(String name, io.ltr8.tson.compiler.ast.DataValue value,
                        Map<Object, HeldInBody> heldByName, boolean tree) {
            if (isLocal(name)) {
                return local.read(name, tree,
                        readers -> read(readers.get(name), value, IdentifierPolicy.none(), heldByName, false, local));
            }
            TsonTypeReader<?> reader = tree ? loader.treeVocabularyOf(origins.get(name)).get(name)
                    : of(name).reader(name);
            return read(reader, value, IdentifierPolicy.none(), heldByName, false);
        }

        /** An annotation's value, read against the type {@code name} under {@code names}. */
        Object annotation(String name, io.ltr8.tson.compiler.ast.DataValue value, IdentifierPolicy names,
                          Map<Object, HeldInBody> heldByName) {
            if (isLocal(name)) {
                return local.read(name, readers -> read(readers.get(name), value, names, heldByName, false, local));
            }
            return read(of(name).get(name), value, names, heldByName, false);
        }

        /**
         * A value entry's payload, {@code name => !type { ... }}, read against the type {@code type} as the value it
         * is: bound by the bind context where a class binds the type, and held as a tree where none does, through a
         * tree-mode compilation, so a schema holding it loads and validates without host classes. A value of a
         * namespace type is delivered as the {@link io.ltr8.tson.schema.meta.Namespace} it is.
         *
         * <p><b>A value is a payload the linker cannot see into</b> -- its fields' types are left behind in the
         * declaring schema -- so its keys and its identifier-typed values, a key of an identifier-keyed map among
         * them, are judged here under {@code names}, at the read, as the same values are in a data document. Nothing
         * in one is closed ({@link TsonReadContext#readsValueBody()}), so a kind-typed slot holds a bare name. A
         * constructor's payload builds the schema layer's own vocabulary, whose names [TSON-SCHEMA] §11.4's scopes
         * cover and {@code TsonSchemaLinker} judges, and is read under no policy.
         */
        Object value(String type, io.ltr8.tson.compiler.ast.DataValue value, IdentifierPolicy names,
                     Map<Object, HeldInBody> heldByName) {
            if (isLocal(type)) {
                try {
                    return local.read(type,
                            readers -> read(asValue(readers, type), value, names, heldByName, true, local));
                } catch (MissingBindingException unbound) {
                    return local.read(type, true,
                            readers -> read(asValue(readers, type), value, names, heldByName, true, local));
                }
            }
            try {
                return read(asValue(of(type), type), value, names, heldByName, true);
            } catch (MissingBindingException unbound) {
                return read(asValue(loader.treeVocabularyOf(origins.get(type)), type), value, names, heldByName, true);
            }
        }

        /** The reader for a value of {@code type}, a namespace type's delivering the {@code Namespace} it is. */
        private static TsonTypeReader<?> asValue(TsonCompiledSchema compiled, String type) {
            TsonTypeReader<?> reader = compiled.get(type);
            return compiled.schema().entries().get(type) instanceof NamespaceBody
                    ? new NamespaceInstanceReader(type, reader) : reader;
        }
    }

    /**
     * Stage 1 of {@link #resolveSchema(SchemaDocument)} -- the whole namespace of every {@code !!import} ({@link
     * TsonSchema#namespaceSources}), in header order, merged as-is (never re-resolved
     * against the importer). Mirrors {@code TsonSchemaLinker.mergeImports} exactly, including its transitivity and
     * its identity-based collision rule (§2.2.3), since this is the same concept applied one stage earlier: an
     * import contributes everything its namespace holds, one schema reached by
     * several routes unifies, and two different schemas declaring one name is the error.
     *
     * <p>An entry reached by several routes is kept as it first arrived, as the linker's merge keeps it. Every
     * import is already linked, so each route's copy carries the {@code subtypes} that route's own link credited
     * over its own closure; the first copy's are kept and none is reconciled. Each entry's facts travel with it into
     * {@code facts}, so the tag of a constructor's instance another schema declared is found here as a local one's is.
     */
    private Map<String, Top> mergeImports(SchemaDocument document,
            Map<String, Annotations> importedNameAnnotations, Map<String, TsonSchema.EntryFacts> facts,
            Set<String> kernelNames, Map<String, String> origins) {
        Map<String, Top> merged = new LinkedHashMap<>();
        Set<String> alreadyImported = new LinkedHashSet<>();
        for (String importUri : namespaceSources(document)) {
            if (!alreadyImported.add(CanonicalIdentity.canonicalize(importUri))) {
                continue;
            }
            TsonLinkedSchema imported = loader.resolveLinked(importUri);
            for (Map.Entry<String, Top> entry : imported.schema().entries().entrySet()) {
                String name = entry.getKey();
                String origin = imported.originOf(name);
                String incumbent = origins.get(name);
                // A schema map of one path declared by several documents is one map, the union of their members,
                // each merged under its path; the map's entry is derived from them and carries no annotations of
                // its own.
                if (incumbent != null && SchemaPaths.isNestedMap(merged.get(name))
                        && SchemaPaths.isNestedMap(entry.getValue())) {
                    continue;
                }
                if (incumbent != null) {
                    if (!incumbent.equals(origin)) {
                        throw new SchemaValidationException("'" + name + "' is declared by two different "
                                + "schemas reached through !!import ('" + incumbent + "' and '" + origin
                                + "') -- distinct types cannot share one name in the flat namespace; import "
                                + "one of them, or a version of each that agrees on where '" + name
                                + "' is declared");
                    }
                    continue;
                }
                merged.put(name, entry.getValue());
                facts.put(name, imported.schema().factsOf(name));
                if (imported.isKernel(name)) {
                    kernelNames.add(name);
                }
                // §8.3's walk carries a dropped hop's declaration annotations to the use site, and an alias
                // is as often imported as local -- so the key annotations travel with the entry rather than
                // being dropped at the import boundary, where the hop would lose them for a second reason. A
                // schema map's are its declaring document's contribution, and travel nowhere.
                if (!SchemaPaths.isNestedMap(entry.getValue())) {
                    importedNameAnnotations.put(name, imported.schema().entries().getAnnotations(name));
                }
                origins.put(name, origin);
            }
        }
        return merged;
    }

    /** {@link TsonSchema#namespaceSources} for the document before it is resolved: the same list, from its header. */
    private static List<String> namespaceSources(SchemaDocument document) {
        return new TsonSchema(document.id().orElseThrow(), document.imports(), Map.of())
                .namespaceSources();
    }
}
