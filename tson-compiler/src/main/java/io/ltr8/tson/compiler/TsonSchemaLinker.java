package io.ltr8.tson.compiler;

import io.ltr8.tson.schema.*;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.AtomParsers;
import io.ltr8.tson.compiler.atom.AtomType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.reader.EntryDisplayName;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.BinaryType;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.Cidr4Type;
import io.ltr8.tson.schema.meta.Cidr6Type;
import io.ltr8.tson.schema.meta.ComplexType;
import io.ltr8.tson.schema.meta.DateTimeType;
import io.ltr8.tson.schema.meta.DateType;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.DurationType;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.Data;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.Ipv4Type;
import io.ltr8.tson.schema.meta.Ipv6Type;
import io.ltr8.tson.schema.meta.MacType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UnknownType;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a resolved-but-unlinked {@link TsonSchema} into a {@link TsonLinkedSchema} -- pass 2, which a schema
 * goes through before {@code TsonSchemaRegistry#register} will accept it. Named for the compiler stage it
 * corresponds to in the pipeline (parse -&gt; desugar -&gt; resolve -&gt; <b>link</b> -&gt; register -&gt;
 * compile -&gt; read): it merges every {@code !!import}'s entries into one namespace, populates {@code
 * TypeDefinition.subtypes} (the reverse of {@code supertypes}, for *this schema's own merged view* of every
 * entry it can see, imported or local -- see {@link #computeSubtypes}'s own Javadoc for why crediting a
 * subtype onto an imported entry's copy here never touches the imported schema's own separately-registered
 * original), derives choice disjointness, and checks that every reference anywhere in the schema actually
 * resolves. A {@link TsonLinkedSchema} is the compile-time proof that all of that ran.
 *
 * <p>Public, and meant to be called directly by anything orchestrating the pipeline -- the registries here
 * link a schema before registering it, same as any other caller. Identities are canonicalized through
 * {@link TsonCanonicalIdentity}, which stays in {@code tson-schema} beside the registry that keys on it.
 *
 * <p><b>No materialization.</b> An argument-bearing {@code type_ref} does not become a synthesized entry
 * here -- {@code SchemaDesugarer} has already turned every sugar form and generic application into a real
 * declaration plus a bare reference, one phase earlier, where the AST and the governing meta's own
 * constructor vocabulary are both still in hand. That is what lets it bind the constructor generically and
 * so cover every shape, rather than the per-shape assemblers a linker-side materialization would need. What
 * survives into an entry the linker sees is therefore a bare name in every position except one: a
 * parameterized declaration's own body, which legitimately references its own parameters ({@code array<T>})
 * and is validated, not rewritten.
 *
 * <p><b>Type-parameter exception:</b> a bare name is valid if it resolves in the schema's own
 * namespace, or if it's one of the checked entry's own declared {@code parameters} -- load-bearing
 * for every declaration that takes type parameters -- a user template ({@code box => <T> { v: T }}) and
 * the {@code instance_template} forms lifted from one -- whose own {@code source}/body positions reference
 * their own type parameter by bare name, not a real other entry.
 *
 * <p><b>{@code !!import} merging (Part 2 §2.2.3).</b> The final namespace a schema is checked
 * against is built in two stages, in this order: (1) every {@code !!import}'s whole namespace, in
 * declaration order, looked up via {@code loader} by canonical identity -- <b>transitive, not shallow</b>:
 * {@code loader} hands back an already-registered, already-flattened {@code TsonSchema}, and all of its
 * {@code entries()} are taken, so an import contributes its own imports' entries too; (2) this schema's own
 * entries, exactly as resolved. §2.2.3 requires exactly this: "an {@code !!import} contributes the imported
 * schema's entire namespace -- the entries it declares and the entries it imported", matching the {@code
 * !!meta} half §3.3.1 already defined as "the target's local declarations <i>plus its imports</i>". A flat
 * namespace with no hiding is the rule the rest of the format is built on.
 *
 * <p><b>Collisions are decided by entry identity.</b> One schema reached by several routes unifies; two
 * *different* schemas declaring one name is the error, as is a local declaration shadowing any name the
 * import closure already binds (no redefinition -- see {@link #mergeImports}). <b>Merged entries keep
 * their home namespace</b>: an imported {@code TypeDefinition} is carried in exactly as the imported schema
 * resolved it, never re-validated against the importer's own namespace -- only the *importer's own* new
 * material gets validated here.
 */
public final class TsonSchemaLinker {

    /** meta.tn's {@code void}-targeted marker (§5.4), written bare -- presence is the assertion. */
    private static final String DISJOINT = "disjoint";

    private TsonSchemaLinker() {
    }

    /**
     * Links {@code bootstrap} -- meta-kernel's own raw, pre-loaded bootstrap output (see {@link
     * TsonSchema#bootstrap()}'s own Javadoc for why it can't be resolved the ordinary way). It lives here
     * rather than on {@link TsonSchemaRegistry} because it belongs with the verb it performs: linking, not
     * storage -- and this is a link whose result the registry deliberately never stores (see below).
     *
     * <p>Takes no {@link TsonSchemaLoader} -- unlike {@link #link}, which always needs one for
     * {@code !!import}/{@code !!meta} lookups -- because meta-kernel's own document, the only real
     * caller of this method, never has any {@code !!import}s: it's the base of the whole governing
     * chain, nothing above it to import from. {@link #link}'s own {@code loader == null} handling
     * (an empty structure namespace) already covers the one lookup {@link #link} might otherwise
     * attempt, and {@link #mergeImports} is only ever reached for a non-empty {@code imports()}
     * list, which meta-kernel's own document never has -- so passing {@code null} through is safe
     * for this specific, guaranteed-narrow case, not a general shortcut.
     *
     * <p>Does <b>not</b> store the result under a persistent identity anywhere, and {@code
     * TsonSchemaRegistry#register} refuses it outright regardless (see that class's own Javadoc).
     * Exists purely so a caller (e.g. building an object-binding-mode {@code
     * TsonParserFactoryRegistry}, which needs a genuinely linked {@code TsonSchema} to validate
     * against up front) can get a usable result straight from the raw bootstrap object, without
     * separately wiring a {@link TsonSchemaLoader} or a registry at all.
     *
     * @throws TsonSchemaValidationException if {@code bootstrap.bootstrap()} is {@code false} -- this
     *                                        method exists specifically for the one self-referential
     *                                        schema, not as a general "link without registering"
     *                                        escape hatch for ordinary schemas (call {@link #link}
     *                                        directly for that)
     */
    public static TsonLinkedSchema linkBootstrap(TsonSchema bootstrap) {
        if (!bootstrap.bootstrap()) {
            throw new TsonSchemaValidationException("'" + bootstrap.id() + "' was not produced by the real "
                    + "bootstrap reader (MetaKernelBootstrapResolver.getMetaKernelSchema()) -- "
                    + "TsonSchemaLinker.linkBootstrap exists specifically for that case; call "
                    + "link directly for an ordinary schema instead");
        }
        return linkWith(bootstrap, null, null);
    }

    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader) {
        return linkWith(schema, loader, null);
    }

    /**
     * {@link #link(TsonSchema, TsonSchemaLoader)} reporting every entry that fails validation through {@code
     * receiver} instead of throwing at the first ([TSON-DATA] §8.1: implementations SHOULD "continue
     * processing after an error to report multiple issues in a single pass"). Every entry is checked, so a
     * schema with two unresolved references reports two.
     *
     * <p><b>The result is only trustworthy if nothing was reported</b> -- same phase-boundary contract as
     * {@code SchemaResolver}'s own reporting overload. A {@link TsonLinkedSchema} whose linking produced
     * diagnostics is not a proof that linking succeeded; the caller checks the receiver and stops rather than
     * registering or compiling it.
     *
     * <p><b>What still throws:</b> anything that makes the namespace itself unusable rather than making one
     * entry wrong -- an {@code !!import} that will not load, or a {@code !!meta} that may not govern. Carrying
     * on past those would report a page of unresolved references that are all consequences of the one real
     * problem, which is the cascade this reporting is meant to avoid, not an example of it.
     *
     * @param receiver where a failing entry is reported; must not be {@code null}
     */
    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader,
                                        TsonDiagnosticsReceiver receiver) {
        Objects.requireNonNull(receiver, "receiver");
        return linkWith(schema, loader, receiver);
    }

    /**
     * Reports {@code message} against one entry, or throws it when there is no receiver.
     *
     * @return {@code true} if the caller should carry on as though the check had passed -- always, since a
     *         throw is the only other outcome. Reads as a guard at the call sites that want to skip the entry.
     */
    private static boolean report(TsonDiagnosticsReceiver receiver, TsonSchema schema, String name,
                                  TypeDefinition def, String message) {
        if (receiver == null) {
            throw new TsonSchemaValidationException(message);
        }
        receiver.report(schemaError(schema, name, def, message));
        return false;
    }

    /**
     * The declaration a failure is reported against: the entry itself when the author wrote it, and otherwise
     * the nearest one that references it and has a line of its own.
     *
     * <p><b>A derived entry has no line, and reporting one against itself leaves nothing to edit.</b>
     * {@code use => { u: [some_typo] }} lifts an {@code array_some_typo_95c9a10f} whose {@code element_type}
     * does not resolve -- a real error, whose diagnostic named an entry the author never wrote and carried no
     * position at all, while the same mistake spelled {@code u: some_typo} landed on {@code /use} with its
     * line. Walking back to the first referrer that has a position puts the two on the same footing. It is
     * the form that is named in the message ({@code EntryDisplayName}) and the declaration that is pointed at.
     *
     * <p>Runs only when something has already failed, so the scan costs nothing on a clean link. Follows
     * references rather than {@code source}, since a lifted form's {@code source} is the bare constructor it
     * applies and leads away from the author rather than back to them.
     *
     * <p><b>This answers "who reached it", which is the right question only when the defect is in what the
     * author reached it <em>with</em>.</b> A defect a held body deferred is not: it is in the template's own
     * text, and every applier reaches that equally. {@link #heldDeclarationNaming} answers that one from the
     * offending name and runs first; this is the fallback, and stays the whole answer for a sugar lift, for
     * an argument the applier wrote, and for every defect no template deferred.
     */
    private static String reportedAgainst(String name, Map<String, TypeDefinition> entries) {
        Set<String> seen = new LinkedHashSet<>();
        String current = name;
        while (seen.add(current)) {
            TypeDefinition definition = entries.get(current);
            if (definition == null || definition.position().isPresent()) {
                return current;
            }
            String referrer = firstReferrerOf(current, entries);
            if (referrer == null) {
                return name; // nothing points at it: its own name is the best that can be said
            }
            current = referrer;
        }
        return name;
    }

    /**
     * A reference resolving to nothing, carried in <b>parts</b> rather than as a finished sentence.
     *
     * <p>A held body defers every question about what its references resolve to, so an unresolved one
     * surfaces on the entry materialisation minted -- {@code box<int32>} -- when the name was written in the
     * template's own text. The two statements of that one fact differ in the subject alone, so the subject
     * is supplied at the point the fact is stated rather than baked in at the point it is discovered.
     *
     * <p><b>Linker-internal, and never escapes.</b> It is caught in the one place it is thrown from and
     * re-stated as a {@link TsonSchemaValidationException}, whose classification this shares: an unresolved
     * reference is the author's error, and a verdict this library will not change its mind about. It is not
     * a subclass of it because that type is deliberately {@code final} and lives in {@code tson-schema},
     * which holds no pipeline machinery.
     */
    private static final class UnresolvedReference extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String trail;
        private final String name;

        UnresolvedReference(String subject, String trail, String name) {
            super(sentence(subject, trail, name));
            this.trail = trail;
            this.name = name;
        }

        /** The name that resolved to nothing -- what decides which declaration wrote it. */
        String name() {
            return name;
        }

        /** The same fact stated against a different declaration. */
        String against(String subject) {
            return sentence(subject, trail, name);
        }

        private static String sentence(String subject, String trail, String name) {
            return "'" + subject + "'" + trail + " has an unresolved reference '" + name + "'";
        }
    }

    /**
     * The declaration a <b>deferred</b> defect belongs to: the open declaration whose held text wrote the
     * name, when the entry that failed is one this resolver derived.
     *
     * <p><b>Holding is what makes this necessary.</b> A closed declaration's references are checked where the
     * author wrote them. A template's are not -- nothing about them can be settled until an application
     * supplies arguments -- so {@code box => <T> { v: T  w: no_such_type }} gets no verdict at its own
     * declaration and one verdict per applier instead, each against a line that is not wrong and does not
     * contain the name. Deferred checking is what holding buys, and it is survivable only if the author is
     * sent to the line they can edit.
     *
     * <p><b>The offending name is the evidence, not the entry.</b> Walking the derived entry's own lineage
     * cannot answer this: a sugar lift's {@code source} is the bare constructor it applies, and an alias
     * composes its argument into someone else's application ({@code half => <B> pair<no_such_type, B>}
     * closes to a {@code pair} instantiation, and {@code pair} is not at fault). Asking instead which held
     * body <em>mentions the name</em> reaches {@code half} directly, and reaches nothing at all when the
     * name came from an argument the applier wrote -- {@code box<3>} and {@code box<some_typo>} keep their
     * existing verdict at the application, which is where those two mistakes are.
     *
     * <p><b>Both filters are load-bearing.</b> Only a derived entry is retargeted, or a closed declaration's
     * own typo would be blamed on any template that happens to name it; and only a {@link TemplateBody}
     * declaration is a candidate, since a defect no held body deferred is already located correctly.
     * {@link TemplateBody#names()} cannot tell a type reference from a field name, which is why it is asked
     * only about a name already known to resolve to nothing -- a field of that name is then the one
     * remaining way to mislead it, and it misleads no worse than naming the applier does.
     *
     * @return the declaration to blame, or {@code null} to leave the failure where it surfaced
     */
    private static String heldDeclarationNaming(String name, TypeDefinition failed,
                                                 Map<String, TypeDefinition> entries) {
        if (failed.position().isPresent()) {
            return null; // the author's own declaration: it already names the line they wrote
        }
        for (Map.Entry<String, TypeDefinition> candidate : entries.entrySet()) {
            TypeDefinition definition = candidate.getValue();
            if (definition.position().isPresent() && definition.body() instanceof TemplateBody held
                    && held.names().contains(name)) {
                return candidate.getKey();
            }
        }
        return null;
    }

    /** One entry's failure, reported when there is a receiver and rethrown as a schema error when there is not. */
    private static void reportOrThrow(TsonDiagnosticsReceiver receiver, TsonSchema schema, String at,
                                       Map<String, TypeDefinition> entries, String message, RuntimeException cause) {
        if (receiver == null) {
            if (cause instanceof TsonSchemaValidationException original && message.equals(cause.getMessage())) {
                throw original; // untouched, which is the fail-fast overloads' standing contract
            }
            throw new TsonSchemaValidationException(message, cause);
        }
        receiver.report(schemaError(schema, at, entries.get(at), message));
    }

    /** The first entry whose own body or source names {@code target} -- insertion order, so it is stable. */
    private static String firstReferrerOf(String target, Map<String, TypeDefinition> entries) {
        for (Map.Entry<String, TypeDefinition> candidate : entries.entrySet()) {
            if (candidate.getKey().equals(target)) {
                continue;
            }
            Set<String> named = new LinkedHashSet<>();
            collectBodyNames(candidate.getValue().body(), named);
            candidate.getValue().source().ifPresent(source -> collectNames(source, named));
            if (named.contains(target)) {
                return candidate.getKey();
            }
        }
        return null;
    }

    /** One entry's failure as a {@link Diagnostic}, positioned at that entry's own declaration. */
    private static Diagnostic schemaError(TsonSchema schema, String name, TypeDefinition def, String message) {
        return Diagnostic.ofSchemaError(TsonCanonicalIdentity.canonicalize(schema.id()), name, message,
                def == null ? Optional.empty() : def.position());
    }

    /**
     * This schema's own canonical identity, stamped on every entry it declares itself -- the same form {@link
     * #schemaError} reports under and {@code TsonSchemaRegistry} keys on, so a read diagnostic and a schema
     * diagnostic against the same declaration name the same document.
     */
    private static String localIdentity(TsonSchema schema) {
        return TsonCanonicalIdentity.canonicalize(schema.id());
    }

    /** The shared body; {@code receiver} is {@code null} for the fail-fast overloads, which rethrow instead. */
    private static TsonLinkedSchema linkWith(TsonSchema schema, TsonSchemaLoader loader,
                                             TsonDiagnosticsReceiver receiver) {
        Map<String, String> origins = new LinkedHashMap<>();
        Map<String, TypeDefinition> merged = mergeImports(schema.imports(), loader, origins);

        // The governing meta-schema's own namespace, one hop via !!meta -- distinct from !!import (which
        // flattens another schema's entries into *this* schema's own returned entries()). !!meta only says
        // "this schema's own vocabulary/constructors come from that other schema"; it never merges anything
        // in, and (§3.3.2) it's never consulted for an ordinary type-ref -- only at the constructor roles
        // §3.3.1 lists. Used as a lookup fallback in exactly one spot now: `source` validation below
        // (`validateEntry`'s own `sourceLookup`), a `source` naming a constructor being one of those roles.
        // Everywhere else (field/key/value/element types, supertypes, subtypes, choice variants) stays
        // type-name-namespace-only, per §3.3.2's explicit "NOT extended by the structure namespace". Empty
        // if !!meta isn't registered yet (e.g. meta-kernel's own self-referential !!meta, mid-registration).
        Optional<TsonLinkedSchema> governingMeta =
                loader == null ? Optional.empty() : loader.load(TsonCanonicalIdentity.canonicalize(schema.meta()));
        checkMayGovern(schema, governingMeta);
        Map<String, TypeDefinition> structureNamespace = governingMeta
                .<Map<String, TypeDefinition>>map(linked -> linked.schema().entries()).orElse(Map.of());

        Set<String> localNames = new LinkedHashSet<>();

        Boolean constructorsAllowed = null;
        for (Map.Entry<String, TypeDefinition> entry : schema.entries().entrySet()) {
            if (merged.containsKey(entry.getKey())) {
                // The local entry is dropped, not the import's: an import is already-linked, separately
                // registered material, so keeping it is the choice that leaves the rest of this schema
                // checkable against something real.
                if (!report(receiver, schema, entry.getKey(), entry.getValue(), "'" + entry.getKey()
                        + "' collides with an entry of the same name brought in by !!import")) {
                    continue;
                }
            }
            TypeDefinition def = entry.getValue();
            if (def.constructor()) {
                if (constructorsAllowed == null) {
                    constructorsAllowed = isMetaKernelGoverned(schema);
                }
                if (!constructorsAllowed) {
                    // Reported, but the entry is still merged: its own shape is fine, it is only not permitted
                    // here, so keeping it lets every reference to it check normally instead of turning one
                    // eligibility error into an unresolved reference at every use site.
                    report(receiver, schema, entry.getKey(), def, "'" + entry.getKey()
                            + "' declares a type constructor "
                            + "(the '~' marker), but '" + schema.id() + "' is not governed directly by the "
                            + "meta-kernel (its own !!meta is '" + schema.meta() + "') -- only a schema chaining "
                            + "to meta-kernel.tn directly may declare new constructors (§2.2.2's "
                            + "meta-programming case); an ordinary type library or application schema may only "
                            + "apply or refine constructors it doesn't declare itself");
                }
            }
            merged.put(entry.getKey(), def);
            origins.put(entry.getKey(), localIdentity(schema));
            localNames.add(entry.getKey());
        }

        merged = computeSubtypes(merged, localNames);
        merged = computeDisjointness(merged);

        Set<String> blamedOnce = new LinkedHashSet<>();
        for (Map.Entry<String, TypeDefinition> entry : merged.entrySet()) {
            try {
                // Named by what the author wrote, not by the content-derived name a derived entry carries:
                // `[some_typo]` is a form they can find in their source where `array_some_typo_95c9a10f` is
                // a name §8.2 makes non-normative and nobody ever typed.
                validateEntry(EntryDisplayName.of(entry.getKey(), entry.getValue(), merged), entry.getValue(),
                        merged, structureNamespace);
            } catch (UnresolvedReference e) {
                String author = heldDeclarationNaming(e.name(), entry.getValue(), merged);
                if (author == null) { // the applier's own text, or a name no held body wrote: as before
                    reportOrThrow(receiver, schema, reportedAgainst(entry.getKey(), merged), merged,
                            e.getMessage(), e);
                    continue;
                }
                // One mistake in a template is one mistake however many declarations apply it: each
                // application mints its own entry and each fails identically, so the verdict that names the
                // template would otherwise be repeated once per applier.
                String message = e.against(author);
                if (blamedOnce.add(author + "\u0000" + message)) {
                    reportOrThrow(receiver, schema, author, merged, message, e);
                }
            } catch (TsonSchemaValidationException e) {
                reportOrThrow(receiver, schema, reportedAgainst(entry.getKey(), merged), merged,
                        e.getMessage(), e);
            }
        }

        checkEveryEntryIsInhabited(schema, merged, localNames, receiver);

        AnnotatedMap<String, TypeDefinition> annotated = withNameAnnotations(merged, schema, loader);
        checkDisjointAssertions(schema, annotated, localNames, receiver);

        return new TsonLinkedSchema(new TsonSchema(schema.id(), schema.meta(), schema.imports(),
                annotated, schema.bootstrap()), origins);
    }

    /**
     * §3.4.1: an entry no finite document can satisfy is rejected, with the chain that has to be broken
     * (§5.10.1's productivity rule, {@link TypeInhabitance}). {@code x => { y: y }} with {@code y => { x: x }}
     * resolves and links cleanly otherwise, and fails at the first document as {@code missing required field
     * 'x'} -- blaming the data for a defect in the schema.
     *
     * <p><b>Every local entry is judged, referenced or not</b>, on the same footing as a declared type
     * parameter the body never uses (§5.10): a declaration nothing can satisfy is a mistake wherever it sits,
     * and its author cannot see it. Imported entries are skipped -- they were judged when their own schema
     * linked, and repeating the verdict here would report one defect once per importer.
     *
     * <p>Runs after {@link #validateEntry}, so an unresolved reference is already reported and never mistaken
     * for an uninhabited one.
     */
    private static void checkEveryEntryIsInhabited(TsonSchema schema, Map<String, TypeDefinition> merged,
                                                    Set<String> localNames, TsonDiagnosticsReceiver receiver) {
        Set<String> inhabited = TypeInhabitance.derive(merged);
        for (String name : localNames) {
            if (inhabited.contains(name)) {
                continue;
            }
            // Rendered the way a read renders it: a derived entry is named by the form or application that
            // produced it, never by the content-derived name §8.2 makes non-normative. A chain reading
            // `tree_text_a7f070f6 needs array_tree_text_a7f070f6_1_f3d1a035` names two entries the author
            // never wrote, about a recursion they did.
            List<String> chain = TypeInhabitance.cycleThrough(name, merged, inhabited).stream()
                    .map(entry -> EntryDisplayName.of(entry, merged.get(entry), merged)).toList();
            report(receiver, schema, name, merged.get(name), "'" + name + "' can never be satisfied by any "
                    + "document: " + String.join(" needs ", chain)
                    + ", and nothing in that chain can be left out or left empty (§3.4.1). A recursion "
                    + "terminates only where it reaches a base case -- an optional field, a possibly-empty "
                    + "container, or a choice variant that does not recur");
        }
    }

    /**
     * §5.4's {@code @disjoint} assertion, checked against the fact {@link #computeDisjointness} derived. The
     * annotation "carries no decode force -- the resolver computes {@code type_definition.disjoint} whether
     * or not it is present -- and exists to be checked against that derived fact, converting a silent drift
     * into a diagnostic."
     *
     * <p><b>Two outcomes, because the fact is two-valued.</b> {@code disjoint: true} verifies the assertion,
     * silently; {@code false} makes it an error. §5.4's derivation ({@link ChoiceDisjointness}) is total, so
     * there is no third, unprovable outcome to report -- and no severity to report it at, [TSON-DATA] §8.1
     * giving a conforming processor one. That is what makes
     * {@code @disjoint} mean <em>machine-verified</em>, the only reading an encoding can rely on to drop a
     * tag. Note §5.4 is explicit that the reportable condition is the assertion, never mere
     * non-disjointness -- an unannotated choice is asked nothing here.
     *
     * <p><b>Runs on the annotated map, and after it is built.</b> §6 puts a declaration's annotations in two
     * places -- before the name (they land on the map key) or after {@code =>} (on the definition) -- and
     * {@code @disjoint} is equally an assertion either way, so both are consulted. Key annotations only
     * exist once {@link #withNameAnnotations} has re-attached them, which is why this is the last pass
     * rather than part of {@code validateEntry}.
     *
     * <p><b>Local entries only.</b> An imported entry was checked when its own schema linked, and {@link
     * #schemaError} stamps <em>this</em> schema's identity -- so re-checking would report another document's
     * problem against this one.
     */
    private static void checkDisjointAssertions(TsonSchema schema, AnnotatedMap<String, TypeDefinition> entries,
                                                 Set<String> localNames, TsonDiagnosticsReceiver receiver) {
        for (String name : localNames) {
            TypeDefinition def = entries.get(name);
            if (def == null || !(def.body() instanceof ChoiceBody choice)) {
                continue;
            }
            if (!def.annotations().has(DISJOINT) && !entries.getAnnotations(name).has(DISJOINT)) {
                continue;
            }
            if (def.disjoint().equals(Optional.of(true))) {
                continue; // verified -- the assertion holds, and says so
            }
            List<String> variants = choice.variants().stream().map(TypeRef::name).toList();
            report(receiver, schema, name, def, "'" + name + "' asserts @disjoint, but its variants "
                    + variants + " are not disjoint (§5.4) -- two of them occupy the same discrimination "
                    + "class (or one has no class at all), so no encoding's single form-resolution pass can "
                    + "tell them apart and every value keeps its !variant tag; drop the assertion, or use a "
                    + "field group (§5.11), which discriminates by label and needs no disjointness");
        }
    }

    /**
     * Re-attaches the annotations each name carried, which the passes above lose by rebuilding {@code merged}
     * as a plain map. §6 binds an annotation written before a declaration's name to that name, and a linked
     * schema is still a map keyed by those names, so they belong on the merged result rather than being
     * dropped at the one point every entry passes through.
     *
     * <p>An imported name keeps the annotations its own schema resolved, the same way its {@code
     * TypeDefinition} is carried in as-is -- documentation travels with the declaration, not with whoever
     * imported it.
     */
    private static AnnotatedMap<String, TypeDefinition> withNameAnnotations(Map<String, TypeDefinition> merged,
            TsonSchema schema, TsonSchemaLoader loader) {
        AnnotatedMap<String, TypeDefinition> result = AnnotatedMap.of(merged);
        if (loader != null) {
            for (String importUri : schema.imports()) {
                Optional<TsonLinkedSchema> imported = loader.load(TsonCanonicalIdentity.canonicalize(importUri));
                if (imported.isPresent()) {
                    result = carryOver(result, imported.get().schema().entries());
                }
            }
        }
        return carryOver(result, schema.entries());
    }

    private static AnnotatedMap<String, TypeDefinition> carryOver(AnnotatedMap<String, TypeDefinition> into,
            AnnotatedMap<String, TypeDefinition> from) {
        AnnotatedMap<String, TypeDefinition> result = into;
        for (String name : from.annotatedKeys()) {
            if (result.containsKey(name)) {
                result = result.withAnnotations(name, from.getAnnotations(name));
            }
        }
        return result;
    }

    /**
     * The other half of the constructor-eligibility rule: {@link #isMetaKernelGoverned} restricts which schema
     * may <em>declare</em> a constructor, and this restricts which may be <em>named as a {@code !!meta}
     * target</em>. Both are the same §2.2.2 question asked from the two ends, and a schema that fails this one
     * could not supply a structure namespace anyway -- having declared no constructors, it has none to supply.
     *
     * <p>The failure this prevents is an ordinary type library or application schema governing another by
     * accident. Naming one as {@code !!meta} is almost always a confusion with {@code !!import}: an import
     * merges another schema's entries into this schema's type-name namespace, which is what a caller wanting
     * core.tn's {@code uuid} means, while {@code !!meta} names the contract the declarations themselves are
     * validated against and is consulted only at §3.3.1's constructor roles. Uncaught, it surfaces much later
     * as every construction in the governed schema falling out of scope.
     *
     * <p>In the wiring this library ships, a schema resolved through {@code TsonCompiledMetaRegistry} reaches
     * that registry's own {@code loadMeta} first, which asks the same question a phase earlier (it must
     * <em>compile</em> the meta to resolve against it) and raises {@link #notAMetaSchema} itself. This is
     * still the linker's to check: a caller driving link with their own loader gets the same verdict, and the
     * rule belongs with the declaration-side half it mirrors rather than only in a registry.
     *
     * <p>Only checked when the target actually loaded. An unresolvable {@code !!meta} is left to the caller
     * that owns fetching (and meta-kernel's self-naming {@code !!meta}, mid-registration, is exactly that
     * case) -- absence of evidence here is not evidence of ineligibility.
     */
    private static void checkMayGovern(TsonSchema schema, Optional<TsonLinkedSchema> governingMeta) {
        if (governingMeta.isEmpty() || isMetaKernelGoverned(governingMeta.get().schema())) {
            return;
        }
        throw notAMetaSchema(schema.meta(), governingMeta.get().schema().meta(), schema.id());
    }

    /**
     * The one wording for "that schema cannot govern this one", shared with {@code
     * TsonCompiledMetaRegistry.loadMeta}, which reaches the same conclusion one layer up and knows only the
     * target (hence the nullable {@code governedId}). Public because that caller is in another module.
     *
     * <p>It is a {@link TsonSchemaValidationException} rather than an {@code IllegalStateException} because
     * it is an <b>authoring</b> error in a schema document, not a library fault: a caller wrapping {@code
     * resolve} in the obvious {@code catch} sees it, and a CLI telling apart "your schema is wrong" from "this
     * tool is broken" gets the right answer.
     *
     * @param governedId the schema naming {@code target} as its {@code !!meta}, or {@code null} where the
     *     caller has only the target in hand
     */
    public static TsonSchemaValidationException notAMetaSchema(String target, String targetsOwnMeta,
            String governedId) {
        return new TsonSchemaValidationException("'" + target + "' is named as the !!meta"
                + (governedId == null ? " of another schema" : " of '" + governedId + "'")
                + " but is not a meta-schema -- its own !!meta is '" + targetsOwnMeta + "', not meta-kernel.tn, "
                + "so it declares no type constructors and supplies no structure namespace (§2.2.2, §3.3.1). "
                + "To use another schema's types, import it (!!import merges its entries into this schema's "
                + "type-name namespace); !!meta names only the meta-schema the declarations are validated "
                + "against");
    }

    /**
     * Whether {@code schema} is entitled to declare its own {@code ~}-marked constructors -- true
     * only if its own {@code !!meta} target is exactly {@link TsonBundledSchemas#META_KERNEL_ID}, the *specific*
     * meta-kernel this library's own compiled-reader machinery is built against, not merely "some
     * self-referencing schema." This is stricter than structural self-reference alone: every
     * resolved {@code TypeDefinition.body} and every {@code !instance} construction (`!enum`,
     * `!integer_type`, ...) is only interpretable because a matching type constructor is declared in
     * *this* meta-kernel specifically -- {@code TsonParserFactoryRegistry}/{@code AtomTypeParser}/
     * {@code RecordParser} (in {@code tson-compiler}) are Java code hard-wired to this one meta-kernel's
     * own fixed vocabulary, not to "whatever schema happens to be self-referencing." A library
     * supports one meta-kernel version at a time (a new revision would mean rebuilding that
     * machinery, not just accepting a differently-identified but structurally-similar substitute), so
     * this checks the schema's own {@code !!meta} string directly, no lookup needed -- correct for
     * meta-kernel itself (whose own {@code !!meta} literally is {@link TsonBundledSchemas#META_KERNEL_ID}) and for
     * meta.tn (governed one hop below it) alike.
     */
    private static boolean isMetaKernelGoverned(TsonSchema schema) {
        return TsonCanonicalIdentity.sameIdentity(schema.meta(), TsonBundledSchemas.META_KERNEL_ID);
    }

    // ── Subtypes (reverse index) ─────────────────────────────────────────

    /**
     * Populates {@link TypeDefinition#subtypes}, the reverse of {@link TypeDefinition#supertypes}
     * -- never done anywhere before this (see {@code DefinitionResolver}'s own Javadoc: "subtypes...
     * is never populated -- it needs a whole-schema pass, not a per-declaration one"). Since {@code
     * supertypes} is already the full *transitive* IS-A chain (by the induction {@code
     * DefinitionResolver}'s composition/refinement resolution already performs), the reverse index
     * falls out just as transitively for free: if {@code success_response}'s own {@code
     * supertypes} includes both {@code response} and (transitively) {@code top}, then both gain
     * {@code success_response} as a subtype here, with no separate transitive-closure step needed.
     *
     * <p><b>Only {@code localNames} entries are walked as potential subtypes -- but the supertype
     * being credited may be anywhere in {@code merged}, imported or local.</b> An import's own
     * already-registered {@link TsonSchema} is never mutated -- {@code mergeImports} only reads its
     * {@code entries()} (an unmodifiable map) and copies {@code TypeDefinition} *references* into a
     * brand-new {@code merged} map built fresh for *this* validation; replacing one of those
     * references in {@code merged} (via {@link #withAddedSubtypes}) changes only this schema's own
     * result, never the imported schema's own frozen copy sitting wherever {@code loader} found it.
     * So when a local entry composes with an imported supertype, crediting the subtype onto *this
     * schema's own view* of that supertype is safe, and correct: from this schema's own perspective
     * the supertype genuinely does have that subtype, even though the imported schema, examined on
     * its own, correctly doesn't know about it. {@link #withAddedSubtypes} unions with whatever
     * subtypes the target already had (e.g. other subtypes declared within its own home schema)
     * rather than replacing them, so an import contributes its own existing subtypes plus whatever
     * new ones this importer adds -- both survive in this schema's own merged result.
     */
    private static Map<String, TypeDefinition> computeSubtypes(Map<String, TypeDefinition> merged,
                                                                 Set<String> localNames) {
        Map<String, Set<String>> newSubtypesByName = new LinkedHashMap<>();
        for (String localName : localNames) {
            for (String supertype : merged.get(localName).supertypes()) {
                if (merged.containsKey(supertype)) {
                    newSubtypesByName.computeIfAbsent(supertype, ignored -> new LinkedHashSet<>()).add(localName);
                }
            }
        }
        if (newSubtypesByName.isEmpty()) {
            return merged;
        }

        Map<String, TypeDefinition> result = new LinkedHashMap<>(merged);
        for (Map.Entry<String, Set<String>> entry : newSubtypesByName.entrySet()) {
            result.put(entry.getKey(), withAddedSubtypes(result.get(entry.getKey()), entry.getValue()));
        }
        return result;
    }

    /**
     * {@code def} plus {@code newSubtypes}, unioned with whatever subtypes it already had -- a new {@link
     * TypeDefinition}, {@code def} itself untouched.
     *
     * <p>Every component is carried across explicitly, {@code position} and {@code annotations} included.
     * They are the two a shorter constructor defaults away, and both are load-bearing: a diagnostic against
     * this entry is located by {@code position}, and §6 metadata written after {@code =>} lives in {@code
     * annotations}. Dropping them here would silently blank both for every type that has a subtype.
     */
    private static TypeDefinition withAddedSubtypes(TypeDefinition def, Set<String> newSubtypes) {
        Set<String> combined = new LinkedHashSet<>(def.subtypes());
        combined.addAll(newSubtypes);
        return new TypeDefinition(def.source(), def.kind(), def.parameters(), def.constructor(),
                def.supertypes(), List.copyOf(combined), def.disjoint(), def.body(), def.position(),
                def.annotations());
    }

    /**
     * Derives {@link TypeDefinition#disjoint} for every choice entry (§5.4), over the fully-merged
     * namespace -- a namespace-wide pass, like {@link #computeSubtypes}, since a variant's discrimination
     * class is only knowable with every entry resolved. The derivation ({@link ChoiceDisjointness}) is
     * total and two-valued, so a linked choice always carries the fact; only non-choice entries leave it
     * absent.
     */
    private static Map<String, TypeDefinition> computeDisjointness(Map<String, TypeDefinition> merged) {
        Map<String, TypeDefinition> result = new LinkedHashMap<>(merged);
        for (Map.Entry<String, TypeDefinition> entry : merged.entrySet()) {
            if (entry.getValue().body() instanceof ChoiceBody choice) {
                TypeDefinition def = entry.getValue();
                result.put(entry.getKey(), new TypeDefinition(def.source(), def.kind(), def.parameters(),
                        def.constructor(), def.supertypes(), def.subtypes(),
                        Optional.of(ChoiceDisjointness.derive(choice, merged)), def.body(), def.position(),
                        def.annotations()));
            }
        }
        return result;
    }

    /**
     * Stage 1: every {@code !!import}'s whole namespace, in declaration order, brought in as-is (§2.2.3's
     * "merged entries keep their home namespace" -- no re-resolution here).
     *
     * <p><b>The namespace is flat and the merge is transitive</b> (§2.2.3): an import
     * contributes everything its own namespace holds, its own imports' entries included, exactly as {@code
     * !!meta} contributes its target's locals *plus its imports* (§3.3.1's "Import what you expose"). So a
     * schema reached by two routes arrives once, and the importer sees one flat name-to-type map with no
     * hidden layers.
     *
     * <p><b>A collision is decided by entry identity, not by name occurrence.</b> The same schema reached
     * through several routes -- the diamond every practical schema forms by importing core.tn -- is one set
     * of entries, so re-arrival is unification, not conflict. Two *different* schemas declaring one name is
     * the real collision, and it is still an error: distinct types cannot share a name in a flat namespace.
     * That is also what makes a revision mismatch (one route reaching {@code /2026/32/m/core.tn}, another
     * {@code /2026/33/m/core.tn}) a hard error at namespace-construction time rather than a confusing field
     * conflict between two identically-spelled types much later.
     *
     * <p>Identity is the canonical one ([TSON-DATA] §2.2.1), so a pinned and an unpinned reference to one
     * schema unify -- which is what lets an author pin their own import while a peer's is unpinned.
     */
    private static Map<String, TypeDefinition> mergeImports(List<String> imports, TsonSchemaLoader loader,
                                                            Map<String, String> origins) {
        Map<String, TypeDefinition> merged = new LinkedHashMap<>();
        Set<String> alreadyImported = new LinkedHashSet<>();
        for (String importUri : imports) {
            String importIdentity = TsonCanonicalIdentity.canonicalize(importUri);
            // Listing one schema twice (or under two spellings of one identity) is redundant, not an error:
            // the second mention asks for a namespace already present and contributes nothing new.
            if (!alreadyImported.add(importIdentity)) {
                continue;
            }
            TsonLinkedSchema imported = loader.load(importIdentity).orElseThrow(() -> new TsonSchemaValidationException(
                    "!!import '" + importUri + "' is not registered"));
            for (Map.Entry<String, TypeDefinition> entry : imported.schema().entries().entrySet()) {
                String name = entry.getKey();
                // The import's own answer, not importIdentity: an entry it reached through an import of its
                // own belongs to whoever declared it, however many hops away that is.
                String origin = imported.originOf(name);
                String incumbent = origins.get(name);
                if (incumbent != null) {
                    if (!incumbent.equals(origin)) {
                        throw new TsonSchemaValidationException("'" + name + "' is declared by two different "
                                + "schemas reached through !!import ('" + incumbent + "' and '" + origin
                                + "') -- distinct types cannot share one name in the flat namespace; import "
                                + "one of them, or a version of each that agrees on where '" + name
                                + "' is declared");
                    }
                    merged.put(name, unified(merged.get(name), entry.getValue()));
                    continue;
                }
                merged.put(name, entry.getValue());
                origins.put(name, origin);
            }
        }
        return merged;
    }

    /**
     * One entry reached by two routes, reconciled. Both copies came from the same declaring schema, so they
     * agree on everything the declaring schema resolved -- everything except {@code subtypes}, which each
     * route's own linking credited against *its* view of the namespace ({@link #computeSubtypes}). The
     * union is the answer §9 requires here: {@code subtypes} is the transitive inverse of {@code supertypes}
     * across *this* schema's namespace, and this schema can see both routes' subtypes even though neither
     * route could see the other's.
     */
    private static TypeDefinition unified(TypeDefinition incumbent, TypeDefinition arriving) {
        if (incumbent.subtypes().containsAll(arriving.subtypes())) {
            return incumbent;
        }
        return withAddedSubtypes(incumbent, new LinkedHashSet<>(arriving.subtypes()));
    }

    // ── Validation ───────────────────────────────────────────────────────

    private static void validateEntry(String name, TypeDefinition def, Map<String, TypeDefinition> namespace,
                                       Map<String, TypeDefinition> structureNamespace) {
        checkOpenEntryUsesEveryParameter(name, def);
        if (def.source().isPresent()) {
            // Unlike every other reference below, `source` gets the structure-namespace fallback --
            // per §3.3.1, the name it records was consumed at a *constructor role* at the point this
            // entry was originally resolved (a constructor-application target, or (for atom
            // refinement) the instance's own already-resolved constructor), both explicitly
            // structure-namespace-eligible, unlike an ordinary type-ref (§3.3.2: "NOT extended by the
            // structure namespace... field types... composition targets"). See #link's own note
            // on `structureNamespace` for why this matters concretely: `void => !unit {}`'s own
            // `source: unit` is exactly this case -- `unit` lives in meta-kernel, reachable from
            // core.tn only via its `!!meta` chain, never a local declaration or `!!import`.
            //
            // The one `source` shape the fallback does *not* cover is an application -- a `source` carrying
            // arguments. Desugar rewrites every constructor application long before resolution, so arguments
            // surviving to here mean a §5.10 user-template head, which §3.3.1 resolves in the type-name
            // namespace only. Letting it reach the governing meta finds a template the schema cannot name and
            // faults it on arity, when the verdict every other reference form gives for that name is that it
            // is unresolved.
            TypeRef source = def.source().get();
            Map<String, TypeDefinition> sourceLookup =
                    structureNamespace.isEmpty() || !source.arguments().isEmpty() ? namespace
                            : mergeWithFallback(namespace, structureNamespace);
            validateTypeRef(source, sourceLookup, def.parameters(), name, " source");
        }
        // A supertype gets the same structure-namespace fallback as `source`, and for the same reason: it is
        // not an author-written reference but the residue of one, and §3.3.2 confines only author-written
        // type-refs to the type-name namespace; §2.2.3 puts a merged entry's own derived references in its
        // defining schema's namespace, not the importer's. A derived chain reaches a constructor
        // whenever a refinement derives from one -- meta-kernel's own `set => ~array ^ {...}` resolves with
        // [array, product, top]. The fallback is defensive rather than load-bearing today: a
        // refinement source resolves through the type-name namespace alone, so a schema deriving from `array`
        // already names it. What did need it -- a transfer of a template's supertypes onto every sized array
        // materialised in a user schema -- is gone with the size templates themselves.
        for (String supertype : def.supertypes()) {
            if (!namespace.containsKey(supertype) && !structureNamespace.containsKey(supertype)) {
                throw new TsonSchemaValidationException("'" + name + "' has an unresolved supertype '" + supertype + "'");
            }
        }
        for (String subtype : def.subtypes()) {
            if (!namespace.containsKey(subtype)) {
                throw new TsonSchemaValidationException("'" + name + "' has an unresolved subtype '" + subtype + "'");
            }
        }
        validateBody(name, def.body(), namespace, def.parameters());
    }

    private static void validateBody(String entryName, Top body, Map<String, TypeDefinition> namespace,
                                      List<String> ownParameters) {
        switch (body) {
            case RecordBody r -> {
                for (String supertype : r.supertypes()) {
                    if (!namespace.containsKey(supertype)) {
                        throw new TsonSchemaValidationException(
                                "'" + entryName + "' has an unresolved supertype '" + supertype + "'");
                    }
                }
                for (RecordField field : r.fields()) {
                    validateTypeRef(field.type(), namespace, ownParameters, entryName,
                            " field '" + field.name() + "'");
                    checkFieldValue(entryName, field, namespace, ownParameters);
                }
                for (FieldGroup group : r.groups()) {
                    for (String member : group.members()) {
                        if (r.fields().stream().noneMatch(f -> f.name().equals(member))) {
                            throw new TsonSchemaValidationException(
                                    "'" + entryName + "' has a field group referencing unknown field '" + member + "'");
                        }
                    }
                }
            }
            // The name alone: a reference body holds a `type_name`, so there is no argument list to check
            // arity or nested references against. Where the alias names an *application* the arguments are
            // in the entry's own `source`, which validateEntry already validated in full -- and the two are
            // not always the same name (a materialised instantiation sources the application and targets
            // the entry minted for it), which is why this checks the body's own target rather than trusting
            // that.
            case Reference ref -> validateTypeRef(ref.target(), namespace, ownParameters, entryName, "");
            case MapBody m -> {
                validateTypeRef(m.keyType(), namespace, ownParameters, entryName, " key_type");
                validateTypeRef(m.valueType(), namespace, ownParameters, entryName, " value_type");
            }
            case ArrayBody a -> validateTypeRef(a.elementType(), namespace, ownParameters, entryName,
                    " element_type");
            case TupleBody t -> {
                int index = 0;
                for (TupleElement element : t.elements()) {
                    validateTypeRef(element.elementType(), namespace, ownParameters, entryName,
                            " element[" + index + "]");
                    index++;
                }
            }
            case ChoiceBody c -> {
                int index = 0;
                for (TypeRef variant : c.variants()) {
                    validateTypeRef(variant, namespace, ownParameters, entryName, " variant[" + index + "]");
                    index++;
                }
                checkVariantsAreDistinct(entryName, c, namespace);
                checkVariantsAreNotVoid(entryName, c, namespace);
            }
            // A held body is opaque to everything that needs to know what a reference *resolves to*: that
            // cannot be settled until substitution supplies the arguments, so type-kind validation and
            // inhabitance apply to it at materialisation instead, where the whole body resolves at once.
            // Checking it here by substituting stand-in arguments would report errors on templates that are
            // correct for every argument anyone passes (`<N> !integer ^ { min: N max: 3 }`).
            //
            // Arity is the exception, and it is decidable: it asks how many parameters the *referenced*
            // entry declares, which no argument changes. See checkHeldArity for why that has to be asked
            // here rather than left to an application that may never happen.
            case TemplateBody held -> checkHeldArity(entryName, held, namespace, ownParameters);
            case Unit ignored -> {
            }
            case EnumBody ignored -> {
            }
            case IntegerType ignored -> {
            }
            case TextType ignored -> {
            }
            case UriType ignored -> {
            }
            case RegexType ignored -> {
            }
            case DecimalType ignored -> {
            }
            case FloatType ignored -> {
            }
            case RationalType ignored -> {
            }
            case UuidType ignored -> {
            }
            case BinaryType ignored -> {
            }
            case DateType ignored -> {
            }
            case TimeType ignored -> {
            }
            case DateTimeType ignored -> {
            }
            case DurationType ignored -> {
            }
            case Cidr4Type ignored -> {
            }
            case Cidr6Type ignored -> {
            }
            case EmailType ignored -> {
            }
            case MacType ignored -> {
            }
            case Ipv4Type ignored -> {
            }
            case Ipv6Type ignored -> {
            }
            case ComplexType ignored -> {
            }
            case UnknownType ignored -> {
            }
            case Extern ignored -> {
            }
            case Data data -> {
                // A body describing something other than a data value. Its shape is the consumer's own Java
                // class, so nothing here can introspect it -- what it declares through `references()` is
                // validated like any other reference, and everything else is opaque by design.
                for (TypeRef reference : data.references()) {
                    validateTypeRef(reference, namespace, ownParameters, entryName,
                            " (!" + TsonCompiledMetaSchema.typenameOf(data) + ")");
                }
            }
        }
    }

    /**
     * §5.4: "The resolver validates that each variant resolves to a distinct type."
     *
     * <p><b>Judged after flattening, which is the whole point of the rule.</b> §8.3 makes an alias and its
     * target one type, so {@code (text | my_text)} with {@code my_text => text} is the same duplicate {@code
     * (text | text)} is -- spelled so that an author cannot see it. Comparing the written names would catch
     * only the spelling an author would have caught themselves. A duplicate variant is never merely
     * redundant: the second is unreachable under §5.4's Tagging rule, since a {@code !variant} tag naming it
     * and one naming the first select the same type, and untagged recovery has nothing to choose between.
     *
     * <p>Here rather than in {@code SchemaDesugarer}, where the sugar is expanded, because this asks what
     * names <em>resolve to</em> -- a question with no answer until the whole namespace exists, imports
     * merged. It runs after {@link #validateTypeRef} has accepted every variant, so an unresolved name is
     * already reported and never reaches the walk.
     *
     * <p>Arguments are compared as written rather than flattened through: only the head name is walked. The
     * gap is unreachable from the sugar -- {@code SchemaDesugarer} hoists any argument-bearing variant to a
     * bare name -- and reaches only a hand-written {@code !choice { variants: [...] } } naming two
     * differently-spelled applications of one type.
     */
    private static void checkVariantsAreDistinct(String entryName, ChoiceBody choice,
                                                  Map<String, TypeDefinition> namespace) {
        Map<TypeRef, String> seen = new LinkedHashMap<>();
        for (TypeRef variant : choice.variants()) {
            TypeRef flattened = new TypeRef(terminalName(variant.name(), namespace), variant.arguments());
            String first = seen.putIfAbsent(flattened, variant.name());
            if (first == null) {
                continue;
            }
            throw new TsonSchemaValidationException("'" + entryName + "' " + (first.equals(variant.name())
                    ? "lists the variant '" + variant.name() + "' twice"
                    : "variants '" + first + "' and '" + variant.name() + "' both resolve to '"
                            + flattened.name() + "'")
                    + " -- §5.4 requires each variant to resolve to a distinct type");
        }
    }

    /**
     * A variant must not resolve to {@code void} (§5.4): {@code (T | void)} spells
     * optionality as a choice, and optionality belongs to the position -- a field's {@code ?} state, the
     * {@code _} sentinel -- never to the type occupying it. Judged after §8.3 flattening, like
     * distinctness, so an alias of {@code void} is caught under whatever name the author wrote.
     */
    private static void checkVariantsAreNotVoid(String entryName, ChoiceBody choice,
                                                 Map<String, TypeDefinition> namespace) {
        for (TypeRef variant : choice.variants()) {
            if (terminalName(variant.name(), namespace).equals("void")) {
                throw new TsonSchemaValidationException("'" + entryName + "' has a variant"
                        + (variant.name().equals("void") ? "" : " '" + variant.name() + "'")
                        + " resolving to 'void' -- optionality is not choice (§5.4): a value's absence is the "
                        + "position's own state, so mark the position optional ('?') instead of uniting its "
                        + "type with void");
            }
        }
    }

    /**
     * The name a reference chain ends at (§8.3): the first entry whose body is not a {@code Reference}. A
     * name this schema does not declare is returned unchanged -- it is a type parameter, already accepted by
     * {@link #validateTypeRef}.
     *
     * <p>A reference cycle stops the walk rather than hanging. Detecting and diagnosing one is a separate
     * unimplemented concern ({@code BACKLOG.md}); stopping at the repeat is enough here, because the name it
     * stops at depends on where the walk started, so a cycle yields no false duplicate.
     */
    private static String terminalName(String name, Map<String, TypeDefinition> namespace) {
        Set<String> walked = new LinkedHashSet<>();
        String current = name;
        while (walked.add(current)) {
            TypeDefinition def = namespace.get(current);
            if (def == null || !(def.body() instanceof Reference reference)
                    || !reference.target().arguments().isEmpty()) {
                return current; // an argument-bearing target is an application, not a hop to another entry
            }
            current = reference.target().name();
        }
        return current;
    }

    /** {@code fallback} entries, overridden by {@code primary} on collision -- {@code primary} isn't mutated. */
    private static Map<String, TypeDefinition> mergeWithFallback(Map<String, TypeDefinition> primary,
                                                                   Map<String, TypeDefinition> fallback) {
        Map<String, TypeDefinition> combined = new LinkedHashMap<>(fallback);
        combined.putAll(primary);
        return combined;
    }

    /**
     * §5.10's parameter-usage rule: an <em>open</em> entry references every parameter it declares. {@code box => <T> { v: text }} declares
     * {@code T} and never uses it, so no application of it could differ from any other -- the parameter is a
     * mistake, not a degenerate-but-legal template.
     *
     * <p><b>A {@link TsonSchemaValidationException}.</b> A parameter list is author-written, so an unused one
     * is the author's error rather than a library fault.
     *
     * <p>Its old converse -- §5.10's closed-entry rule, checked over {@code record_field.value_param} -- has
     * no sound form now that a parameter and a literal share one slot: at a closed entry there are no
     * parameters for a token to resolve into, so a token there <em>is</em> a literal (§8.1's shadowing rule)
     * and there is nothing to detect. The rule's reference half is unaffected, and needs no code of its own:
     * {@link #validateTypeRef} accepts a name only if the namespace holds it or {@code ownParameters} lists
     * it, so at a closed entry a parameter reference is already an unresolved one.
     *
     * <p>Deciding this rather than admitting the degenerate form also settles a question the open
     * representation would otherwise have to answer: with an unreferenced parameter rejected, a template
     * whose bindings are all concrete cannot exist, so nothing has to say whether unused parameters
     * participate in comparing two open entries.
     */
    private static void checkOpenEntryUsesEveryParameter(String entryName, TypeDefinition def) {
        if (def.parameters().isEmpty()) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        def.source().ifPresent(ref -> collectNames(ref, referenced));
        collectBodyNames(def.body(), referenced);
        for (String parameter : def.parameters()) {
            if (!referenced.contains(parameter)) {
                throw new TsonSchemaValidationException("'" + entryName + "' declares the type parameter '"
                        + parameter + "' and never references it, so every application of it would denote the "
                        + "same type -- a declared parameter must be used (§5.10)");
            }
        }
    }

    /** Every name an entry's body mentions, for {@link #checkOpenEntryUsesEveryParameter}. */
    private static void collectBodyNames(Top body, Set<String> into) {
        switch (body) {
            case RecordBody record -> record.fields().forEach(field -> {
                collectNames(field.type(), into);
                // A routed parameter rides `value` like any other token, so it is named here too -- which is
                // what keeps `<S> base ^ { status: = S }` from reading as a template that never uses S.
                field.value().map(Token::text).ifPresent(into::add);
            });
            case ArrayBody array -> collectNames(array.elementType(), into);
            case MapBody map -> {
                collectNames(map.keyType(), into);
                collectNames(map.valueType(), into);
            }
            case TupleBody tuple -> tuple.elements().forEach(e -> collectNames(e.elementType(), into));
            case ChoiceBody choice -> choice.variants().forEach(v -> collectNames(v, into));
            case Reference reference -> collectNames(reference.target(), into);
            // The one question a held body answers without being resolved, and it answers it about tokens
            // rather than about references -- which is the same rule substitution follows when it decides
            // what to rewrite.
            case TemplateBody held -> into.addAll(held.names());
            default -> { } // an atom body names no type
        }
    }

    private static void collectNames(TypeRef ref, Set<String> into) {
        into.add(ref.name());
        for (TypeArgument argument : ref.arguments()) {
            if (argument instanceof TypeArgument.Ref nested) {
                collectNames(nested.ref(), into);
            }
        }
    }

    /**
     * [TSON-SCHEMA] §5.2's dependency between a field's two halves: a {@code ~}/{@code =} value must be a
     * value of the field's own declared type. meta-kernel states it on {@code record_field.value} -- "the
     * type of fixed/default values, which must be the field's declared type" -- and calls it "a dependency
     * the schema language does not express directly", which is what leaves it to a check like this one.
     *
     * <p><b>Here rather than at compile, because of who the verdict belongs to.</b> The same check runs
     * today as a side effect of building the record's reader ({@code RecordAbstractReader} decodes every
     * FIXED/DEFAULT value once, at construction), and a failure there becomes an {@code ErrorReader} -- so
     * the author's own {@code tson compile} passes, and the mistake surfaces to whoever later sends data,
     * coded as a gap in this library. The verdict does not change as this library improves, so by the
     * project's own classification test it is the author's error, and this phase is where an author error
     * against one declaration is already reported with the declaration's own name and line.
     *
     * <p><b>Atoms and enums only, and the rest is not silently blessed.</b> A field typed by a record,
     * container or choice needs a compiled reader to check a value against, and compilation happens after
     * linking; those keep the existing path. A field whose type is a parameter is skipped by construction --
     * a held body is not read as this vocabulary at all, so the only parametric field reaching here has
     * already been substituted by materialisation, and is checked against the argument it was closed with.
     */
    private static void checkFieldValue(String entryName, RecordField field,
                                         Map<String, TypeDefinition> namespace, List<String> ownParameters) {
        if (field.value().isEmpty() || ownParameters.contains(field.type().name())) {
            return;
        }
        TypeDefinition target = namespace.get(field.type().name());
        // An unresolved reference is already reported by validateTypeRef; a target that is still open (or an
        // application of one) has no single body to check against until materialisation closes it. A
        // REFERENCE target should not occur -- §8.3 flattens a type position past one -- and skipping is the
        // right answer if it ever does: the chain end is what would have to be checked, not the hop.
        if (target == null || !target.parameters().isEmpty() || !field.type().arguments().isEmpty()
                || target.body() instanceof Reference) {
            return;
        }
        Optional<AtomType<?>> parser = AtomParsers.forBody(target.body());
        if (parser.isEmpty()) {
            return;
        }
        Token value = field.value().get();
        try {
            parser.get().read(new TokenValue(value.text(), TokenForm.valueOf(value.form().name())));
        } catch (AtomTypeException e) {
            // The field's two halves are what the author has to reconcile, so both are named, in the order
            // they are written, and the value is echoed as the schema spells it -- quoted if it was quoted,
            // so the author reads back their own line rather than a normalisation of it. The atom's own
            // message follows: it already states the rule and cites the section, so nothing here restates it.
            throw new TsonSchemaValidationException("'" + entryName + "': field '" + field.name() + "' is "
                    + "declared '" + field.type().name() + "', but its "
                    + (field.state() == FieldState.REQUIRED_DEFAULT ? "default" : "fixed value") + " "
                    + asWritten(value) + " is not a value of that type -- " + e.getMessage() + ". §5.2 makes "
                    + "a field's fixed or default value a value of the field's own declared type");
        }
    }

    /** A token echoed the way the schema spells it, so a quoted value is visibly quoted in the message. */
    private static String asWritten(Token token) {
        return token.form() == Token.Form.UNQUOTED ? token.text() : "\"" + token.text() + "\"";
    }

    /**
     * One reference validated. The entry it sits in is passed as {@code subject} and the path within that
     * entry as {@code trail} ({@code " field 'w'"}, {@code " element[1]"}) rather than pre-joined, because
     * {@link UnresolvedReference} has to be able to re-state itself against a different subject: a defect a
     * held body deferred surfaces on the entry materialisation minted and belongs to the declaration whose
     * text wrote it, and only the subject differs between the two statements of it.
     */
    private static void validateTypeRef(TypeRef ref, Map<String, TypeDefinition> namespace, List<String> ownParameters,
                                         String subject, String trail) {
        String context = "'" + subject + "'" + trail;
        TypeDefinition target = namespace.get(ref.name());
        if (target != null && target.body() instanceof Data notAType) {
            // §8.1's schema map holds only type definitions, so an entry describing something else has no
            // way to say "declare me, but do not let anything name me as a type". The Data marker is that
            // way. Without this the misuse resolves, links AND compiles, and fails only when a document is
            // read against it. §4.1: naming a DATA entry where a type is expected is a resolver error.
            throw new TsonSchemaValidationException(context + " names '" + ref.name() + "', which is built "
                    + "with '" + TsonCompiledMetaSchema.typenameOf(notAType) + "' and describes something "
                    + "other than a data value -- it is declared by this schema but is not a type, so "
                    + "nothing can be typed by it");
        }
        if (!namespace.containsKey(ref.name()) && !ownParameters.contains(ref.name())) {
            throw new UnresolvedReference(subject, trail, ref.name());
        }
        checkArity(ref, namespace, ownParameters, context);
        for (TypeArgument arg : ref.arguments()) {
            if (arg instanceof TypeArgument.Ref nested) {
                validateTypeRef(nested.ref(), namespace, ownParameters, subject, trail);
            }
            // TypeArgument.Value is a literal token, not a type reference -- nothing to validate.
        }
    }

    /**
     * §5.10's arity rule over the <b>applications</b> a held body writes -- {@code chain => <T> { tail:
     * chain<T, T>? }} applies two arguments to a one-parameter template, and nothing ever closes that
     * application, so deferring the check would let the template ship with the mistake in it.
     *
     * <p><b>It can be answered without substituting</b>, which is why it belongs here at all: arity compares
     * the argument count written against the parameter count the referenced entry declares, and neither
     * number depends on what the arguments resolve to -- the only thing holding withholds.
     *
     * <p><b>Applications only, never bare names.</b> The rule's other half -- a template named without being
     * applied -- is not decidable against a held body: its tokens are field names, states, literals and type
     * references alike, so "this token names a template" rejects a correct schema whose field happens to be
     * called {@code box} beside a template of that name. An application is a distinguishable shape in the
     * wire tree; a bare name is not. So that half runs where the reference is unambiguous, on the entry
     * materialisation mints -- and an unapplied template gets no verdict, which is the open form's own
     * position rather than a shortfall ({@code SPEC-FEEDBACK.md} #5: "an unapplied template is checked no
     * further and gets no verdict").
     */
    private static void checkHeldArity(String entryName, TemplateBody held,
            Map<String, TypeDefinition> namespace, List<String> ownParameters) {
        for (TypeRef application : held.applications()) {
            checkArity(application, namespace, ownParameters, "'" + entryName + "'");
            for (TypeArgument argument : application.arguments()) {
                if (argument instanceof TypeArgument.Ref nested) {
                    checkArity(nested.ref(), namespace, ownParameters, "'" + entryName + "'");
                }
            }
        }
    }

    /**
     * §5.10's arity rule, over every reference in the schema: a reference supplies exactly as many arguments
     * as the entry it names declares parameters. Three shapes of author error collapse into it -- too many
     * ({@code chain<T, T>} for a one-parameter {@code chain}), too few, and <b>none at all</b>
     * ({@code use => { u: box } } naming a template without applying it, which is the common one).
     *
     * <p><b>The zero-argument case is why this check exists here rather than at an application site.</b> An
     * unapplied template reference is not an application, so nothing on the materialisation path ever sees
     * it: it linked, compiled, and then failed at <em>read</em> time with "no usable compiled reader" and a
     * library-fault exit code, for what is plainly the author's error. The eager-rejection discipline
     * guarded applications and never bare names.
     *
     * <p>By the time linking runs, {@code TemplateMaterialiser} has rewritten every application it could
     * close, so what reaches here is the set that genuinely needs checking: references inside template
     * bodies, which stay open by design, and bare names anywhere.
     *
     * <p>A reference naming one of the enclosing declaration's own <em>parameters</em> has no arity to check
     * -- a parameter is not an entry and declares nothing -- but it is not simply skipped: §5.10 admits no
     * head abstraction, so a parameter carrying an argument list is refused here, where the author wrote it.
     * Left to run, {@code <T> { v: T<text> }} substitutes whatever {@code T} binds into a {@code type_name}
     * slot, and an argument-bearing binding lands there as {@code type_ref}'s record form -- reported one
     * phase later as a wire-vocabulary mismatch, which names none of what the author did.
     */
    private static void checkArity(TypeRef ref, Map<String, TypeDefinition> namespace,
                                    List<String> ownParameters, String context) {
        if (ownParameters.contains(ref.name())) {
            if (!ref.arguments().isEmpty()) {
                throw new TsonSchemaValidationException(context + ": '" + ref.name() + "' is a type parameter "
                        + "applied to arguments -- a parameter stands for a type, never for a template, and "
                        + "§5.10 admits no head abstraction, so '" + ref.name() + "<...>' is no form. Name the "
                        + "template and apply that, or take the applied type as the parameter instead");
            }
            return;
        }
        TypeDefinition referenced = namespace.get(ref.name());
        if (referenced == null) {
            return; // reached only through the structure-namespace fallback, which the caller already allowed
        }
        int declared = referenced.parameters().size();
        int supplied = ref.arguments().size();
        if (declared == supplied) {
            return;
        }
        if (declared == 0) {
            throw new TsonSchemaValidationException(context + ": '" + ref.name() + "' declares no type "
                    + "parameters, so '" + ref.name() + "<...>' applies arguments to something that takes "
                    + "none (§5.10); drop the argument list");
        }
        if (supplied == 0) {
            throw new TsonSchemaValidationException(context + ": '" + ref.name() + "' is a template taking "
                    + declared + " type argument" + (declared == 1 ? "" : "s") + " " + referenced.parameters()
                    + ", and a template is not a type until it is applied -- write '" + ref.name()
                    + "<...>' with its arguments (§5.10)");
        }
        throw new TsonSchemaValidationException(context + ": '" + ref.name() + "' takes " + declared
                + " type argument" + (declared == 1 ? "" : "s") + " " + referenced.parameters() + ", but "
                + supplied + " " + (supplied == 1 ? "was" : "were") + " applied (§5.10)");
    }
}
