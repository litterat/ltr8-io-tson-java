package io.ltr8.tson.base;
import io.ltr8.tson.base.TsonConfig;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.base.policy.FetchPolicy;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.base.policy.UnicodePolicy;
import io.ltr8.tson.base.bind.AtomContext;
import java.util.Objects;

/**
 * What a deployment states about reading TSON, as one immutable value -- and <b>the same value whichever
 * encoding reads it</b>.
 *
 * <p>Three questions, each answered by a value of its own:
 *
 * <ul>
 *   <li>{@link #withProcessorPolicy} -- what this processor will <b>admit and spend</b>: [TSON-DATA] §8.2's
 *       two Unicode surfaces and §9.1's resource bounds. A denial rule over documents, meaningful with no
 *       schema and no Java class in hand.</li>
 *   <li>{@link #withSchemaAccess} -- where it may <b>obtain a schema</b>, and under what constraints. Deny
 *       by default: nothing beyond the bundled standard library is fetchable until this says so.</li>
 *   <li>{@link #withDataBindContext} -- which <b>Java classes</b> the schema's types bind to.</li>
 * </ul>
 *
 * <p>{@link #withMetaNameBinder} is a fourth setting but not a fourth question: it is one seam into a
 * context the consumer never holds. {@code withDataBindContext} binds the <em>data</em> a schema describes
 * ({@code order} -> {@code Order}); this adds names to the binding of a governing meta's own
 * <em>vocabulary</em> ({@code operation} -> {@code Operation}), which the library otherwise builds itself.
 * One name means different things on the two sides, so one namespace holding both would collide the first
 * time a schema type and a meta-layer constructor shared a name.
 *
 * <p><b>It lives here, and construction does not.</b> A configuration is a value: it names what a
 * deployment chose and knows nothing about how a processor is assembled from it. Building one names the
 * compiler's own registry and so belongs with the engine -- {@code Tson.of(config)} -- which is what lets
 * this be shared rather than duplicated per encoding. Every setting returns a new instance, so a
 * configuration may be handed out, kept, and derived from without the holder losing what they stated.
 *
 * <p><b>What is deliberately not here</b> is anything a reader can decide for itself. Which schema a read
 * validates against, where its diagnostics go, whether an undeclared field is discarded -- all of those are
 * per-reader derivations, because two endpoints of one application legitimately differ on them.
 */
public final class TsonConfig {

    private final DataBindContext dataBindContext;
    private final SchemaAccess schemaAccess;
    private final DataNameBinder metaNameBinder;
    private final ProcessorPolicy policy;

    TsonConfig(DataBindContext dataBindContext, SchemaAccess schemaAccess, ProcessorPolicy policy, DataNameBinder metaNameBinder) {
        this.dataBindContext = dataBindContext;
        this.schemaAccess = schemaAccess;
        this.policy = policy;
        this.metaNameBinder = metaNameBinder;
    }

    public static TsonConfig defaults() {
        return new TsonConfig( AtomContext.defaultContext(), SchemaAccess.registeredOnly(), ProcessorPolicy.defaults(), null);
    }

    public SchemaAccess schemaAccess() {
        return schemaAccess;
    }

    public DataBindContext dataBindContext() {
        return dataBindContext;
    }

    public DataNameBinder metaNameBinder() {
        return metaNameBinder;
    }

    public ProcessorPolicy processorPolicy() {
        return policy;
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
     * TsonConfig.defaults().withSchemaAccess(SchemaAccess.fileSchemas("schemas.example.com", dir));
     * }</pre>
     *
     * <p>It is the schema half of what a deployment constrains, {@link #withProcessorPolicy} being the reading
     * half. Two values because they are consumed by different subsystems: a reader applies one and fetches
     * nothing, the loader applies the other and reads no documents.
     */
    public TsonConfig withSchemaAccess(SchemaAccess schemaAccess) {
        return new TsonConfig(this.dataBindContext, Objects.requireNonNull(schemaAccess, "schemaAccess"), this.policy, this.metaNameBinder);
    }

    /**
     * The {@link DataBindContext} a processor built from this configuration binds its object readers and
     * writers against -- defaults to {@link AtomContext#defaultContext()}.
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
     * <p>Unrelated to (and never overriding) the object-binding-mode context construction always uses
     * internally to resolve the standard library itself -- see {@code Tson}'s own Javadoc for why that one's
     * mode is fixed, and {@link #metaNameBinder} for the one thing about it a consumer may extend.
     */
    public TsonConfig withDataBindContext(DataBindContext dataBindContext) {
        return new TsonConfig(Objects.requireNonNull(dataBindContext, "dataBindContext"), this.schemaAccess, this.policy, this.metaNameBinder);
    }

    /**
     * The names a consumer's own <b>meta layer</b> adds -- consulted when resolving a schema whose governing
     * meta declares constructors of its own, so that {@code search => !operation { ... }} can bind to that
     * consumer's own {@code @Typename("operation")} class. Defaults to unset: the kernel's own vocabulary
     * alone, which is every schema governed by the bundled meta.tn.
     *
     * <p><b>Composed over the library's own binder, never replacing it</b> ({@code SchemaMetaNameBinder.contextExtendedWith}): {@code record}/{@code enum}/{@code integer_type} and the
     * rest resolve first, and this binder answers only for a name the kernel does not declare. So it adds
     * names and gives up nothing -- in particular not the object-binding mode the standard library must be
     * compiled in (see {@code Tson}), which construction fixes and this does not touch.
     *
     * <p>Distinct from {@link #dataBindContext}, which binds the <em>data</em> a schema describes; this
     * binds the <em>schema vocabulary</em> a meta describes. A consumer with both supplies both.
     */
    public TsonConfig withMetaNameBinder(DataNameBinder metaNameBinder) {
        return new TsonConfig(this.dataBindContext, this.schemaAccess, this.policy, Objects.requireNonNull(metaNameBinder, "metaNameBinder"));
    }

    /**
     * Everything about a read that is in neither the document nor the schema -- the two [TSON-DATA] §8.2
     * Unicode surfaces and §9.1's resource bounds -- as the one value a deployment states. Defaults to
     * {@link ProcessorPolicy#defaults()}.
     *
     * <p><b>This is the setter to reach for, and the three below are its components.</b> A deployment's
     * constraints are one decision made in one place -- typically a platform library handing an application
     * a policy it does not assemble itself -- so the primary form takes the whole value and
     * {@link #withIdentifierPolicy}/{@link #withTokenPolicy}/{@link #withLimits} fold into it. That is also what lets
     * one policy configure both encodings: {@code Json.withProcessorPolicy} takes this same value, and a
     * deployment that states its constraints twice has two places to get them wrong.
     *
     * <p>Reported back by {@code Tson.processorPolicy()}, by each reader, and by {@code tson policy}.
     *
     * <p><b>In code on purpose.</b> A security policy read from the environment is ambient authority: a CI
     * config, a container image or a dependency calling {@code setenv} would change it with no diff and
     * nothing in review, it would be invisible at the call site, and it would be process-global, so a
     * library embedding this one could not hold its own. A method call is greppable, diffable and scoped to
     * the instance that holds it.
     */
    public TsonConfig withProcessorPolicy(ProcessorPolicy policy) {
        return new TsonConfig(this.dataBindContext, this.schemaAccess, Objects.requireNonNull(policy, "policy"), this.metaNameBinder);
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
     * <p>One component of {@link #withProcessorPolicy}, which is where a deployment stating all three at once
     * says so.
     */
    public TsonConfig withIdentifierPolicy(UnicodePolicy identifierPolicy) {
        return new TsonConfig(this.dataBindContext, this.schemaAccess,  policy.withIdentifierPolicy(Objects.requireNonNull(identifierPolicy, "identifierPolicy")), this.metaNameBinder);
    }

    /**
     * UTS #39 §5.2 over <b>every token a read pulls off the stream</b>, values included
     * ([TSON-DATA] §8.2's "Values") -- {@link #withIdentifierPolicy}'s peer on the other surface.
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
     * whatever {@link #withIdentifierPolicy} says. The setter is named for the surface it acts on rather than
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
     * <p>One component of {@link #withProcessorPolicy}, which is where a deployment stating all three at once
     * says so.
     *
     * @throws IllegalArgumentException if {@code tokenPolicy} is per-segment -- {@code _} and {@code -} are
     *         word separators by convention in a name and ordinary characters in a value, so segmenting one
     *         admits UTS #39's own {@code Toys-Я-Us}, the spoof a strict token policy exists to refuse.
     *         {@link ProcessorPolicy} enforces it, so every route to a policy refuses it alike
     */
    public TsonConfig withTokenPolicy(UnicodePolicy tokenPolicy) {
        return new TsonConfig(this.dataBindContext, this.schemaAccess,  policy.withTokenPolicy(Objects.requireNonNull(tokenPolicy, "tokenPolicy")), this.metaNameBinder);
    }

    /**
     * The [TSON-DATA] §9.1 resource limits every read off this instance applies -- {@link
     * LimitsPolicy#defaults()} unless this says otherwise.
     *
     * <p><b>In code, for {@link #withProcessorPolicy}'s reason.</b> §9.1 requires the bounds be configurable;
     * taking them from the ambient environment instead would let a deployment's behaviour change without any
     * statement of it in the deployment, which is the property that makes a refusal explainable. A caller
     * that raises the depth is choosing to spend more Java stack on one document, and {@link
     * LimitsPolicy#withMaxDepth} says what the ceiling on that choice actually is.
     *
     * <p>One component of {@link #withProcessorPolicy}, which is where a deployment stating all three at once
     * says so.
     *
     * <p>Reported back by {@code Tson.limitsPolicy()} and by each reader
     * ({@code TsonTreeReader.limitsPolicy()}), which is where a derived reader's own {@code withLimits}
     * shows.
     */
    public TsonConfig withLimits(LimitsPolicy limits) {
        return new TsonConfig(this.dataBindContext, this.schemaAccess,  policy.withLimits(Objects.requireNonNull(limits, "limits")), this.metaNameBinder);
    }

}
