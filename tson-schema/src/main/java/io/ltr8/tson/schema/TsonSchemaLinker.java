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
 * Turns a resolved-but-unlinked {@link TsonSchema} into a {@link TsonLinkedSchema} -- the "pass 2"
 * a schema goes through before {@code TsonSchemaRegistry#register} will accept it (2026-07-27, renamed
 * from {@code SchemaValidator}/{@code validate} on the user's own explicit direction, borrowing
 * standard compiler vocabulary for the whole pipeline: parse -&gt; resolve -&gt; link -&gt; register
 * -&gt; compile -&gt; read). What it does hasn't changed, only what it's called and what it returns:
 * flattens every {@code type_ref} with arguments into a real, named entry (so the result has no
 * dangling/unexpanded references -- the defining trait of a linked schema, same as a linker
 * resolving external symbols and instantiating templates), populates {@code
 * TypeDefinition.subtypes} (the reverse of {@code supertypes}, for *this schema's own merged view*
 * of every entry it can see, imported or local -- see {@link #computeSubtypes}'s own Javadoc for
 * why crediting a subtype onto an imported entry's copy here never touches the imported schema's
 * own separately-registered original), then checks that every reference anywhere in the schema
 * actually resolves.
 *
 * <p><b>Genuinely public now, unlike its predecessor</b> -- {@code SchemaValidator} was "not part
 * of the public API," an implementation detail {@code TsonSchemaRegistry#register} ran internally and
 * nothing else was meant to call. Renaming it to a real pipeline-stage name changes that on
 * purpose: {@code link} is now something a caller orchestrating the pipeline calls directly and
 * deliberately, same as {@code parse}/{@code resolve}/{@code compile} -- including from *other*
 * modules (e.g. {@code tson-parser}'s own {@code TsonCompiledRegistry}, which needs to link a
 * schema before registering it, exactly the same as any other caller). Moved out of {@code
 * io.ltr8.tson.schema.registry} into this package directly (2026-07-27) once that publicness was
 * settled -- living in a package whose own docs describe it as "private pass-2 machinery nothing
 * outside this module calls directly" was the one thing still contradicting it. {@link
 * CanonicalIdentity} stays behind in {@code .registry}, genuinely internal-by-convention -- it was
 * never a named pipeline stage, just an implementation detail of how registry lookups work.
 *
 * <p><b>Materialization is uniform</b> (a deliberate simplification confirmed with the user, not
 * Part 2 §8.2's literal text): *every* {@code type_ref} with a non-empty {@code arguments} list
 * gets a synthesized entry, regardless of whether the applied name is itself a constructor (like
 * {@code set}) or a genuine non-constructor template -- §8.2 says only the latter should
 * materialise. Bottom-up: a nested argument that's itself argument-bearing is materialized first,
 * so an outer entry's own synthesized name is built from an already-flattened application, and two
 * structurally-identical applications anywhere in the schema dedup to the same entry (record
 * equality on {@link TypeRef} is exactly the "flattened applications are structurally equal" test
 * §8.2 calls for). The synthesized entry's own shape is exactly {@link
 * TypeDefinition#reference(TypeRef)}'s existing one -- that method's own Javadoc already flagged
 * this gap ("this resolver doesn't materialise instantiation entries yet, so target is reused as
 * both source and (as a placeholder) body.target until that exists").
 *
 * <p><b>{@code TypeDefinition.source} is never itself materialized</b>, even when it carries
 * arguments (e.g. {@code set}'s own {@code source: array<T>}): it's provenance -- how this entry
 * was itself derived -- not a field consuming another type, so it's validated (a name must still
 * resolve) but never rewritten into a separate synthetic entry.
 *
 * <p><b>Type-parameter exception:</b> a bare name is valid if it resolves in the schema's own
 * namespace, or if it's one of the checked entry's own declared {@code parameters} -- load-bearing
 * for every parameterized declaration (`array`, `set`, `map`, `array_min`, `array_max`,
 * `array_ranged`), whose own {@code source}/body positions reference their own type parameter by
 * bare name (`array<T>`), not a real other entry.
 *
 * <p><b>{@code !!import} merging (Part 2 §2.2.3).</b> The final namespace a schema is checked
 * against is built in three stages, in this order: (1) every {@code !!import}'s own entries, in
 * declaration order, looked up via {@code loader} by canonical identity -- shallow, per §2.2.3
 * ("only the entries declared in the imported schema's own body are imported... entries the
 * imported schema itself brought in via its own {@code !!import} directives are not transitively
 * included"), which falls out for free here since {@code loader} hands back an already-registered,
 * already-flattened {@code TsonSchema} and only *its* {@code entries()} are read, never its own
 * {@code imports()}; (2) this schema's own entries, resolved/materialized exactly as with no
 * imports; (3) every newly synthesized entry from step 2. A name collision -- between two imports,
 * or between an import and a local declaration -- is a resolver error (§2.2.3), checked as each
 * stage is merged in, not after the fact. <b>Merged entries keep their home namespace</b>: an
 * imported {@code TypeDefinition} is carried in exactly as the imported schema resolved it, never
 * re-validated or re-materialized against the importer's own namespace -- only the *importer's own*
 * new material (stage 2 and 3) gets resolved/validated here.
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

        // Full lookup namespace for materialization's own constructor lookups (see #instantiate) --
        // imports plus this schema's own already-resolved (pre-rewrite) entries. Deliberately built
        // once, up front, rather than relying on materialization order within `merged`: a
        // constructor like `array` needs to be found regardless of whether *its own* declaration
        // has been reached yet in the loop below (it usually hasn't -- real fixtures apply array
        // sugar on fields declared well before `array` itself).
        Map<String, TypeDefinition> namespace = new LinkedHashMap<>(merged);
        namespace.putAll(schema.entries());

        // The governing meta-schema's own namespace, one hop via !!meta -- distinct from !!import
        // (which flattens another schema's entries into *this* schema's own returned entries()).
        // !!meta only says "this schema's own vocabulary/constructors come from that other schema";
        // it never merges anything in, and (§3.3.2) it's never consulted for an ordinary type-ref --
        // only at the constructor roles §3.3.1 lists: constructor-application targets, generic-
        // application heads, and sugar-form desugar targets. Used as a lookup fallback in exactly
        // those two spots: here, for materialization's own constructor lookup (every argument-bearing
        // type-ref `instantiate` sees is, by construction, one of those roles); and, narrowly, for
        // `source` validation below (`validateEntry`'s own `sourceLookup`) -- everywhere else
        // (field/key/value/element types, supertypes, subtypes, choice variants) stays type-name-
        // namespace-only (`merged`/`namespace`), per §3.3.2's explicit "NOT extended by the structure
        // namespace". Local/imported names always win on collision, and none of this schema's own
        // entries() ever gain a structure-namespace entry as a side effect. Empty if !!meta isn't
        // registered yet (e.g. meta-kernel's own self-referential !!meta, mid-registration) --
        // lookups then behave exactly as before this fallback existed.
        Map<String, TypeDefinition> structureNamespace = loader == null ? Map.of()
                : loader.load(CanonicalIdentity.of(schema.meta()))
                        .map(linked -> linked.schema().entries()).orElse(Map.of());
        Map<String, TypeDefinition> lookup = new LinkedHashMap<>(structureNamespace);
        lookup.putAll(namespace);

        Map<TypeRef, String> materializedNames = new LinkedHashMap<>();
        Map<String, TypeDefinition> synthesized = new LinkedHashMap<>();
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
            Top rewrittenBody = rewriteBody(def.body(), materializedNames, synthesized, lookup);
            merged.put(entry.getKey(), new TypeDefinition(def.source(), def.kind(), def.parameters(),
                    def.constructor(), def.supertypes(), def.subtypes(), def.disjoint(), rewrittenBody));
            localNames.add(entry.getKey());
        }
        merged.putAll(synthesized);
        localNames.addAll(synthesized.keySet());

        merged = computeSubtypes(merged, localNames);

        for (Map.Entry<String, TypeDefinition> entry : merged.entrySet()) {
            validateEntry(entry.getKey(), entry.getValue(), merged, structureNamespace);
        }

        return new TsonLinkedSchema(new TsonSchema(schema.id(), schema.meta(), schema.imports(), merged, schema.bootstrap()));
    }

    /**
     * Whether {@code schema} is entitled to declare its own {@code ~}-marked constructors -- true
     * only if its own {@code !!meta} target is exactly {@link TsonBundledSchemas#META_KERNEL_ID}, the *specific*
     * meta-kernel this library's own compiled-reader machinery is built against, not merely "some
     * self-referencing schema." This is stricter than structural self-reference alone: every
     * resolved {@code TypeDefinition.body} and every {@code !instance} construction (`!enum`,
     * `!integer_type`, ...) is only interpretable because a matching type constructor is declared in
     * *this* meta-kernel specifically -- {@code TsonParserFactoryRegistry}/{@code AtomTypeParser}/
     * {@code RecordParser} (in {@code tson-parser}) are Java code hard-wired to this one meta-kernel's
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

    // ── Materialization ──────────────────────────────────────────────────

    private static Top rewriteBody(Top body, Map<TypeRef, String> materializedNames,
                                    Map<String, TypeDefinition> synthesized, Map<String, TypeDefinition> namespace) {
        return switch (body) {
            case RecordBody r -> {
                List<RecordField> fields = new ArrayList<>(r.fields().size());
                for (RecordField field : r.fields()) {
                    fields.add(new RecordField(field.name(),
                            materialize(field.type(), materializedNames, synthesized, namespace),
                            field.state(), field.value(), field.valueParam()));
                }
                yield new RecordBody(r.supertypes(), fields, r.groups());
            }
            case Reference ref -> new Reference(materialize(ref.target(), materializedNames, synthesized, namespace));
            case MapBody m -> new MapBody(
                    materialize(m.keyType(), materializedNames, synthesized, namespace),
                    materialize(m.valueType(), materializedNames, synthesized, namespace),
                    m.minItems(), m.maxItems());
            case ArrayBody a -> new ArrayBody(
                    materialize(a.elementType(), materializedNames, synthesized, namespace),
                    a.state(), a.unordered(), a.uniqueItems(), a.minItems(), a.maxItems());
            case TupleBody t -> {
                List<TupleElement> elements = new ArrayList<>(t.elements().size());
                for (TupleElement element : t.elements()) {
                    elements.add(new TupleElement(
                            materialize(element.elementType(), materializedNames, synthesized, namespace),
                            element.state()));
                }
                yield new TupleBody(elements);
            }
            case ChoiceBody c -> {
                List<TypeRef> variants = new ArrayList<>(c.variants().size());
                for (TypeRef variant : c.variants()) {
                    variants.add(materialize(variant, materializedNames, synthesized, namespace));
                }
                yield new ChoiceBody(variants);
            }
            case Unit u -> u;
            case EnumBody e -> e;
            case IntegerType i -> i;
            case TextType t -> t;
            case UriType u -> u;
            case RegexType r -> r;
            case DecimalType d -> d;
            case FloatType f -> f;
            case RationalType r -> r;
            case UuidType u -> u;
            case BinaryType b -> b;
            case DateType d -> d;
            case TimeType t -> t;
            case DateTimeType d -> d;
            case DurationType d -> d;
            case Cidr4Type c -> c;
            case Cidr6Type c -> c;
            case EmailType e -> e;
            case MacType m -> m;
            case Ipv4Type i -> i;
            case Ipv6Type i -> i;
            case ComplexType c -> c;
            case UnknownType u -> u;
            case Extern e -> e;
        };
    }

    /** Bottom-up: rewrites {@code ref}'s own arguments first, then materializes {@code ref} itself if it still has any. */
    private static TypeRef materialize(TypeRef ref, Map<TypeRef, String> materializedNames,
                                        Map<String, TypeDefinition> synthesized, Map<String, TypeDefinition> namespace) {
        List<TypeArgument> rewrittenArgs = new ArrayList<>(ref.arguments().size());
        for (TypeArgument arg : ref.arguments()) {
            if (arg instanceof TypeArgument.Ref nested) {
                rewrittenArgs.add(new TypeArgument.Ref(materialize(nested.ref(), materializedNames, synthesized, namespace)));
            } else {
                rewrittenArgs.add(arg);
            }
        }
        TypeRef flattened = new TypeRef(ref.name(), rewrittenArgs);
        if (flattened.arguments().isEmpty()) {
            return flattened;
        }

        String existingName = materializedNames.get(flattened);
        if (existingName != null) {
            return TypeRef.of(existingName);
        }

        String syntheticName = syntheticName(flattened);
        materializedNames.put(flattened, syntheticName);
        synthesized.put(syntheticName, instantiate(flattened, namespace));
        return TypeRef.of(syntheticName);
    }

    /**
     * Real instantiation for an argument-bearing application of a real constructor -- e.g. {@code
     * array<field_name>} (from {@code [field_name]} sugar, §5.3) should materialize to a genuine
     * {@code !array { element_type: field_name }} body, not a self-referential placeholder pointing
     * back at the very application being materialized. Falls back to the old placeholder shape
     * ({@link TypeDefinition#reference}) for everything this doesn't (yet) know how to build: {@code
     * application.name()} not resolving to a real constructor in {@code namespace}, an arity
     * mismatch between the constructor's own declared {@code parameters} and the arguments actually
     * applied, or (see {@link #instantiateBody}) a constructor this method has no per-shape
     * assembler for yet.
     *
     * <p><b>Deliberately hand-written per target shape, not routed through generic {@code tson-bind}
     * binding</b> -- {@code tson-schema} has no dependency on {@code tson-parser} (where {@code
     * DefinitionResolver}'s own {@code resolveInstance}, backed by the compiled {@code
     * Record*Reader}, already does the general version of this for an explicit {@code !C value}
     * instance), and every field a materializable
     * shape like {@link ArrayBody} needs is a plain {@code boolean}/{@code BigInteger}/{@code
     * TypeRef} -- not worth a new cross-module dependency to bind generically. {@code source} on the
     * result mirrors {@code resolveInstance}'s own convention exactly: the bare constructor name
     * (§5.5's "construction transfers only the constructor's kind"), never the full application with
     * its arguments.
     */
    private static TypeDefinition instantiate(TypeRef application, Map<String, TypeDefinition> namespace) {
        TypeDefinition constructor = namespace.get(application.name());
        if (constructor == null || !constructor.constructor() || !(constructor.body() instanceof RecordBody vocabulary)) {
            return TypeDefinition.reference(application);
        }
        Map<String, TypeArgument> argumentsByParameter = zipParameters(constructor.parameters(), application.arguments());
        if (argumentsByParameter == null) {
            return TypeDefinition.reference(application);
        }
        Top body = instantiateBody(application.name(), vocabulary, argumentsByParameter);
        if (body == null) {
            return TypeDefinition.reference(application);
        }
        return new TypeDefinition(Optional.of(TypeRef.of(application.name())), constructor.kind(), List.of(), false,
                List.of(), List.of(), Optional.empty(), body);
    }

    /** Positionally zips a constructor's own declared parameters to the arguments an application supplies; {@code null} on an arity mismatch. */
    private static Map<String, TypeArgument> zipParameters(List<String> parameters, List<TypeArgument> arguments) {
        if (parameters.size() != arguments.size()) {
            return null;
        }
        Map<String, TypeArgument> byParameter = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            byParameter.put(parameters.get(i), arguments.get(i));
        }
        return byParameter;
    }

    /**
     * Per-target-shape assembly -- {@code null} means "not supported yet", handled by {@link
     * #instantiate}'s own fallback. {@code array} and {@code set} both route through {@link
     * #instantiateArray} -- {@code set}'s own resolved vocabulary is a {@link RecordBody} with the
     * *identical* field shape as {@code array}'s (same field names, {@code element_type} routed via
     * the same {@code value_param: "T"}), a structural tightening (§5.7's refinement, not a fresh
     * composition), so the same assembler applies unmodified: it already reads {@code state}/{@code
     * unordered}/{@code unique_items} from whatever the vocabulary's own {@link RecordField#value}
     * actually says rather than assuming {@code array}'s own defaults, so {@code set}'s tightened
     * {@code REQUIRED}/{@code true}/{@code true} come out correctly with no {@code set}-specific
     * code at all. {@code map}/{@code tuple}/{@code record}/{@code choice}/any atom-family
     * constructor still aren't wired up -- a known, explicit gap, not a silent wrong answer.
     */
    private static Top instantiateBody(String constructorName, RecordBody vocabulary,
                                        Map<String, TypeArgument> argumentsByParameter) {
        return switch (constructorName) {
            case "array", "set" -> instantiateArray(vocabulary, argumentsByParameter);
            default -> null;
        };
    }

    /**
     * {@code array}'s (and {@code set}'s -- see {@link #instantiateBody}) own vocabulary, read
     * field by field: {@code element_type} MUST route to a type-ref argument (§5.10's labelled-form
     * parameter routing, {@code value_param}) -- anything else (no routing, or a literal-value
     * argument where a type is expected) means this can't be built, {@code null} rather than a
     * wrong guess. {@code state}/{@code unordered}/{@code unique_items} take the vocabulary's own
     * schema-composed default {@link RecordField#value} when present (mirroring, in miniature, the
     * compiled {@code Record*Reader}'s own schema-composed defaulting one layer up in {@code
     * tson-parser}) -- this is exactly what makes {@code set}'s own tightened defaults (all three {@code
     * REQUIRED_FIXED}, unlike {@code array}'s own {@code REQUIRED_DEFAULT}/{@code
     * REQUIRED_DEFAULT}/{@code REQUIRED_DEFAULT}) come out correctly without this method needing to
     * know which constructor it's assembling for. {@code min_items}/{@code max_items} have no
     * default in either vocabulary and stay absent -- {@code array_min}/{@code array_max}/{@code
     * array_ranged} tighten them, but aren't applied via {@code <...>} sugar in any real fixture, so
     * that case still isn't attempted.
     */
    private static ArrayBody instantiateArray(RecordBody vocabulary, Map<String, TypeArgument> argumentsByParameter) {
        TypeRef elementType = null;
        ElementState state = ElementState.REQUIRED;
        boolean unordered = false;
        boolean uniqueItems = false;
        Optional<BigInteger> minItems = Optional.empty();
        Optional<BigInteger> maxItems = Optional.empty();

        for (RecordField field : vocabulary.fields()) {
            TypeArgument routed = field.valueParam().map(argumentsByParameter::get).orElse(null);
            switch (field.name()) {
                case "element_type" -> {
                    if (!(routed instanceof TypeArgument.Ref ref)) {
                        return null;
                    }
                    elementType = ref.ref();
                }
                case "state" -> state = field.value().map(t -> ElementState.valueOf(t.text())).orElse(state);
                case "unordered" -> unordered = field.value().map(t -> Boolean.parseBoolean(t.text())).orElse(unordered);
                case "unique_items" -> uniqueItems = field.value().map(t -> Boolean.parseBoolean(t.text())).orElse(uniqueItems);
                default -> {
                    // min_items/max_items (no default to apply here) and anything else array's own
                    // vocabulary might grow later -- left at the unconstrained default above.
                }
            }
        }
        return elementType == null ? null : new ArrayBody(elementType, state, unordered, uniqueItems, minItems, maxItems);
    }

    /**
     * §8.2's own non-normative guidance: "a readable head plus a structural hash." Not
     * conformance-relevant -- free to refine if a real schema's names ever collide or read badly.
     */
    private static String syntheticName(TypeRef flattened) {
        StringBuilder head = new StringBuilder(flattened.name());
        for (TypeArgument arg : flattened.arguments()) {
            head.append('_');
            switch (arg) {
                case TypeArgument.Ref r -> head.append(r.ref().name());
                case TypeArgument.Value v -> head.append(v.value().text());
            }
        }
        return head + "_" + String.format("%08x", flattened.toString().hashCode());
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
        for (String supertype : def.supertypes()) {
            if (!namespace.containsKey(supertype)) {
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
