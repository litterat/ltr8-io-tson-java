package io.ltr8.tson;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.policy.FetchPolicy;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.BindMismatchException;
import io.ltr8.tson.compiler.*;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.base.policy.UnicodePolicy;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.compiler.TsonCompiledMetaRegistry;
import java.util.Map;
import java.util.Objects;

/**
 * Configures and builds a {@link Tson} -- reached via {@link Tson#builder()}, never constructed
 * directly. {@link #schemaAccess} says where user schemas beyond the bundled standard library come from;
 * {@link #dataBindContext} says which Java classes the schema's types bind to and
 * {@link #lenientBinding} whether they must account for every field; {@link #metaNameBinder} binds a governing meta's own constructors;
 * {@link #processorPolicy} states what this processor will admit as a name and spend on a document; and
 * {@link #build()}
 * constructs a {@link TsonCompiledMetaRegistry} and has it
 * load the bundled meta-kernel/meta.tn/core.tn standard library, then wraps it as a {@link Tson}.
 *
 * <p>The two binding options are deliberately separate and never merged. {@link #dataBindContext} binds
 * <em>data</em> ({@code order} -> {@code Order}); {@link #metaNameBinder} binds a governing meta's own
 * <em>vocabulary</em> ({@code operation} -> {@code Operation}). One name means different things on the two
 * sides, so one namespace holding both would collide the first time a schema type and a meta-layer
 * constructor shared a name.
 */
public final class TsonConfig {

    private DataBindContext dataBindContext = AtomContext.defaultContext();
    private boolean strictBinding = true;
    private SchemaAccess schemaAccess = SchemaAccess.registeredOnly();
    private DataNameBinder metaNameBinder;
    private ProcessorPolicy policy = ProcessorPolicy.defaults();

    TsonConfig() {
    }

    /**
     * Where this deployment may obtain a schema beyond the bundled standard library, and under what
     * constraints -- the {@link SchemaSource} and the {@link FetchPolicy} governing it, as one value.
     * Defaults to {@link SchemaAccess#registeredOnly()}: nothing beyond the standard library is fetchable.
     *
     * <p><b>The vocabulary for stating one lives on {@link SchemaAccess} and not here</b>, so there is one
     * place to learn it and one place it can drift: {@link SchemaAccess#httpSchemas} and
     * {@link SchemaAccess#fileSchemas} are the one-call forms of the two sources this library ships,
     * {@link SchemaAccess#of} wraps a source of your own, and {@link SchemaAccess#builder()} is the general
     * form -- a {@link FetchPolicy}, a mirror, a host mapped elsewhere. A second copy of those four on this
     * class would be a second surface to keep in step, and the mutual-exclusion rules among them would have
     * to be stated twice.
     *
     * <pre>{@code
     * Tson.builder().schemaAccess(SchemaAccess.fileSchemas("schemas.example.com", dir)).build();
     * }</pre>
     *
     * <p>It is the schema half of what a deployment constrains, {@link #processorPolicy} being the reading
     * half. Two values because they are consumed by different subsystems: a reader applies one and fetches
     * nothing, the loader applies the other and reads no documents.
     */
    public TsonConfig schemaAccess(SchemaAccess schemaAccess) {
        this.schemaAccess = Objects.requireNonNull(schemaAccess, "schemaAccess");
        return this;
    }

    /**
     * The {@link DataBindContext} the built {@link Tson}'s own {@link Tson#objectReader()}/{@link
     * Tson#objectWriter()} bind against -- defaults to {@link AtomContext#defaultContext()}.
     *
     * <p><b>The vocabulary for building one is {@code tson-bind}'s, not this class's</b>, so there is one
     * place to learn it and one place it can drift:
     *
     * <pre>{@code
     * DataBindContext.builder()
     *         .nameBinder(DataNameBinder.ofMap(Map.of("order", Order.class)).orElse(kernelVocabulary))
     *         .registerAtoms(AtomContext.hostTypes())
     *         .build()
     * }</pre>
     *
     * <p><b>A context's configuration is fixed once it is built</b>, so a context handed here cannot acquire
     * a binding later: {@code registerAtom} is {@link DataBindContext.Builder}'s and closes at
     * {@code build()}. What stays concurrent is the descriptor cache, where two threads resolving one class
     * both do the work and one answer wins -- duplicated work on a race, never duplicated state.
     *
     * <p>Unrelated to (and never overriding) the object-binding-mode context {@link #build()} always uses
     * internally to resolve the standard library itself -- see {@link Tson}'s own Javadoc for why that one's
     * mode is fixed, and {@link #metaNameBinder} for the one thing about it a consumer may extend.
     */
    public TsonConfig dataBindContext(DataBindContext dataBindContext) {
        this.dataBindContext = Objects.requireNonNull(dataBindContext, "dataBindContext");
        return this;
    }

    /**
     * Lets a bound class hold fewer fields than the schema declares, silently -- off by default.
     *
     * <p>By default the two must agree, and a mismatch is a {@link BindMismatchException} when the schema is
     * compiled in bind mode, which is startup for anything compiling its schemas once. <b>That is why this
     * is configuration and not a reader derivation</b>: the check compares a compiled schema against a
     * class, so a reader derived afterwards has no answer left to give. Which field a <em>document</em> may
     * carry beyond its class is the different question {@code TsonObjectReader.ignoringUnknownFields} asks,
     * per reader, at read time.
     *
     * <p>The strict default is the asymmetry between the two ways of being wrong: a strict reader that is
     * wrong says so at startup, in one message naming both sides, and is fixed in minutes; a lenient one
     * that is wrong drops a value from every document and surfaces much later as a field that mysteriously
     * holds its default.
     *
     * <p>Leniency is a real position and not merely an escape hatch -- versioned evolution, where a v1
     * consumer deliberately reads a v2 document and means to ignore what it does not know. This is where
     * that intention is written down, and it is the only path on which a field is dropped at all, every
     * other mismatch being settled before a document exists. It is silent by necessity: reporting abandons
     * the construction, so a lenient reader that reported would hand back nothing for exactly the documents
     * it exists to accept.
     *
     * <p>The narrower alternative is {@code @Unbound} on the one component that is the class's own business
     * rather than the wire's.
     */
    public TsonConfig lenientBinding() {
        this.strictBinding = false;
        return this;
    }

    /**
     * The names a consumer's own <b>meta layer</b> adds -- consulted when resolving a schema whose governing
     * meta declares constructors of its own, so that {@code search => !operation { ... }} can bind to that
     * consumer's own {@code @Typename("operation")} class. Defaults to unset: the kernel's own vocabulary
     * alone, which is every schema governed by the bundled meta.tn.
     *
     * <p><b>Composed over the library's own binder, never replacing it</b> ({@link
     * SchemaMetaNameBinder#contextExtendedWith}): {@code record}/{@code enum}/{@code integer_type} and the
     * rest resolve first, and this binder answers only for a name the kernel does not declare. So it adds
     * names and gives up nothing -- in particular not the object-binding mode the standard library must be
     * compiled in (see {@link Tson}), which is what {@code build()} fixes and this does not touch.
     *
     * <p>Distinct from {@link #dataBindContext}, which binds the <em>data</em> a schema describes; this
     * binds the <em>schema vocabulary</em> a meta describes. A consumer with both supplies both.
     */
    public TsonConfig metaNameBinder(DataNameBinder metaNameBinder) {
        this.metaNameBinder = Objects.requireNonNull(metaNameBinder, "metaNameBinder");
        return this;
    }

    /**
     * Everything about a read that is in neither the document nor the schema -- the two [TSON-DATA] §8.2
     * Unicode surfaces and §9.1's resource bounds -- as the one value a deployment states. Defaults to
     * {@link ProcessorPolicy#defaults()}.
     *
     * <p><b>This is the setter to reach for, and the three below are its components.</b> A deployment's
     * constraints are one decision made in one place -- typically a platform library handing an application
     * a policy it does not assemble itself -- so the primary form takes the whole value and
     * {@link #identifierPolicy}/{@link #tokenPolicy}/{@link #limits} fold into it. That is also what lets
     * one policy configure both encodings: {@code Json.withProcessorPolicy} takes this same value, and a
     * deployment that states its constraints twice has two places to get them wrong.
     *
     * <p>Reported back by {@link Tson#processorPolicy()}, by each reader, and by {@code tson policy}.
     *
     * <p><b>In code on purpose.</b> A security policy read from the environment is ambient authority: a CI
     * config, a container image or a dependency calling {@code setenv} would change it with no diff and
     * nothing in review, it would be invisible at the call site, and it would be process-global, so a
     * library embedding this one could not hold its own. A method call is greppable, diffable and scoped to
     * the instance that holds it.
     */
    public TsonConfig processorPolicy(ProcessorPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /**
     * The UTS #39 §5.2 restriction level applied to every name a schema declares -- type names, record field
     * names, parameter names and enum members -- [TSON-DATA] §8.2's restricted-script rule, over the schema-layer
     * scopes [TSON-SCHEMA] §11.4 names.
     *
     * <p>The default is {@link UnicodePolicy#highlyRestrictive()} over a whole name: the strictest of §5.2's
     * practically deployable levels, and one it <em>names</em>, so the default is a position two
     * implementations agree on without reading this project's documents. It refuses a name that mixes
     * scripts, which is how a homograph reads as another name.
     *
     * <p><b>Reach for the unit before the level.</b> The default rejects ordinary compounds —
     * {@code id_}<i>пользователя</i>, {@code url_}<i>адрес</i>, {@code alpha_α} — because a Latin
     * abbreviation beside a word in another script is how identifiers are written outside English. Passing
     * {@code highlyRestrictive().perSegment()} applies the same level to each {@code _}/{@code -} delimited
     * segment, which admits all of those and still refuses every within-word homograph, because a homograph
     * has to sit inside a word to read as that word. That is a narrower rule, not a weaker posture, and it is
     * the relaxation to try first. Narrower still is {@code permitting(LATIN, CYRILLIC)}, for a deployment
     * that knows exactly which combination it means.
     *
     * <p>The two ways of switching it off are deliberately distinct: {@link UnicodePolicy#scriptsUnchecked()}
     * drops the script rule and keeps the identifier profile, while {@link UnicodePolicy#unrestricted()}
     * drops that too — §5.2's own level 6, which takes {@code Identifier_Status} with it and which §5.2
     * describes as a diagnostic tool.
     *
     * <p>One component of {@link #processorPolicy}, which is where a deployment stating all three at once
     * says so.
     */
    public TsonConfig identifierPolicy(UnicodePolicy identifierPolicy) {
        this.policy = policy.withIdentifierPolicy(Objects.requireNonNull(identifierPolicy, "identifierPolicy"));
        return this;
    }

    /**
     * UTS #39 §5.2 over <b>every token a read pulls off the stream</b>, values included
     * ([TSON-DATA] §8.2's "Values") -- {@link #identifierPolicy}'s peer on the other surface.
     * Defaults to {@link UnicodePolicy#unrestricted()}, which checks nothing.
     *
     * <p><b>The default is the opposite of the identifier default, for the same reason in each case.</b> A
     * declared name is an interface and a homograph in one is an attack, so names default to Highly
     * Restrictive. A value is data, and data may legitimately be anything -- a Greek quotation, a Cyrillic
     * display name -- so imposing a script rule on it would break ordinary documents to no end. Nothing is
     * checked here until a deployment says otherwise, and at the default no scan runs at all.
     *
     * <p><b>Raise it when values are more than payload:</b> a service that renders what it reads into a UI,
     * or matches it against a blocklist or an allowlist, faces on values exactly the spoofing surface
     * [TSON-DATA] §9.4 raises for names, and nothing in the series speaks to it.
     *
     * <p><b>A name is a token, so this also constrains names, and that is deliberate.</b> The check runs
     * before anything knows which tokens are names, so a token policy stricter than the identifier policy
     * subsumes it -- {@code tokenPolicy(asciiOnly())} has made this instance's identifiers ASCII-only too,
     * whatever {@link #identifierPolicy} says. The setter is named for the surface it acts on rather than
     * for the values it mostly affects, so that consequence is visible where it is configured.
     *
     * <p>The restriction level is the only rule available here, where for names it was the second
     * choice. Skeleton distinctness is a <em>relation</em> and needs a set to hold over; values have none --
     * two values in one array need not be distinguishable, and two values in different documents cannot be
     * compared at all. The per-string rule is what remains, which is the same reason it is right for a
     * browser judging a domain name.
     *
     * <p>{@link UnicodePolicy.Level#MINIMALLY_RESTRICTIVE} and {@link UnicodePolicy.Level#UNRESTRICTED}
     * collapse here: §5.2 says so directly, a token that is not a name having no identifier profile to drop.
     *
     * <p>One component of {@link #processorPolicy}, which is where a deployment stating all three at once
     * says so.
     *
     * @throws IllegalArgumentException if {@code tokenPolicy} is per-segment -- {@code _} and {@code -} are
     *         word separators by convention in a name and ordinary characters in a value, so segmenting one
     *         admits UTS #39's own {@code Toys-Я-Us}, the spoof a strict token policy exists to refuse.
     *         {@link ProcessorPolicy} enforces it, so every route to a policy refuses it alike
     */
    public TsonConfig tokenPolicy(UnicodePolicy tokenPolicy) {
        this.policy = policy.withTokenPolicy(Objects.requireNonNull(tokenPolicy, "tokenPolicy"));
        return this;
    }

    /**
     * The [TSON-DATA] §9.1 resource limits every read off this instance applies -- {@link
     * LimitsPolicy#defaults()} unless this says otherwise.
     *
     * <p><b>In code, for {@link #processorPolicy}'s reason.</b> §9.1 requires the bounds be configurable;
     * taking them from the ambient environment instead would let a deployment's behaviour change without any
     * statement of it in the deployment, which is the property that makes a refusal explainable. A caller
     * that raises the depth is choosing to spend more Java stack on one document, and {@link
     * LimitsPolicy#withMaxDepth} says what the ceiling on that choice actually is.
     *
     * <p>One component of {@link #processorPolicy}, which is where a deployment stating all three at once
     * says so.
     *
     * <p>Reported back by {@link Tson#limitsPolicy()} and by each reader ({@link
     * io.ltr8.tson.compiler.TsonTreeReader#limitsPolicy()}), which is where a derived reader's own {@code
     * withLimits} shows.
     */
    public TsonConfig limits(LimitsPolicy limits) {
        this.policy = policy.withLimits(Objects.requireNonNull(limits, "limits"));
        return this;
    }

    public Tson build() {
        // The resolution core is both the store and the on-demand loader; withStandardLibrary loads the
        // bundled meta-kernel/meta/core, and the access's source is consulted only for other URIs. It
        // compiles the standard library in object-binding mode -- the only mode that can (a DOM reader
        // can't resolve the !enum/!integer instances a meta-schema declares), which is why it takes the
        // bind context rather than a resolver that might be the wrong mode. The *mode* is what is fixed
        // here; which names that binder knows is metaNameBinder's to extend. Tson builds the per-mode read
        // registries (DOM and object-binding, the latter bound to dataBindContext) over this one core.
        DataBindContext schemaContext = metaNameBinder == null
                ? SchemaMetaNameBinder.defaultContext()
                : SchemaMetaNameBinder.contextExtendedWith(metaNameBinder);
        SchemaSource source = schemaAccess.source();
        TsonCompiledMetaRegistry core = TsonCompiledMetaRegistry.withStandardLibrary(
                schemaContext, source, policy.identifierPolicy());
        return new Tson(core, dataBindContext, strictBinding, policy);
    }
}
