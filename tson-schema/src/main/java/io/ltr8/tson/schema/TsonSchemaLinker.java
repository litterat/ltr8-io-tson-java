package io.ltr8.tson.schema;

import io.ltr8.tson.schema.registry.CanonicalIdentity;
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
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.Extern;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.Ipv4Type;
import io.ltr8.tson.schema.meta.Ipv6Type;
import io.ltr8.tson.schema.meta.MacType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p>Public, and meant to be called directly by anything orchestrating the pipeline -- including from other
 * modules ({@code tson-compiler}'s own registries link a schema before registering it, same as any other
 * caller). {@link CanonicalIdentity} stays in {@code .registry}, internal by convention: it was never a
 * named pipeline stage, just how registry lookups compare identities.
 *
 * <p><b>No materialization.</b> An argument-bearing {@code type_ref} does not become a synthesized entry
 * here -- {@code SchemaDesugarer} (in {@code tson-compiler}) has already turned every sugar form and generic
 * application into a real declaration plus a bare reference, one phase earlier, where the AST and the
 * governing meta's own constructor vocabulary are both still in hand. This module cannot reach {@code
 * tson-compiler}, so doing it here meant hand-written per-shape assemblers that covered {@code array}/{@code
 * set} and nothing else; the phase that replaced them binds the constructor generically and so covers every
 * shape. What survives into an entry the linker sees is therefore a bare name in every position except one:
 * a parameterized declaration's own body, which legitimately references its own parameters ({@code
 * array<T>}) and is validated, not rewritten.
 *
 * <p><b>Type-parameter exception:</b> a bare name is valid if it resolves in the schema's own
 * namespace, or if it's one of the checked entry's own declared {@code parameters} -- load-bearing
 * for every parameterized declaration (`array`, `set`, `map`, `array_min`, `array_max`,
 * `array_ranged`), whose own {@code source}/body positions reference their own type parameter by
 * bare name (`array<T>`), not a real other entry.
 *
 * <p><b>{@code !!import} merging (Part 2 §2.2.3).</b> The final namespace a schema is checked
 * against is built in two stages, in this order: (1) every {@code !!import}'s own entries, in
 * declaration order, looked up via {@code loader} by canonical identity -- shallow, per §2.2.3
 * ("only the entries declared in the imported schema's own body are imported... entries the
 * imported schema itself brought in via its own {@code !!import} directives are not transitively
 * included"), which falls out for free here since {@code loader} hands back an already-registered,
 * already-flattened {@code TsonSchema} and only *its* {@code entries()} are read, never its own
 * {@code imports()}; (2) this schema's own entries, exactly as resolved. A name collision -- between two
 * imports, or between an import and a local declaration -- is a resolver error (§2.2.3), checked as each
 * stage is merged in, not after the fact. <b>Merged entries keep their home namespace</b>: an
 * imported {@code TypeDefinition} is carried in exactly as the imported schema resolved it, never
 * re-validated against the importer's own namespace -- only the *importer's own* new material gets
 * validated here.
 */
public final class TsonSchemaLinker {

    private TsonSchemaLinker() {
    }

    /**
     * Links {@code bootstrap} -- meta-kernel's own raw, pre-loaded bootstrap output (see {@link
     * TsonSchema#bootstrap()}'s own Javadoc for why it can't be resolved the ordinary way). Moved
     * here from {@link TsonSchemaRegistry} (2026-07-27) once that class became a pure store again --
     * this method belongs with the verb it performs, not with a registry it deliberately never
     * stores its own result in (see below).
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
        return link(bootstrap, null);
    }

    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader) {
        Map<String, TypeDefinition> merged = mergeImports(schema.imports(), loader);

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
                loader == null ? Optional.empty() : loader.load(CanonicalIdentity.of(schema.meta()));
        checkMayGovern(schema, governingMeta);
        Map<String, TypeDefinition> structureNamespace = governingMeta
                .<Map<String, TypeDefinition>>map(linked -> linked.schema().entries()).orElse(Map.of());

        Set<String> localNames = new LinkedHashSet<>();

        Boolean constructorsAllowed = null;
        for (Map.Entry<String, TypeDefinition> entry : schema.entries().entrySet()) {
            if (merged.containsKey(entry.getKey())) {
                throw new TsonSchemaValidationException(
                        "'" + entry.getKey() + "' collides with an entry of the same name brought in by !!import");
            }
            TypeDefinition def = entry.getValue();
            if (def.constructor()) {
                if (constructorsAllowed == null) {
                    constructorsAllowed = isMetaKernelGoverned(schema);
                }
                if (!constructorsAllowed) {
                    throw new TsonSchemaValidationException("'" + entry.getKey() + "' declares a type constructor "
                            + "(the '~' marker), but '" + schema.id() + "' is not governed directly by the "
                            + "meta-kernel (its own !!meta is '" + schema.meta() + "') -- only a schema chaining "
                            + "to meta-kernel.tn directly may declare new constructors (§2.2.2's "
                            + "meta-programming case); an ordinary type library or application schema may only "
                            + "apply or refine constructors it doesn't declare itself");
                }
            }
            merged.put(entry.getKey(), def);
            localNames.add(entry.getKey());
        }

        merged = computeSubtypes(merged, localNames);
        merged = computeDisjointness(merged);

        for (Map.Entry<String, TypeDefinition> entry : merged.entrySet()) {
            validateEntry(entry.getKey(), entry.getValue(), merged, structureNamespace);
        }

        return new TsonLinkedSchema(new TsonSchema(schema.id(), schema.meta(), schema.imports(),
                withNameAnnotations(merged, schema, loader), schema.bootstrap()));
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
                Optional<TsonLinkedSchema> imported = loader.load(CanonicalIdentity.of(importUri));
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
        return CanonicalIdentity.of(schema.meta()).equals(CanonicalIdentity.of(TsonBundledSchemas.META_KERNEL_ID));
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

    /** {@code def} plus {@code newSubtypes}, unioned with whatever subtypes it already had -- a new {@link TypeDefinition}, {@code def} itself untouched. */
    private static TypeDefinition withAddedSubtypes(TypeDefinition def, Set<String> newSubtypes) {
        Set<String> combined = new LinkedHashSet<>(def.subtypes());
        combined.addAll(newSubtypes);
        return new TypeDefinition(def.source(), def.kind(), def.parameters(), def.constructor(),
                def.supertypes(), List.copyOf(combined), def.disjoint(), def.body());
    }

    /**
     * Derives {@link TypeDefinition#disjoint} for every choice entry (§5.4), over the fully-merged,
     * subtypes-populated namespace -- a namespace-wide pass, like {@link #computeSubtypes}, since a
     * variant's own kind/family/bounds/supertypes are only knowable with every entry resolved. See {@link
     * ChoiceDisjointness} for the derivation and its deliberately partial scope.
     */
    private static Map<String, TypeDefinition> computeDisjointness(Map<String, TypeDefinition> merged) {
        Map<String, TypeDefinition> result = new LinkedHashMap<>(merged);
        for (Map.Entry<String, TypeDefinition> entry : merged.entrySet()) {
            if (entry.getValue().body() instanceof ChoiceBody choice) {
                TypeDefinition def = entry.getValue();
                result.put(entry.getKey(), new TypeDefinition(def.source(), def.kind(), def.parameters(),
                        def.constructor(), def.supertypes(), def.subtypes(),
                        ChoiceDisjointness.derive(choice, merged), def.body(), def.position()));
            }
        }
        return result;
    }

    /**
     * Stage 1: every {@code !!import}'s own entries, in declaration order, brought in as-is
     * (§2.2.3's "merged entries keep their home namespace" -- no re-resolution here).
     */
    private static Map<String, TypeDefinition> mergeImports(List<String> imports, TsonSchemaLoader loader) {
        Map<String, TypeDefinition> merged = new LinkedHashMap<>();
        for (String importUri : imports) {
            String importIdentity = CanonicalIdentity.of(importUri);
            TsonLinkedSchema imported = loader.load(importIdentity).orElseThrow(() -> new TsonSchemaValidationException(
                    "!!import '" + importUri + "' is not registered"));
            for (Map.Entry<String, TypeDefinition> entry : imported.schema().entries().entrySet()) {
                if (merged.containsKey(entry.getKey())) {
                    throw new TsonSchemaValidationException(
                            "'" + entry.getKey() + "' is declared by more than one !!import");
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    // ── Validation ───────────────────────────────────────────────────────

    private static void validateEntry(String name, TypeDefinition def, Map<String, TypeDefinition> namespace,
                                       Map<String, TypeDefinition> structureNamespace) {
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
            Map<String, TypeDefinition> sourceLookup = structureNamespace.isEmpty() ? namespace
                    : mergeWithFallback(namespace, structureNamespace);
            validateTypeRef(def.source().get(), sourceLookup, def.parameters(), "'" + name + "' source");
        }
        // A supertype gets the same structure-namespace fallback as `source`, and for the same reason: it is
        // not an author-written reference but the residue of one. A §8.2 template instantiation keeps "the
        // template's supertypes, unchanged by substitution", and a kernel template's chain begins at the
        // constructor it refines and continues into the base kinds -- a closure of `array_ranged` materialised
        // in a user schema carries [array, product, top], none of which that schema can name. §3.3.2 confines
        // author-written type-refs to the type-name namespace; it does not speak to a derived chain, and §8.2
        // mandates one that routinely leaves it. The spec does not reconcile the two -- see SPEC-FEEDBACK.md.
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
                    validateTypeRef(field.type(), namespace, ownParameters,
                            "'" + entryName + "' field '" + field.name() + "'");
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
            case Reference ref -> validateTypeRef(ref.target(), namespace, ownParameters, "'" + entryName + "'");
            case MapBody m -> {
                validateTypeRef(m.keyType(), namespace, ownParameters, "'" + entryName + "' key_type");
                validateTypeRef(m.valueType(), namespace, ownParameters, "'" + entryName + "' value_type");
            }
            case ArrayBody a -> validateTypeRef(a.elementType(), namespace, ownParameters,
                    "'" + entryName + "' element_type");
            case TupleBody t -> {
                int index = 0;
                for (TupleElement element : t.elements()) {
                    validateTypeRef(element.elementType(), namespace, ownParameters,
                            "'" + entryName + "' element[" + index + "]");
                    index++;
                }
            }
            case ChoiceBody c -> {
                int index = 0;
                for (TypeRef variant : c.variants()) {
                    validateTypeRef(variant, namespace, ownParameters, "'" + entryName + "' variant[" + index + "]");
                    index++;
                }
            }
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
        }
    }

    /** {@code fallback} entries, overridden by {@code primary} on collision -- {@code primary} isn't mutated. */
    private static Map<String, TypeDefinition> mergeWithFallback(Map<String, TypeDefinition> primary,
                                                                   Map<String, TypeDefinition> fallback) {
        Map<String, TypeDefinition> combined = new LinkedHashMap<>(fallback);
        combined.putAll(primary);
        return combined;
    }

    private static void validateTypeRef(TypeRef ref, Map<String, TypeDefinition> namespace, List<String> ownParameters,
                                         String context) {
        if (!namespace.containsKey(ref.name()) && !ownParameters.contains(ref.name())) {
            throw new TsonSchemaValidationException(context + " has an unresolved reference '" + ref.name() + "'");
        }
        for (TypeArgument arg : ref.arguments()) {
            if (arg instanceof TypeArgument.Ref nested) {
                validateTypeRef(nested.ref(), namespace, ownParameters, context);
            }
            // TypeArgument.Value is a literal token, not a type reference -- nothing to validate.
        }
    }
}
