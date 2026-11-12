package io.ltr8.tson.compiler;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.SchemaRefusalException;
import io.ltr8.tson.base.SchemaValidationException;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.CountingReceiver;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.SourcePosition;
import io.ltr8.tson.atom.IdentifierGrammar;
import io.ltr8.unicode.Normalization;
import io.ltr8.tson.compiler.resolver.ConstructionSugar;
import io.ltr8.tson.compiler.resolver.KindMembers;
import io.ltr8.tson.compiler.resolver.NameType;
import io.ltr8.tson.schema.*;
import io.ltr8.tson.atom.AtomParsers;
import io.ltr8.tson.atom.AtomType;
import io.ltr8.tson.atom.AtomTypeException;
import io.ltr8.unicode.IdentifierProfile;
import io.ltr8.unicode.ConfusableNames;
import io.ltr8.tson.schema.meta.EntryDisplayName;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.ParamRef;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.binary.BytesType;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.net.Cidr4Type;
import io.ltr8.tson.schema.net.Cidr6Type;
import io.ltr8.tson.schema.numeric.ComplexType;
import io.ltr8.tson.schema.temporal.DateTimeType;
import io.ltr8.tson.schema.temporal.DateType;
import io.ltr8.tson.schema.meta.DeclaredType;
import io.ltr8.tson.schema.meta.ApplyBody;
import io.ltr8.tson.schema.meta.TypeSlots;
import io.ltr8.tson.schema.meta.DependentBody;
import io.ltr8.tson.schema.numeric.DecimalType;
import io.ltr8.tson.schema.temporal.DurationType;
import io.ltr8.tson.schema.temporal.PeriodType;
import io.ltr8.tson.schema.net.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.numeric.FloatType;
import io.ltr8.tson.schema.numeric.IntegerType;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.net.HostType;
import io.ltr8.tson.schema.net.HostnameType;
import io.ltr8.tson.schema.net.MediaTypeType;
import io.ltr8.tson.schema.net.Ipv4Type;
import io.ltr8.tson.schema.net.Ipv6Type;
import io.ltr8.tson.schema.net.MacType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.numeric.RationalType;
import io.ltr8.tson.schema.meta.Product;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.unicode.RegexType;
import io.ltr8.tson.schema.unicode.TextType;
import io.ltr8.tson.schema.temporal.TimeType;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Sum;
import io.ltr8.tson.schema.net.IriType;
import io.ltr8.tson.schema.net.UriType;
import io.ltr8.tson.schema.meta.VoidType;
import io.ltr8.tson.schema.unicode.IdentifierType;
import io.ltr8.tson.schema.net.UuidType;
import io.ltr8.tson.schema.meta.TypeChain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.function.Predicate;

/**
 * Turns a resolved-but-unlinked {@link TsonSchema} into a {@link TsonLinkedSchema} -- pass 2, which a schema
 * goes through before {@code TsonSchemaRegistry#register} will accept it. Named for the compiler stage it
 * corresponds to in the pipeline (parse -&gt; desugar -&gt; resolve -&gt; <b>link</b> -&gt; register -&gt;
 * compile -&gt; read): it merges every {@code !!import}'s entries, and their facts, into one namespace, populates
 * each type's {@code subtypes} (the reverse of {@code supertypes}, for *this schema's own merged view* of every
 * entry it can see, imported or local -- see {@link #computeSubtypes}'s own Javadoc for why crediting a
 * subtype onto an imported entry's copy here never touches the imported schema's own separately-registered
 * original), computes each kind's members ({@link #computeKinds}), derives choice disjointness, checks that every
 * reference anywhere in the schema actually resolves, that a reference's refinement narrows its {@code of} ({@link
 * #checkReferenceNarrows}) and that a field a record restates narrows its type ({@link #checkRestatedFieldTypes}),
 * resolves and judges every value a value entry holds
 * by name ({@code ValuesByName}), and judges every dependent field's value a value entry holds as written against the
 * type its sibling names ({@code HeldValues}). A {@link TsonLinkedSchema} is the compile-time proof that all of that
 * ran.
 *
 * <p>Public, and meant to be called directly by anything orchestrating the pipeline -- the registries here
 * link a schema before registering it, same as any other caller. Identities are canonicalized through
 * {@link CanonicalIdentity}, which lives in {@code tson-base}, shared with the registries that key on it.
 *
 * <p><b>No materialization.</b> The linker builds no type. {@code SchemaDesugarer} writes every sugar form as the
 * construction it denotes, in place, and the resolver binds it through the constructor's compiled reader, which
 * covers every shape generically; the resolver's {@code TemplateMaterialiser} closes every application, in place or
 * into the declaration naming it. What survives into an entry the linker sees is therefore a name or a type written
 * in place in every position except one: an open record's own fields and parents, which legitimately reference its own
 * parameters ({@code box<T>}) and are checked only for what no argument changes -- the arity of the references it
 * writes ({@link #checkOpenArity}) -- never resolved against the schema. A
 * closed entry holds no parameter, so every name it holds resolves in the schema's own namespace or is unresolved.
 *
 * <p><b>One namespace per schema (Part 2 §2.2.3, §3.3).</b> The namespace a schema is checked
 * against is built in two stages, in this order: (1) the whole namespace of every {@code !!import} ({@link
 * TsonSchema#namespaceSources}), in header order, looked up via {@code loader} by canonical
 * identity -- <b>transitive, not shallow</b>: {@code loader} hands back an already-registered, already-linked schema
 * whose {@code entries()} hold its own closure merged in, and all of them are taken; (2) this schema's own entries,
 * exactly as resolved. Every name a schema
 * writes -- a construction head, a refinement source, a type-ref, a kind's or a reference's {@code of}, an
 * annotation -- resolves in that one flat namespace, with no hiding and no second store.
 *
 * <p><b>Anyone may declare a constructor</b> (an entry IS-A {@code top}, §4.1) or a reference, and import any
 * schema.
 *
 * <p><b>Collisions are decided by entry identity.</b> One schema reached by several routes unifies; two
 * *different* schemas declaring one name is the error, as is a local declaration shadowing any name the
 * import closure already binds (no redefinition -- see {@link #mergeImports}). <b>Merged entries keep
 * their home namespace</b>: an imported entry is carried in exactly as the imported schema resolved it, its
 * derived indexes aside -- {@code subtypes} gains this schema's subtypes, and a kind's members are recomputed over
 * this closure -- and never re-validated against the importer's own namespace: only the *importer's own* new
 * material gets validated here.
 */
public final class TsonSchemaLinker {

    /** The kernel's {@code void}-targeted marker (§5.4), written bare -- presence is the assertion. */
    private static final String DISJOINT = "meta.disjoint";

    /** The constructor every type a value can have is built with: what `meta.type_ref` is the kind of. */
    private static final String TYPE = KernelNames.of("type");

    /** The constructor of the identifier families, the kind a namespace's {@code key_type} is a member of. */
    private static final String IDENTIFIER_TYPE = KernelNames.of(KernelNames.UNICODE, "identifier_type");

    /** [TSON-SCHEMA] §4.1's structural root -- what an entry IS-A when it is a constructor. */


    private TsonSchemaLinker() {
    }

    /**
     * Links {@code bootstrap} -- the kernel's own raw, pre-loaded bootstrap output (see {@link
     * TsonSchema#bootstrap()}'s own Javadoc for why it can't be resolved the ordinary way). It lives here
     * rather than on {@link TsonSchemaRegistry} because it belongs with the verb it performs: linking, not
     * storage -- and this is a link whose result the registry deliberately never stores (see below).
     *
     * <p>Takes no {@link TsonSchemaLoader} -- unlike {@link #link}, which always needs one for
     * {@code !!import} lookups -- because the kernel's own document, the only real caller of this method,
     * never has any {@code !!import}s: its header is its {@code !!id} alone ([TSON-SCHEMA] §3.4) -- so {@link
     * #mergeImports} never reaches the loader, and
     * passing {@code null} through is safe for this specific, guaranteed-narrow case, not a general
     * shortcut.
     *
     * <p>Does <b>not</b> store the result under a persistent identity anywhere, and {@code
     * TsonSchemaRegistry#register} refuses it outright regardless (see that class's own Javadoc).
     * Exists purely so a caller (e.g. building an object-binding-mode {@code
     * TsonParserFactoryRegistry}, which needs a genuinely linked {@code TsonSchema} to validate
     * against up front) can get a usable result straight from the raw bootstrap object, without
     * separately wiring a {@link TsonSchemaLoader} or a registry at all.
     *
     * @throws SchemaValidationException if {@code bootstrap.bootstrap()} is {@code false} -- this
     *                                        method exists specifically for the one pre-loaded
     *                                        schema, not as a general "link without registering"
     *                                        escape hatch for ordinary schemas (call {@link #link}
     *                                        directly for that)
     */
    public static TsonLinkedSchema linkBootstrap(TsonSchema bootstrap) {
        if (!bootstrap.bootstrap()) {
            throw new SchemaValidationException("'" + bootstrap.id() + "' was not produced by the real "
                    + "bootstrap reader (KernelBootstrapResolver.kernelSchema()) -- "
                    + "TsonSchemaLinker.linkBootstrap exists specifically for that case; call "
                    + "link directly for an ordinary schema instead");
        }
        // The bootstrap is this library's own artifact, not user input, so it is linked under the default
        // rather than under whatever a caller configured -- a policy should not be able to break the kernel.
        return linkWith(bootstrap, null, null, IdentifierPolicy.defaults(), null, Set.of());
    }

    /**
     * Links the part of a schema whose resolution is in progress that is ready, {@code pending} naming its
     * declarations not yet ready: the partial link of [TSON-SCHEMA] §3.4.1's wait order.
     * A resolution reads a value against an entry of its own document through readers compiled from the entries
     * ready so far ({@code TsonSchemaCompiler.compileReady}), and those need a linked schema while some of the names
     * its entries hold have no entry yet. {@code ready}'s imports are merged through {@code loader}, which may be
     * {@code null} for a schema that imports nothing (the kernel's bootstrap).
     *
     * <p>A reference to a name in {@code pending} is therefore not a refusal, and nothing here is a verdict: each
     * entry is judged when the whole schema links, which every resolution ends with, so what this link finds wrong
     * is dropped rather than reported against a read it would only confuse.
     */
    public static TsonLinkedSchema linkReady(TsonSchema ready, TsonSchemaLoader loader, Set<String> pending) {
        return linkWith(ready, loader, diagnostic -> { }, IdentifierPolicy.none(), null, Set.copyOf(pending));
    }

    /**
     * Links {@code schema}, throwing at the first entry that fails, with no bind context: a value by name whose walk
     * selects a field of a record value cannot be read, so a schema whose namespace values are named through a field
     * is linked with the context they were bound under ({@link #link(TsonSchema, TsonSchemaLoader, IdentifierPolicy,
     * DataBindContext)}).
     */
    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader) {
        return linkWith(schema, loader, null, IdentifierPolicy.defaults(), null, Set.of());
    }

    /** {@link #link(TsonSchema, TsonSchemaLoader)} with the §5.2 restriction level for declared names chosen. */
    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader, IdentifierPolicy identifiers) {
        return linkWith(schema, loader, null, identifiers, null, Set.of());
    }

    /**
     * {@link #link(TsonSchema, TsonSchemaLoader, IdentifierPolicy)} with {@code values}, the bind context the
     * resolver bound {@code schema}'s namespace values under: a value by name whose walk selects a field of a record
     * value reads the field through that context's descriptor of the value's class ({@code ValuesByName}).
     */
    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader, IdentifierPolicy identifiers,
                                        DataBindContext values) {
        return linkWith(schema, loader, null, identifiers, Objects.requireNonNull(values, "values"), Set.of());
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
     * entry wrong -- an {@code !!import} that will not load. Carrying
     * on past those would report a page of unresolved references that are all consequences of the one real
     * problem, which is the cascade this reporting is meant to avoid, not an example of it.
     *
     * @param receiver where a failing entry is reported; must not be {@code null}
     */
    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader,
                                        DiagnosticsReceiver receiver) {
        return link(schema, loader, receiver, IdentifierPolicy.defaults());
    }

    /** The reporting overload with the §5.2 restriction level for declared names chosen. */
    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader,
                                        DiagnosticsReceiver receiver, IdentifierPolicy identifiers) {
        Objects.requireNonNull(receiver, "receiver");
        return linkWith(schema, loader, receiver, identifiers, null, Set.of());
    }

    /** The reporting overload with the bind context the namespace values were bound under, as for throwing. */
    public static TsonLinkedSchema link(TsonSchema schema, TsonSchemaLoader loader,
                                        DiagnosticsReceiver receiver, IdentifierPolicy identifiers,
                                        DataBindContext values) {
        Objects.requireNonNull(receiver, "receiver");
        return linkWith(schema, loader, receiver, identifiers, Objects.requireNonNull(values, "values"), Set.of());
    }

    /**
     * [TSON-DATA] §8.2's name hygiene over the schema-layer scopes [TSON-SCHEMA] §11.4 names: the merged
     * namespace, and each entry's own field names and enum members. A choice's variants are deliberately not
     * a scope -- see the comment at the switch below.
     *
     * <p><b>The namespace check runs over {@code merged}, which is the point.</b> §2.2.3 already requires
     * imported names be "disjoint from each other and from local entries", and that disjointness is exact
     * equality — which a confusable pair passes by construction, since two entries a reviewer reads as one
     * name are, to the resolver, two names. Running here catches a spoofed name arriving from an import as
     * well as one declared locally, and it is the place where the damage is greatest: a name that changes
     * which type a document validates against.
     *
     * <p>Data-side field names need no check of their own under a schema: a data field name is valid only if
     * it matches a declared one, so restricting declarations restricts the data by construction. A Class 1
     * record is the case that has no declaration, and is checked by the schemaless readers instead.
     */
    private static void checkNames(DiagnosticsReceiver receiver, TsonSchema schema,
                                   Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                                   Function<String, Top> structure, Set<String> refusedEnums,
                                   IdentifierPolicy identifiers) {
        // Each schema map is a scope of its own: the root's entries, and each nested map's members, named by their
        // own last segment, a member's path being its map's name and its own.
        Map<String, Map<String, String>> scopes = new LinkedHashMap<>();
        merged.keySet().forEach(name -> scopes.computeIfAbsent(SchemaPaths.parent(name), map -> new LinkedHashMap<>())
                .put(SchemaPaths.member(name), name));
        if (identifiers.appliesSkeletonDistinctness()) {
            scopes.forEach((map, members) -> ConfusableNames.firstCollision(members.keySet()).ifPresent(collision -> {
                String second = members.get(collision.second());
                refuse(receiver, schema, second, at(facts, second), Diagnostic.Code.CONFUSABLE_NAMES,
                        "in the namespace of '" + schema.id() + "'" + (map.isEmpty() ? "" : ", the schema map '"
                                + map + "'") + ": " + collision.describe());
            }));
        }

        // The restricted-character and restricted-script rules over the same names, in the same pass. Where the
        // collision check above is a
        // relation and needs the whole set, these are properties of each name on its own -- so they are the
        // rules that reach a name nothing else in the schema resembles.
        merged.forEach((name, definition) -> perName(receiver, schema, name, at(facts, name),
                SchemaPaths.member(name), "declared name ", identifiers));

        merged.forEach((name, definition) -> {
            Top body = definition;
            Optional<SourcePosition> position = at(facts, name);
            // A choice's variants are deliberately *not* a scope of their own: a variant is a reference to
            // a declared name, so two confusable variants are two confusable entries in `merged` and the
            // namespace check above has already reported them. A check here could never fire.
            // An enum whose members its own type refused has had its verdict: judging those members as names
            // too would report the one mistake twice.
            List<String> names = refusedEnums.contains(name) ? List.of() : switch (body) {
                case EnumBody enumBody -> List.copyOf(enumBody.members());
                case RecordBody record -> List.copyOf(record.fields().keySet());
                default -> List.of();
            };
            String noun = body instanceof RecordBody ? "field names" : "members";
            // An enum's members and a record's field names are names of its name type, an identifier family, and
            // are judged under that type's profile, whose added characters are its own.
            IdentifierProfile profile = NameType.profile(definition, constructorOf(name, definition, facts),
                            merged::get, structure)
                    .orElse(IdentifierGrammar.PROFILE);
            checkScope(receiver, schema, name, "'" + name + "'", position, names, noun, identifiers, profile);
            // An open record's literal values are every application's, so they are judged here, once.
            if (body instanceof RecordBody record) {
                checkFieldValues(receiver, schema, name, "'" + name + "'", position, fieldValues(record), merged,
                        identifiers);
            }
            // A record written in place is a scope of its own, judged as a declared record is (§5.2, §11.4).
            if (!refusedEnums.contains(name)) {
                for (TypeInline inline : FieldNames.inPlace(definition)) {
                    RecordBody record = (RecordBody) inline.of();
                    String who = FieldNames.inPlace(name, inline);
                    checkScope(receiver, schema, name, who, position, List.copyOf(record.fields().keySet()),
                            "field names", identifiers, NameType.profile(record, Optional.of(inline.constructor()),
                                    merged::get, structure).orElse(IdentifierGrammar.PROFILE));
                    checkFieldValues(receiver, schema, name, who, position, fieldValues(record), merged,
                            identifiers);
                }
            }

            // [TSON-SCHEMA] §11.4 does not list a template's parameters among its scopes, and this treats
            // them as one anyway -- §11.4 declines the scope. A parameter is a name, §11.4's own reasoning for
            // enum members applies to it unchanged, and `<T, Т>` otherwise declares two parameters that read
            // identically: a body referencing `T` binds one of them and a reviewer cannot see which, which
            // is the substitution hazard §8.2 exists to refuse.
            checkScope(receiver, schema, name, position, OpenEntry.parameterNames(definition), "parameters",
                    identifiers);
        });
    }

    /** One named scope ([TSON-DATA] §8.2): its own collision relation, then each name's own two rules. */
    private static void checkScope(DiagnosticsReceiver receiver, TsonSchema schema, String entry,
                                   Optional<SourcePosition> definition, List<String> names, String noun,
                                   IdentifierPolicy identifiers) {
        checkScope(receiver, schema, entry, "'" + entry + "'", definition, names, noun, identifiers,
                IdentifierGrammar.PROFILE);
    }

    /**
     * The same, judging each name under {@code profile} -- an enum's or a record's name type's, for its members or
     * field names -- and naming the scope in a message as {@code who}, reported against {@code entry}.
     */
    private static void checkScope(DiagnosticsReceiver receiver, TsonSchema schema, String entry, String who,
                                   Optional<SourcePosition> definition, List<String> names, String noun,
                                   IdentifierPolicy identifiers, IdentifierProfile profile) {
        if (identifiers.appliesSkeletonDistinctness()) {
            ConfusableNames.firstCollision(names).ifPresent(collision -> refuse(receiver, schema, entry,
                    definition, Diagnostic.Code.CONFUSABLE_NAMES,
                    who + " has " + noun + " that read alike: " + collision.describe()));
        }
        String singular = noun.substring(0, noun.length() - 1);
        names.forEach(member -> perName(receiver, schema, entry, definition, member,
                who + " has a " + singular + " where ", identifiers, profile));
    }

    /**
     * §8.2's per-name rules over the values a schema supplies for its data: a field's default or fixed value,
     * where the field's type is an identifier family. Such a value is a name, as the same token written in a
     * document is -- and a default reaches every document that omits the field, so a value the reader would
     * refuse if the document wrote it must not be one the reader injects. Judged under the family's own profile,
     * as a read judges it: over the value, the text in the family's normalization form.
     */
    private static void checkFieldValues(DiagnosticsReceiver receiver, TsonSchema schema, String entry, String who,
                                         Optional<SourcePosition> definition, List<FieldValue> values,
                                         Map<String, Top> merged, IdentifierPolicy identifiers) {
        for (FieldValue field : values) {
            Optional<IdentifierType> family = AliasChain.terminalDefinition(field.type().name(), merged)
                    .filter(IdentifierType.class::isInstance)
                    .map(IdentifierType.class::cast);
            if (family.isPresent()) {
                String what = field.role() == FieldRole.FIXED ? "a fixed value" : "a default";
                perName(receiver, schema, entry, definition,
                        family.get().normalization().apply(field.value().text()),
                        who + " has " + what + " for '" + field.name() + "' where ", identifiers,
                        family.get().profile());
            }
        }
    }

    /** One field's literal value, with the type it is a value of. */
    private record FieldValue(String name, TypeRef type, FieldRole role, Token value) {
    }

    /** {@code record}'s literal values: a value is a name only at an identifier family, never written in place. */
    private static List<FieldValue> fieldValues(RecordBody record) {
        return record.fields().entrySet().stream()
                .filter(f -> f.getValue().value().isPresent() && f.getValue().sibling().isEmpty())
                .flatMap(f -> TypeExpr.named(f.getValue().typeExpr()).stream()
                        .map(type -> new FieldValue(f.getKey(), type, f.getValue().role(),
                                f.getValue().value().get()))).toList();
    }

    /**
     * §8.2's two per-name rules over one name: the restricted-character rule ({@code Identifier_Status}) and
     * the restricted-script rule (the restriction level).
     *
     * <p><b>Both are here rather than at the positions that read the name.</b> Those positions are spread
     * over the schema parser, the definition resolver and the atom vocabulary, so a rule applied there is
     * three call sites with three exceptions, and has a hole wherever a naming position reaches only one of
     * them. §8.2 defines its rules over named scopes and [TSON-SCHEMA] §11.4 supplies the
     * schema layer's, so the walk that already enumerates those scopes is the one place all three belong.
     * What stays at the reading positions is §7.7's grammar, which is validity and really is a parse error.
     */
    private static void perName(DiagnosticsReceiver receiver, TsonSchema schema, String entry,
                                Optional<SourcePosition> definition, String name, String prefix,
                                IdentifierPolicy identifiers) {
        perName(receiver, schema, entry, definition, name, prefix, identifiers, IdentifierGrammar.PROFILE);
    }

    /** The same under {@code profile}, whose added characters are its own and meet no restricted-character rule. */
    private static void perName(DiagnosticsReceiver receiver, TsonSchema schema, String entry,
                                Optional<SourcePosition> definition, String name, String prefix,
                                IdentifierPolicy identifiers, IdentifierProfile profile) {
        // Each rule reports under its own code, since the two want different fixes -- change the character, against
        // rename or relax the policy. The restricted-character message names the name; the script one opens with it.
        for (IdentifierPolicy.Violation violation : identifiers.judge(name, profile)) {
            refuse(receiver, schema, entry, definition, violation.code(),
                    violation.code() == Diagnostic.Code.RESTRICTED_CHARACTER
                            ? prefix + "'" + name + "': " + violation.reason() : prefix + violation.reason());
        }
    }

    /**
     * A §8.2 refusal against one entry, or a throw when there is no receiver. {@link #report}'s peer, and
     * separate from it because the two say different things: that one is "this schema is wrong", this one is
     * "this processor declines it", and §8.2 requires that a consumer be able to tell them apart.
     */
    private static void refuse(DiagnosticsReceiver receiver, TsonSchema schema, String name,
                               Optional<SourcePosition> at, Diagnostic.Code code, String message) {
        if (receiver == null) {
            throw new SchemaRefusalException(code, message, null);
        }
        receiver.report(TsonDiagnostics.ofSchemaRefusal(CanonicalIdentity.canonicalize(schema.id()), name,
                code, message, at));
    }

    /** What a message calls {@code name} -- its author's own name, or the form that produced it. */
    private static String displayName(String name, Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                                      Map<String, ApplyBody> applications) {
        return EntryDisplayName.of(name, merged.get(name), facts.getOrDefault(name, TsonSchema.EntryFacts.NONE),
                Optional.ofNullable(applications.get(name)), merged::get,
                other -> facts.getOrDefault(other, TsonSchema.EntryFacts.NONE),
                other -> Optional.ofNullable(applications.get(other)));
    }

    /** Where {@code name} was declared, from the merged facts -- absent for an entry with no source position. */
    private static Optional<SourcePosition> at(Map<String, TsonSchema.EntryFacts> facts, String name) {
        return facts.getOrDefault(name, TsonSchema.EntryFacts.NONE).position();
    }

    /** The constructor {@code definition}'s value applies: its recorded tag, or the one its class is the body of. */
    static Optional<String> constructorOf(String name, Top definition, Map<String, TsonSchema.EntryFacts> facts) {
        return facts.getOrDefault(name, TsonSchema.EntryFacts.NONE).constructor()
                .or(() -> Optional.of(TsonSchema.constructorOf(definition)));
    }

    /**
     * Reports {@code message} against one entry, or throws it when there is no receiver.
     *
     * @return {@code true} if the caller should carry on as though the check had passed -- always, since a
     *         throw is the only other outcome. Reads as a guard at the call sites that want to skip the entry.
     */
    private static boolean report(DiagnosticsReceiver receiver, TsonSchema schema, String name,
                                  Optional<SourcePosition> at, String message) {
        if (receiver == null) {
            throw new SchemaValidationException(message);
        }
        receiver.report(schemaError(schema, name, at, message));
        return false;
    }

    /**
     * A reference resolving to nothing, carried in <b>parts</b> rather than as a finished sentence.
     *
     * <p>An open record defers every question about what its references resolve to, so an unresolved one
     * surfaces inside a record an application closes to in place -- {@code box<int32>} -- when the name was written
     * in the template's own text. The two statements of that one fact differ in the subject alone, so the subject
     * is supplied at the point the fact is stated rather than baked in at the point it is discovered.
     *
     * <p><b>Linker-internal, and never escapes.</b> It is caught in the one place it is thrown from and
     * re-stated as a {@link SchemaValidationException}, whose classification this shares: an unresolved
     * reference is the author's error, and a verdict this library will not change its mind about. It is not
     * a subclass of it because that type is sealed in {@code tson-base}, permitting only {@code
     * SchemaRefusalException}.
     */
    private static final class UnresolvedReference extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String subject;
        private final String trail;
        private final String name;
        private final boolean inPlace;
        private final boolean formNamed;

        UnresolvedReference(String subject, String trail, String name) {
            this(subject, trail, name, false, false);
        }

        private UnresolvedReference(String subject, String trail, String name, boolean inPlace, boolean formNamed) {
            super(sentence(subject, trail, name));
            this.subject = subject;
            this.trail = trail;
            this.name = name;
            this.inPlace = inPlace;
            this.formNamed = formNamed;
        }

        /** The name that resolved to nothing -- what decides which declaration wrote it. */
        String name() {
            return name;
        }

        /**
         * Whether it surfaced inside a record an application closes to in place, whose text an open record wrote
         * ({@link #heldDeclarationNaming}).
         */
        boolean inPlace() {
            return inPlace;
        }

        /** The same fact, surfaced inside a record in place. */
        UnresolvedReference heldInPlace() {
            return inPlace ? this : new UnresolvedReference(subject, trail, name, true, formNamed);
        }

        /**
         * The same fact, surfaced inside a construction written in place and named by its form, which stays its
         * subject whichever declaration it is stated against.
         */
        UnresolvedReference namedByForm() {
            return inPlace || formNamed ? this : new UnresolvedReference(subject, trail, name, false, true);
        }

        /** The same fact stated against a different declaration. */
        String against(String subject) {
            return sentence(formNamed ? this.subject : subject, trail, name);
        }

        private static String sentence(String subject, String trail, String name) {
            return "'" + subject + "'" + trail + " has an unresolved reference '" + name + "'";
        }
    }

    /**
     * The declaration a <b>deferred</b> defect belongs to: the open record whose body wrote the name, when the
     * failure surfaced inside a record an application closed to in place.
     *
     * <p><b>Deferral is what makes this necessary.</b> A closed declaration's references are checked where the
     * author wrote them. An open record's are judged where it is applied, so {@code box => <T> { v: T  w:
     * no_such_type }} gets its verdict inside each record {@code box<…>} closes to in place, against a line that is
     * not wrong and does not contain the name. The author is sent to the line they can edit instead.
     *
     * <p><b>The offending name is the evidence.</b> Asking which open record <em>mentions the name</em>, a type it
     * holds in place included, reaches the template directly, and reaches nothing at all when the name came from an
     * argument the applier wrote -- {@code box<some_typo>} keeps its verdict at the application, which is where that
     * mistake is.
     *
     * <p><b>Both filters are load-bearing.</b> Only a failure inside a record in place is retargeted, or a closed
     * declaration's own typo would be blamed on any template that happens to name it; and only an open record is a
     * candidate, since a defect no open record deferred is already located correctly.
     *
     * @return the declaration to blame, or {@code null} to leave the failure where it surfaced
     */
    private static String heldDeclarationNaming(UnresolvedReference failure, Map<String, Top> entries,
                                                 Map<String, TsonSchema.EntryFacts> facts) {
        if (!failure.inPlace()) {
            return null; // the author's own text: it already names the line they wrote
        }
        for (Map.Entry<String, Top> candidate : entries.entrySet()) {
            if (at(facts, candidate.getKey()).isPresent() && OpenEntry.isOpen(candidate.getValue())
                    && writes(candidate.getValue(), failure.name())) {
                return candidate.getKey();
            }
        }
        return null;
    }

    /** Whether the open record {@code open} writes {@code name}, in a reference or inside a type it holds in place. */
    private static boolean writes(Top open, String name) {
        return OpenEntry.names(open).contains(name);
    }

    /** One entry's failure, reported when there is a receiver and rethrown as a schema error when there is not. */
    private static void reportOrThrow(DiagnosticsReceiver receiver, TsonSchema schema, String at,
                                      Map<String, TsonSchema.EntryFacts> facts, String message,
                                      RuntimeException cause) {
        if (receiver == null) {
            if (cause instanceof SchemaValidationException original && message.equals(cause.getMessage())) {
                throw original; // untouched, which is the fail-fast overloads' standing contract
            }
            throw new SchemaValidationException(message, cause);
        }
        receiver.report(schemaError(schema, at, at(facts, at), message));
    }

    /** One entry's failure as a {@link Diagnostic}, positioned at that entry's own declaration. */
    private static Diagnostic schemaError(TsonSchema schema, String name, Optional<SourcePosition> at,
                                          String message) {
        return TsonDiagnostics.ofSchemaError(CanonicalIdentity.canonicalize(schema.id()), name, message, at);
    }

    /**
     * This schema's own canonical identity, stamped on every entry it declares itself -- the same form {@link
     * #schemaError} reports under and {@code TsonSchemaRegistry} keys on, so a read diagnostic and a schema
     * diagnostic against the same declaration name the same document.
     */
    private static String localIdentity(TsonSchema schema) {
        return CanonicalIdentity.canonicalize(schema.id());
    }

    /** The shared body; {@code receiver} is {@code null} for the fail-fast overloads, which rethrow instead. */
    private static TsonLinkedSchema linkWith(TsonSchema schema, TsonSchemaLoader loader,
                                             DiagnosticsReceiver given, IdentifierPolicy identifiers,
                                             DataBindContext values, Set<String> pending) {
        // Counted, so the values held as written are judged only over a schema otherwise clean (HeldValues).
        CountingReceiver counted = given == null ? null : new CountingReceiver(given);
        DiagnosticsReceiver receiver = counted;
        Map<String, String> origins = new LinkedHashMap<>();
        Map<String, Normalization> enumForms = new LinkedHashMap<>();
        Map<String, Top> nameTypes = new LinkedHashMap<>();
        Map<String, TsonSchema.EntryFacts> facts = new LinkedHashMap<>();
        // The application each declared application closes (§8.2), off its key: an import's as its schema states
        // it, a local one's as this schema's resolution wrote it. What a message shows for an entry with no
        // source position.
        Map<String, ApplyBody> applications = new LinkedHashMap<>();
        Map<String, Top> merged = mergeImports(schema, loader, origins, enumForms, nameTypes, facts,
                applications);
        schema.entries().keySet().forEach(name -> schema.applicationOf(name)
                .ifPresent(application -> applications.put(name, application)));

        Set<String> localNames = new LinkedHashSet<>();
        // A constructor is judged by where `top` is defined, never by its spelling (§4.1).
        Predicate<String> kernel = name -> TsonBundledSchemas.isKernel(origins.get(name));

        for (Map.Entry<String, Top> entry : schema.entries().entrySet()) {
            // This document's contribution to a schema map an import declares too: one open map, no collision.
            boolean contributes = SchemaPaths.isNestedMap(merged.get(entry.getKey()))
                    && SchemaPaths.isNestedMap(entry.getValue());
            if (merged.containsKey(entry.getKey()) && !contributes) {
                // The local entry is dropped, not the import's: an import is already-linked,
                // separately registered material, so keeping it is the choice that leaves the rest of this
                // schema checkable against something real.
                if (!report(receiver, schema, entry.getKey(), schema.positionOf(entry.getKey()), "'" + entry.getKey()
                        + "' collides with an entry of the same name brought in by !!import")) {
                    continue;
                }
            }
            Top def = entry.getValue();
            merged.put(entry.getKey(), def);
            facts.put(entry.getKey(), schema.factsOf(entry.getKey()));
            origins.put(entry.getKey(), localIdentity(schema));
            localNames.add(entry.getKey());
        }

        merged = computeSubtypes(merged, localNames);
        // Only here, with the closure whole, can an enum's name type be followed to the entry its constructor pinned.
        for (String name : localNames) {
            Top def = merged.get(name);
            Normalization form = EnumLabels.labelForm(def, constructorOf(name, def, facts), merged,
                    merged::get);
            if (form != Normalization.NONE) {
                enumForms.put(name, form);
            }
        }
        // A reference read under this schema names an entry of the final namespace, assembled below.
        AtomicReference<Map<String, Top>> closure = new AtomicReference<>(Map.of());
        // And a name read under a reference that names values reaches a value of that final namespace.
        AtomicReference<ValuesByName> reached = new AtomicReference<>();
        merged = computeKinds(merged, facts, merged::get, pending, reached, new java.util.AbstractMap<>() {
            @Override
            public Top get(Object name) {
                return closure.get().get(name);
            }

            @Override
            public Set<Map.Entry<String, Top>> entrySet() {
                return closure.get().entrySet();
            }
        });
        merged = computeDisjointness(merged, pending);
        // Before the name checks: a member that is not a value of its enum's name type is the more basic verdict,
        // and the per-name rules would otherwise report it as a restricted character.
        Set<String> refusedEnums = checkEnumTypes(schema, merged, facts, localNames, merged::get,
                receiver);
        Set<String> refusedRecords = checkFieldNameTypes(schema, merged, facts, localNames, merged::get,
                receiver);
        refusedEnums.addAll(refusedRecords);
        for (String name : localNames) {
            if (!refusedRecords.contains(name)) {
                FieldNames.readBy(merged.get(name), merged, merged::get)
                        .ifPresent(type -> nameTypes.put(name, type));
            }
        }
        checkNames(receiver, schema, merged, facts, merged::get, refusedEnums, identifiers);

        Set<String> blamedOnce = new LinkedHashSet<>();
        Map<String, Top> namespace = merged;
        Function<String, Optional<String>> constructors = name -> constructorOf(name, namespace.get(name), facts);
        ValuesByName byName = new ValuesByName(schema, merged, facts, origins, values);
        for (Map.Entry<String, Top> entry : merged.entrySet()) {
            try {
                // Named by what the author wrote: its declared name, or the form or application an entry with no
                // source position records.
                validateEntry(displayName(entry.getKey(), merged, facts, applications), entry.getValue(),
                        merged, constructors, other -> displayName(other, namespace, facts, applications), byName);
            } catch (UnsupportedOperationException gap) {
                // A field's value naming a value through a step this library does not yet follow: the gap it is.
                if (receiver == null) {
                    throw gap;
                }
                receiver.report(TsonDiagnostics.ofSchemaGap(localIdentity(schema), entry.getKey(), gap.getMessage(),
                        at(facts, entry.getKey())));
            } catch (UnresolvedReference e) {
                if (pending.contains(e.name())) {
                    continue; // a declaration not yet ready: judged when the whole schema links
                }
                String author = heldDeclarationNaming(e, merged, facts);
                if (author == null) { // the applier's own text, or a name no open record wrote: blame the entry
                    reportOrThrow(receiver, schema, entry.getKey(), facts, e.getMessage(), e);
                    continue;
                }
                // One mistake in a template is one mistake however many declarations apply it: each
                // application closes to its own record in place and each fails identically, so the verdict that
                // names the template would otherwise be repeated once per applier.
                String message = e.against(author);
                if (blamedOnce.add(author + "\u0000" + message)) {
                    reportOrThrow(receiver, schema, author, facts, message, e);
                }
            } catch (SchemaValidationException e) {
                reportOrThrow(receiver, schema, entry.getKey(), facts, e.getMessage(), e);
            }
        }

        checkValuesByName(schema, merged, facts, origins, localNames, values, receiver);
        checkEveryEntryIsInhabited(schema, merged, facts, applications, localNames, receiver);
        checkRecordExtension(schema, merged, facts, applications, localNames, origins, enumForms, receiver);

        AnnotatedMap<String, Top> annotated = withNameAnnotations(merged, schema, loader);
        checkDisjointAssertions(schema, annotated, facts, localNames, receiver);
        checkTaggedVariantsAreNamed(schema, annotated, facts, localNames, receiver);
        closure.set(annotated);
        reached.set(new ValuesByName(schema, annotated, facts, origins, values));

        TsonLinkedSchema linked = new TsonLinkedSchema(new TsonSchema(schema.id(), schema.imports(), annotated,
                schema.bootstrap(), schema.valuesByName(), schema.heldValues(), facts), origins, enumForms, nameTypes);
        if (pending.isEmpty() && (counted == null || !counted.reported()) && HeldValues.anyHeld(schema, localNames)) {
            checkHeldValues(linked, merged, facts, origins, localNames, identifiers, values, receiver);
        }
        return linked;
    }

    /**
     * The dependent fields' values each local value entry holds as written, judged against the type each one's
     * sibling names in this schema's namespace ([TSON-SCHEMA] §5.2, {@link HeldValues}), and the references those
     * reads record by name judged as any value body's ({@link ValuesByName}). Each refusal is its own diagnostic,
     * against the entry that holds the value. Runs last, over a schema whose link is otherwise clean, since the
     * judgement reads through the schema compiled.
     */
    private static void checkHeldValues(TsonLinkedSchema linked, Map<String, Top> merged,
                                        Map<String, TsonSchema.EntryFacts> facts, Map<String, String> origins,
                                        Set<String> localNames, IdentifierPolicy identifiers,
                                        DataBindContext values, DiagnosticsReceiver receiver) {
        TsonSchema schema = linked.schema();
        ValuesByName names = new ValuesByName(schema, merged, facts, origins, values);
        HeldValues held = new HeldValues(linked, merged, identifiers);
        for (String name : localNames) {
            held.check(name, names,
                    (entry, refused) -> reportOrThrow(receiver, schema, entry, facts, refused.getMessage(), refused),
                    (entry, gap) -> {
                        if (receiver == null) {
                            throw gap;
                        }
                        receiver.report(TsonDiagnostics.ofSchemaGap(localIdentity(schema), entry, gap.getMessage(),
                                at(facts, entry)));
                    });
        }
    }

    /**
     * The values each local value entry holds by name, as the resolver's read collected them
     * ({@link TsonSchema#valuesByName()}), resolved in this schema's namespace and judged against the slots they
     * fill ([TSON-SCHEMA] §5.12, {@link ValuesByName}). Each refused name is its own diagnostic, against the entry
     * that holds it.
     *
     * <p><b>Local entries only</b>, as for inhabitance: an imported body's names were judged when its own schema
     * linked, in its own namespace, and judging them again here would be judging them in the wrong one. Runs
     * after {@link #validateEntry}, so a body's unresolved reference is reported first and on its own.
     */
    private static void checkValuesByName(TsonSchema schema, Map<String, Top> merged,
                                          Map<String, TsonSchema.EntryFacts> facts,
                                          Map<String, String> origins, Set<String> localNames,
                                          DataBindContext values,
                                          DiagnosticsReceiver receiver) {
        ValuesByName judged = new ValuesByName(schema, merged, facts, origins, values);
        for (String name : localNames) {
            Top definition = merged.get(name);
            List<TsonSchema.ValueByName> held = schema.valuesByName().get(name);
            if (held != null && definition instanceof ValueEntry) {
                judged.check(name, held,
                        refused -> reportOrThrow(receiver, schema, name, facts, refused.getMessage(), refused),
                        gap -> {
                            if (receiver == null) {
                                throw gap;
                            }
                            receiver.report(TsonDiagnostics.ofSchemaGap(localIdentity(schema), name,
                                    gap.getMessage(), at(facts, name)));
                        });
            }
        }
    }

    /**
     * §5.10.1's productivity rule: an entry no finite document can satisfy is rejected, with the chain that has to be broken
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
    private static void checkEveryEntryIsInhabited(TsonSchema schema, Map<String, Top> merged,
                                                    Map<String, TsonSchema.EntryFacts> facts,
                                                    Map<String, ApplyBody> applications,
                                                    Set<String> localNames, DiagnosticsReceiver receiver) {
        Set<String> inhabited = TypeInhabitance.derive(merged);
        for (String name : localNames) {
            if (inhabited.contains(name)) {
                continue;
            }
            // Rendered the way a read renders it: an entry with no source position by the form or application
            // that produced it.
            List<String> chain = TypeInhabitance.cycleThrough(name, merged, inhabited).stream()
                    .map(entry -> displayName(entry, merged, facts, applications)).toList();
            report(receiver, schema, name, at(facts, name), "'" + name + "' can never be satisfied by any "
                    + "document: " + String.join(" needs ", chain)
                    + ", and nothing in that chain can be left out or left empty (§5.10.1). A recursion "
                    + "terminates only where it reaches a base case -- an optional field, a possibly-empty "
                    + "container, or a choice variant that does not recur");
        }
    }

    /**
     * What each enum's {@code name_type} obliges ({@link EnumLabels}): a text family, of which every member is a
     * value, no two of them one. Refused at schema load, against the enum that states it; the names of the
     * enums refused are returned, so the name checks do not judge the same members a second time.
     */
    private static Set<String> checkEnumTypes(TsonSchema schema, Map<String, Top> merged,
                                              Map<String, TsonSchema.EntryFacts> facts,
                                              Set<String> localNames, Function<String, Top> structure,
                                              DiagnosticsReceiver receiver) {
        Set<String> refused = new LinkedHashSet<>();
        for (EnumLabels.Violation violation : EnumLabels.check(merged, facts, localNames, structure)) {
            report(receiver, schema, violation.entry(), at(facts, violation.entry()), violation.message());
            refused.add(violation.entry());
        }
        return refused;
    }

    /**
     * What each record's {@code name_type} obliges ({@link FieldNames}, [TSON-SCHEMA] §5.2): an identifier
     * family, of which every field name the record states is a value, no two of its fields one. Refused at schema
     * load, against the record that states it; the names of the records refused are returned, so the name checks
     * do not judge the same names a second time.
     */
    private static Set<String> checkFieldNameTypes(TsonSchema schema, Map<String, Top> merged,
                                                   Map<String, TsonSchema.EntryFacts> facts,
                                                   Set<String> localNames, Function<String, Top> structure,
                                                   DiagnosticsReceiver receiver) {
        Set<String> refused = new LinkedHashSet<>();
        for (EnumLabels.Violation violation : FieldNames.check(merged, localNames, structure)) {
            report(receiver, schema, violation.entry(), at(facts, violation.entry()), violation.message());
            refused.add(violation.entry());
        }
        return refused;
    }

    /**
     * What each {@code record.extension} member obliges of the rest of the closure ({@link RecordExtension}) --
     * that nothing composes onto a FINAL record, that a sealed family's selectors are usable and its members
     * pin them distinctly, that the two marks agree with each other, and that every member is declared.
     *
     * <p><b>Runs on the merged map rather than the annotated one</b>, unlike {@link #checkDisjointAssertions}:
     * the marks are consumed into the body by the resolver, so there is no annotation left to consult and the
     * pass needs only what {@code subtypes} population has already put in place.
     *
     * <p>Reporting stays here because {@link RecordExtension} hands back the entry each violation belongs to
     * and nothing else -- a checker that knew about {@code DiagnosticsReceiver} would be a checker two callers
     * could not share, and it is already the shape {@link ChoiceDisjointness} keeps.
     */
    private static void checkRecordExtension(TsonSchema schema, Map<String, Top> merged,
                                              Map<String, TsonSchema.EntryFacts> facts,
                                              Map<String, ApplyBody> applications,
                                              Set<String> localNames, Map<String, String> origins,
                                              Map<String, Normalization> enumForms, DiagnosticsReceiver receiver) {
        for (RecordExtension.Violation violation
                : RecordExtension.check(merged, facts, applications, localNames, origins, enumForms)) {
            report(receiver, schema, violation.entry(), at(facts, violation.entry()), violation.message());
        }
    }

    /**
     * §5.4's {@code @meta.disjoint} assertion, checked against the fact {@link #computeDisjointness} derived. The
     * annotation "carries no decode force -- the resolver computes {@code choice.disjoint} whether
     * or not it is present -- and exists to be checked against that derived fact, converting a silent drift
     * into a diagnostic."
     *
     * <p><b>Two outcomes, because the fact is two-valued.</b> {@code disjoint: true} verifies the assertion,
     * silently; {@code false} makes it an error. §5.4's derivation ({@link ChoiceDisjointness}) is total, so
     * there is no third, unprovable outcome to report -- and no severity to report it at, [TSON-DATA] §8.1
     * giving a conforming processor one. That is what makes
     * {@code @meta.disjoint} mean <em>machine-verified</em>, the only reading an encoding can rely on to drop a
     * tag. Note §5.4 is explicit that the reportable condition is the assertion, never mere
     * non-disjointness -- an unannotated choice is asked nothing here.
     *
     * <p><b>Runs on the annotated map, and after it is built.</b> §6 puts a declaration's annotations in two
     * places -- before the name (they land on the map key) or after {@code =>} (on the value, and so among the
     * entry's facts) -- and
     * {@code @meta.disjoint} is equally an assertion either way, so both are consulted. Key annotations only
     * exist once {@link #withNameAnnotations} has re-attached them, which is why this is the last pass
     * rather than part of {@code validateEntry}.
     *
     * <p><b>Local entries only.</b> An imported entry was checked when its own schema linked, and {@link
     * #schemaError} stamps <em>this</em> schema's identity -- so re-checking would report another document's
     * problem against this one.
     */
    private static void checkDisjointAssertions(TsonSchema schema, AnnotatedMap<String, Top> entries,
                                                 Map<String, TsonSchema.EntryFacts> facts,
                                                 Set<String> localNames, DiagnosticsReceiver receiver) {
        for (String name : localNames) {
            if (!(entries.get(name) instanceof ChoiceBody choice)) {
                continue;
            }
            if (!AliasChain.annotates(facts.getOrDefault(name, TsonSchema.EntryFacts.NONE).annotations(), DISJOINT,
                    entries) && !AliasChain.annotates(entries.getAnnotations(name), DISJOINT, entries)) {
                continue;
            }
            if (choice.disjoint().equals(Optional.of(true))) {
                continue; // verified -- the assertion holds, and says so
            }
            List<String> variants = choice.variants().stream().map(TypeExpr::text).toList();
            report(receiver, schema, name, at(facts, name), "'" + name + "' asserts @meta.disjoint, but its variants "
                    + variants + " are not disjoint (§5.4) -- two of them occupy the same discrimination "
                    + "class (or one has no class at all), so no encoding's single form-resolution pass can "
                    + "tell them apart and every value keeps its !variant tag; drop the assertion, or use a "
                    + "field group (§5.11), which discriminates by label and needs no disjointness");
        }
    }

    /**
     * A choice that is not disjoint has every variant a name ([TSON-SCHEMA] §5.4): its values are selected by a tag, a
     * tag names a variant by its name, and a variant written in place has none, so it could never be selected. Asked
     * of every local choice, a declared one and one written in place at any depth; a choice whose {@code
     * @meta.disjoint} fails is left to {@link #checkDisjointAssertions}, which reports it on its own terms.
     */
    private static void checkTaggedVariantsAreNamed(TsonSchema schema, AnnotatedMap<String, Top> entries,
                                                    Map<String, TsonSchema.EntryFacts> facts,
                                                    Set<String> localNames, DiagnosticsReceiver receiver) {
        for (String name : localNames) {
            Top entry = entries.get(name);
            // An open record's choices are judged where each application closes, their variants known (§5.4).
            if (entry == null || OpenEntry.isOpen(entry)) {
                continue;
            }
            boolean asserted = AliasChain.annotates(facts.getOrDefault(name, TsonSchema.EntryFacts.NONE)
                    .annotations(), DISJOINT, entries) || AliasChain.annotates(entries.getAnnotations(name), DISJOINT,
                    entries);
            List<ChoiceBody> choices = new ArrayList<>();
            if (entry instanceof ChoiceBody declared && !asserted) {
                choices.add(declared);
            }
            mapInline(entry, body -> {
                if (body instanceof ChoiceBody inPlace) {
                    choices.add(inPlace);
                }
                return body;
            });
            for (ChoiceBody choice : choices) {
                if (!choice.disjoint().equals(Optional.of(false))
                        || Set.copyOf(choice.variants()).size() != choice.variants().size()) {
                    continue; // disjoint, or a repeated variant, which distinctness has refused
                }
                List<String> inPlace = choice.variants().stream().filter(TypeInline.class::isInstance)
                        .map(TypeExpr::text).toList();
                if (inPlace.isEmpty()) {
                    continue;
                }
                report(receiver, schema, name, at(facts, name), "'" + name + "': the choice '"
                        + choice.variants().stream().map(TypeExpr::text).collect(java.util.stream.Collectors.joining(
                                " | ", "(", ")")) + "' is not disjoint, so a value selects its variant by a "
                        + "tag, which names a variant by its name -- and " + inPlace + " " + (inPlace.size() == 1
                        ? "is a variant" : "are variants") + " written in place, with no name a tag could give "
                        + "(§5.4): declare each as an entry and name it, or make the variants disjoint");
                break;
            }
        }
    }

    /**
     * Re-attaches the annotations each name carried, which the passes above lose by rebuilding {@code merged}
     * as a plain map. §6 binds an annotation written before a declaration's name to that name, and a linked
     * schema is still a map keyed by those names, so they belong on the merged result rather than being
     * dropped at the one point every entry passes through.
     *
     * <p>An imported name keeps the annotations its own schema resolved, the same way its value is carried in
     * as-is -- documentation travels with the declaration, not with whoever imported it.
     */
    private static AnnotatedMap<String, Top> withNameAnnotations(Map<String, Top> merged,
            TsonSchema schema, TsonSchemaLoader loader) {
        AnnotatedMap<String, Top> result = AnnotatedMap.of(merged);
        if (loader != null) {
            for (String importUri : schema.imports()) {
                Optional<TsonLinkedSchema> imported = loader.load(CanonicalIdentity.canonicalize(importUri));
                if (imported.isPresent()) {
                    result = carryOver(result, imported.get().schema().entries(), true);
                }
            }
        }
        return carryOver(result, schema.entries(), false);
    }

    private static AnnotatedMap<String, Top> carryOver(AnnotatedMap<String, Top> into, Namespace<Top> from,
                                                       boolean imported) {
        AnnotatedMap<String, Top> result = into;
        for (String name : from.annotatedKeys()) {
            // A schema map's annotations are its declaring document's contribution: an import's never carry over.
            if (result.containsKey(name) && !(imported && SchemaPaths.isNestedMap(from.get(name)))) {
                result = result.withAnnotations(name, from.getAnnotations(name));
            }
        }
        return result;
    }

    // ── Subtypes (reverse index) ─────────────────────────────────────────

    /**
     * Populates each type's {@code subtypes}, the reverse of {@code supertypes}. Since a closed chain is already
     * the full *transitive* IS-A chain (by the induction the resolver's composition/refinement resolution
     * performs), the reverse index falls out just as transitively for free: if {@code success_response}'s own
     * chain includes both {@code response} and (transitively) {@code top}, then both gain {@code
     * success_response} as a subtype here, with no separate transitive-closure step needed.
     *
     * <p><b>Only {@code localNames} entries are walked as potential subtypes -- but the supertype being credited
     * may be anywhere in {@code merged}, imported or local.</b> An import's own already-registered {@link
     * TsonSchema} is never mutated -- {@code mergeImports} only reads its {@code entries()} (an unmodifiable map)
     * and copies their values into a brand-new {@code merged} map built fresh for *this* validation; replacing
     * one of those values in {@code merged} (via {@link #withAddedSubtypes}) changes only this schema's own
     * result, never the imported schema's own frozen copy. So when a local entry composes with an imported
     * supertype, crediting the subtype onto *this schema's own view* of that supertype is safe, and correct:
     * from this schema's own perspective the supertype genuinely does have that subtype, even though the
     * imported schema, examined on its own, correctly doesn't know about it. {@link #withAddedSubtypes} unions
     * with whatever subtypes the target already had rather than replacing them.
     *
     * <p><b>Every type is credited, and nothing else</b> ({@link Type}). An alias is a hop, and a template is no
     * type (§5.10): its chain records what its written parents reach, which each application inherits, but the
     * template itself is in no index, so no position typed by its parent can reach an open record.
     */
    private static Map<String, Top> computeSubtypes(Map<String, Top> merged, Set<String> localNames) {
        Map<String, Set<String>> newSubtypesByName = new LinkedHashMap<>();
        for (String localName : localNames) {
            Top local = merged.get(localName);
            if (OpenEntry.isOpen(local)) {
                continue;
            }
            for (String supertype : TypeChain.supertypesOf(local)) {
                if (merged.get(supertype) instanceof Type) {
                    newSubtypesByName.computeIfAbsent(supertype, ignored -> new LinkedHashSet<>()).add(localName);
                }
            }
        }
        if (newSubtypesByName.isEmpty()) {
            return merged;
        }

        Map<String, Top> result = new LinkedHashMap<>(merged);
        for (Map.Entry<String, Set<String>> entry : newSubtypesByName.entrySet()) {
            result.put(entry.getKey(), withAddedSubtypes(result.get(entry.getKey()), entry.getValue()));
        }
        return result;
    }

    /**
     * {@code def} plus {@code newSubtypes}, unioned with whatever subtypes it already had -- a new value, {@code
     * def} itself untouched. A value that carries no chain carries no subtypes and comes back unchanged.
     */
    private static Top withAddedSubtypes(Top def, Set<String> newSubtypes) {
        if (!(def instanceof Type type)) {
            return def;
        }
        Set<String> combined = new LinkedHashSet<>(type.subtypes());
        combined.addAll(newSubtypes);
        return TypeChain.withChain(def, type.chain().withSubtypes(List.copyOf(combined)));
    }

    /**
     * Derives {@code choice.disjoint} for every choice entry (§5.4), over the fully-merged namespace -- a
     * namespace-wide pass, like {@link #computeSubtypes}, since a variant's discrimination class is only
     * knowable with every entry resolved. The derivation ({@link ChoiceDisjointness}) is total and
     * two-valued, so a linked choice always carries the fact.
     *
     * <p><b>It is written on the body, which is the only thing that has variants to be disjoint over.</b>
     * An entry with no {@code !meta.choice} body has nowhere to put the fact, so "absent on every other
     * definition" stops being a rule anyone can break. A choice written in place has a body too, at the slot that
     * holds it, and is derived there the same way ({@link #mapInline}).
     *
     * <p><b>A partial link ({@link #linkReady}) derives nothing over a variant still pending</b>: its class is not
     * known until it is ready, so the choice keeps the fact as it stands, and the whole schema's link derives it. The
     * kernel's hand-built {@code meta.type_expr} is read against before {@code meta.type_inline} is ready, and states
     * the fact its {@code @meta.disjoint} asserts.
     */
    private static Map<String, Top> computeDisjointness(Map<String, Top> merged, Set<String> pending) {
        Map<String, Top> result = new LinkedHashMap<>(merged);
        for (Map.Entry<String, Top> entry : merged.entrySet()) {
            Top value = mapInline(entry.getValue(), body -> body instanceof ChoiceBody inline
                    ? new ChoiceBody(inline.chain(), inline.variants(),
                            Optional.of(ChoiceDisjointness.derive(inline, merged)))
                    : body);
            if (value instanceof ChoiceBody choice && choice.variants().stream()
                    .noneMatch(variant -> variant instanceof TypeRef reference && pending.contains(reference.name()))) {
                value = new ChoiceBody(choice.chain(), choice.variants(),
                        Optional.of(ChoiceDisjointness.derive(choice, merged)));
            }
            if (value != entry.getValue()) {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    /**
     * {@code body} with {@code derive} applied to every type written in place inside it, at any depth, innermost
     * first; the same instance where it holds none. What the link derives over an entry's own body -- a choice's
     * {@code disjoint}, a reference's index -- it derives over a body written in place the same way, there being no
     * entry to hold it.
     */
    static Top mapInline(Top body, UnaryOperator<io.ltr8.tson.schema.meta.Type> derive) {
        return switch (body) {
            case RecordBody record when record.fields().values().stream()
                    .anyMatch(field -> field.type() instanceof TypeInline) -> record.withFields(record.mapFields(
                            field -> field.type() instanceof TypeInline inline
                                    ? field.withType(mapInline(inline, derive)) : field));
            case ArrayBody array when array.elementType() instanceof TypeInline inline -> new ArrayBody(
                    array.chain(), mapInline(inline, derive), array.voidable(), array.ordered(),
                    array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody map when map.valueType() instanceof TypeInline inline -> new MapBody(map.chain(),
                    map.keyType(), mapInline(inline, derive), map.voidable(), map.ordered(),
                    map.minItems(), map.maxItems());
            case NamespaceBody namespace when namespace.valueType() instanceof TypeInline inline -> new NamespaceBody(
                    namespace.chain(), namespace.keyType(), mapInline(inline, derive), namespace.ordered(),
                    namespace.minItems(), namespace.maxItems());
            case TupleBody tuple when tuple.elements().stream()
                    .anyMatch(element -> element.elementType() instanceof TypeInline) -> new TupleBody(tuple.chain(),
                    tuple.elements().stream().map(element -> element.elementType() instanceof TypeInline inline
                            ? new TupleElement(mapInline(inline, derive), element.voidable())
                            : element).toList());
            case ChoiceBody choice when choice.variants().stream().anyMatch(TypeInline.class::isInstance) ->
                    new ChoiceBody(choice.chain(), choice.variants().stream()
                            .map(variant -> variant instanceof TypeInline inline
                                    ? (TypeExpr) mapInline(inline, derive) : variant).toList(),
                            choice.disjoint());
            default -> body;
        };
    }

    /** A type written in place, the types written inside it derived first, then its own body. */
    private static TypeInline mapInline(TypeInline inline, UnaryOperator<io.ltr8.tson.schema.meta.Type> derive) {
        Top inner = mapInline(inline.of(), derive);
        return inline.withOf(derive.apply((io.ltr8.tson.schema.meta.Type) inner));
    }

    /**
     * Derives every reference's index ([TSON-SCHEMA] §4.2) over the fully-merged namespace: whether its {@code of}
     * names a constructor ({@link KindMembers#namesTypes}), and for one that does, the names it admits, by {@link
     * KindMembers}' construction walk. Every reference in the closure is recomputed, an imported one included: the
     * index is computed over the <em>governing</em> schema's closure, so one reference holds different members in two
     * schemas importing different sets, and whatever a body arrived carrying is discarded rather than trusted. A
     * message lists the members by name, as data holds a reference (§4.2). The index is never written: resolved
     * output states {@code of} alone.
     *
     * <p>Before the name-type checks, {@code checkNames} and validation, and after {@link #computeSubtypes}, which
     * no member depends on: the walk reads each entry's constructor and the constructors' {@code supertypes}, both
     * already resolved. A reference that names types and has no members admits no value, and the inhabitance check
     * reports it.
     *
     * <p>A reference over any other type carries what a qualified name reaches ({@link ReferenceBody.Values}): judged
     * by {@link ValuesByName} over the final namespace, which {@code reached} holds once the link has assembled it, so
     * a data document's read judges the name as a value body's is.
     *
     * <p>A partial link ({@link #linkReady}) admits each {@code pending} name as a member of every reference that
     * names types, and gives a reference that names values nothing to reach: a declaration not yet ready has no value
     * to judge, and a pin or default naming it is judged when the whole schema links, as a reference to it is.
     *
     * @param structure where a constructor is looked up first: the schema's one namespace, as {@code merged} is
     */
    private static Map<String, Top> computeKinds(Map<String, Top> merged, Map<String, TsonSchema.EntryFacts> facts,
                                                 Function<String, Top> structure, Set<String> pending,
                                                 AtomicReference<ValuesByName> reached, Map<String, Top> entries) {
        Map<String, Top> result = null;
        // A reference written in place is linked as an entry's is, at the slot that holds it.
        UnaryOperator<io.ltr8.tson.schema.meta.Type> link = body -> body instanceof ReferenceBody reference
                ? linkReference(reference, merged, facts, structure, pending, reached, entries) : body;
        for (Map.Entry<String, Top> entry : merged.entrySet()) {
            Top value = mapInline(entry.getValue(), link);
            if (value instanceof ReferenceBody reference) {
                value = linkReference(reference, merged, facts, structure, pending, reached, entries);
            }
            if (value != entry.getValue()) {
                if (result == null) {
                    result = new LinkedHashMap<>(merged);
                }
                result.put(entry.getKey(), value);
            }
        }
        return result == null ? merged : result;
    }

    /** One reference's index: whether its {@code of} names types, and the members or the value reach it admits. */
    private static ReferenceBody linkReference(ReferenceBody reference, Map<String, Top> merged,
                                               Map<String, TsonSchema.EntryFacts> facts, Function<String, Top> structure,
                                               Set<String> pending, AtomicReference<ValuesByName> reached,
                                               Map<String, Top> entries) {
        boolean namesTypes = KindMembers.namesTypes(reference.of().name(), structure);
        List<String> members = namesTypes ? KindMembers.of(reference, merged,
                name -> constructorOf(name, merged.get(name), facts), structure) : List.of();
        // A message lists the members by name, before the pending ones a partial link adds.
        List<String> shown = members;
        if (namesTypes && !pending.isEmpty()) {
            members = java.util.stream.Stream.concat(members.stream(), pending.stream()).distinct().toList();
        }
        return reference.linked(namesTypes, members, entries, shown,
                namesTypes || !pending.isEmpty() ? Optional.empty()
                        : Optional.of(valuesOf(reference.of().name(), reached)));
    }

    /**
     * What a name read under a reference over {@code of}, a type that is no constructor, reaches: judged by {@link
     * ValuesByName} over the final namespace, which {@code reached} holds once the link has assembled it.
     */
    private static ReferenceBody.Values valuesOf(String of, AtomicReference<ValuesByName> reached) {
        return (where, name) -> {
            ValuesByName judged = reached.get();
            if (judged == null) {
                throw new IllegalStateException("'" + name.text() + "' is read under 'meta.ref<" + of + ">' before "
                        + "the schema linking it has assembled its namespace");
            }
            return judged.reach(where, name, of);
        };
    }

    /**
     * Stage 1: the whole namespace of every {@code !!import}, in header order,
     * brought in as-is (§2.2.3's "merged entries keep their home namespace" -- no re-resolution here).
     *
     * <p><b>The namespace is flat and the merge is transitive</b> (§2.2.3): an import contributes everything its own
     * namespace holds, its own closure included. So a schema reached by two routes arrives once, and the importer sees
     * one flat name-to-type map with no hidden layers.
     *
     * <p><b>A collision is decided by entry identity, not by name occurrence.</b> The same schema reached
     * through several routes -- the diamond every practical schema forms by importing core.tn -- is one set
     * of entries, so re-arrival is unification, not conflict. Two *different* schemas declaring one name is
     * the real collision, and it is still an error: distinct types cannot share a name in a flat namespace.
     * That is also what makes a revision mismatch (one route reaching {@code /2026/37/m/core.tn}, another
     * {@code /2026/40/m/core.tn}) a hard error at namespace-construction time rather than a confusing field
     * conflict between two identically-spelled types much later.
     *
     * <p>Identity is the canonical one ([TSON-DATA] §2.2.1), so a pinned and an unpinned reference to one
     * schema unify -- which is what lets an author pin their own import while a peer's is unpinned.
     */
    private static Map<String, Top> mergeImports(TsonSchema importer, TsonSchemaLoader loader,
                                                 Map<String, String> origins,
                                                 Map<String, Normalization> enumForms,
                                                 Map<String, Top> nameTypes,
                                                 Map<String, TsonSchema.EntryFacts> facts,
                                                 Map<String, ApplyBody> applications) {
        Map<String, Top> merged = new LinkedHashMap<>();
        Set<String> alreadyImported = new LinkedHashSet<>();
        for (String importUri : importer.namespaceSources()) {
            String importIdentity = CanonicalIdentity.canonicalize(importUri);
            // Listing one schema twice (or under two spellings of one identity) is redundant, not an error:
            // the second mention asks for a namespace already present and contributes nothing new.
            if (!alreadyImported.add(importIdentity)) {
                continue;
            }
            TsonLinkedSchema imported = (loader == null ? Optional.<TsonLinkedSchema>empty()
                    : loader.load(importIdentity)).orElseThrow(() -> new SchemaValidationException(
                    "!!import '" + importUri + "' is not registered"));
            for (Map.Entry<String, Top> entry : imported.schema().entries().entrySet()) {
                String name = entry.getKey();
                // The import's own answer, not importIdentity: an entry it reached through an import of its
                // own belongs to whoever declared it, however many hops away that is.
                String origin = imported.originOf(name);
                String incumbent = origins.get(name);
                // A schema map of one path declared by several documents is one map, the union of their members:
                // each member merges under its path, and the map's entry is derived from them.
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
                    merged.put(name, unified(merged.get(name), entry.getValue()));
                    continue;
                }
                merged.put(name, entry.getValue());
                facts.put(name, imported.schema().factsOf(name));
                imported.schema().applicationOf(name).ifPresent(application -> applications.put(name, application));
                origins.put(name, origin);
                Normalization form = imported.enumForms().get(name);
                if (form != null) {
                    enumForms.put(name, form);
                }
                imported.nameType(name).ifPresent(type -> nameTypes.put(name, type));
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
    private static Top unified(Top incumbent, Top arriving) {
        if (TypeChain.subtypesOf(incumbent).containsAll(TypeChain.subtypesOf(arriving))) {
            return incumbent;
        }
        return withAddedSubtypes(incumbent, new LinkedHashSet<>(TypeChain.subtypesOf(arriving)));
    }

    // ── Validation ───────────────────────────────────────────────────────

    private static void validateEntry(String name, Top def, Map<String, Top> namespace,
                                       Function<String, Optional<String>> constructors,
                                       Function<String, String> shown, ValuesByName byName) {
        checkOpenEntryUsesEveryParameter(name, def);
        // A chain reaches a constructor whenever a refinement derives from one -- the kernel's own `set_type =>
        // array ^ {...}` resolves with [array, product, top, type] -- and every name on it is in the one namespace,
        // the defining schema's closure being inside the importer's (§2.2.3).
        for (String supertype : TypeChain.supertypesOf(def)) {
            if (!namespace.containsKey(supertype)) {
                throw new SchemaValidationException("'" + name + "' has an unresolved supertype '" + supertype + "'");
            }
        }
        for (String subtype : TypeChain.subtypesOf(def)) {
            if (!namespace.containsKey(subtype)) {
                throw new SchemaValidationException("'" + name + "' has an unresolved subtype '" + subtype + "'");
            }
        }
        validateBody(name, def, namespace, constructors, shown, byName);
    }

    private static void validateBody(String entryName, Top body, Map<String, Top> namespace,
                                      Function<String, Optional<String>> constructors, Function<String, String> shown,
                                      ValuesByName byName) {
        if (OpenEntry.isOpen(body)) {
            // An open record's references are judged by what they resolve to once substitution supplies the
            // arguments, so type-kind validation and inhabitance apply to its applications instead, where the whole
            // record resolves at once. Checking it here by substituting stand-in arguments would report errors on
            // templates that are correct for every argument anyone passes.
            //
            // Arity is the exception, and it is decidable: it asks how many parameters the *referenced*
            // entry declares, which no argument changes. See checkOpenArity for why that has to be asked
            // here rather than left to an application that may never happen.
            checkOpenArity(entryName, (RecordBody) body, namespace, OpenEntry.parameterNames(body));
            return;
        }
        checkCoherent(body);
        switch (body) {
            case RecordBody r -> {
                // The chain is checked by validateEntry, its every member a bare name in a closed body. A dependent
                // field names a sibling, judged with its sibling by §5.2's rules, and carries no value.
                DependentFields.check(entryName, r, namespace);
                for (Map.Entry<String, RecordField> field : r.fields().entrySet()) {
                    if (field.getValue().ownType().isPresent()) {
                        validateSlot(field.getValue().ownType().get(), namespace, constructors, entryName,
                                " field '" + field.getKey() + "'", shown, byName);
                        checkFieldValue(entryName, field.getKey(), field.getValue(), namespace, constructors,
                                byName);
                    }
                }
                for (FieldGroup group : r.groups()) {
                    for (String member : group.memberNames()) {
                        if (!r.fields().containsKey(member)) {
                            throw new SchemaValidationException(
                                    "'" + entryName + "' has a field group referencing unknown field '" + member + "'");
                        }
                    }
                }
                checkRestatedFieldTypes(entryName, r, namespace, shown);
            }
            // An alias may name a constructor (§3.3.1 applies it as its target), so no kind judges the target.
            // An alias naming a template bare names the template under another name (`ref_of => ref`),
            // applied as its target is, so it is no use of the template without its arguments.
            case Alias ref when OpenEntry.isOpen(namespace.get(AliasChain.terminal(ref.target().name(), namespace))) -> {
                if (!namespace.containsKey(ref.target().name())) {
                    throw new UnresolvedReference(entryName, "", ref.target().name());
                }
            }
            case Alias ref -> validateTypeRef(ref.target(), namespace, null,
                    entryName, "");
            case MapBody m -> {
                validateTypeRef(m.keyType(), namespace, constructors, entryName,
                        " key_type");
                validateSlot(m.valueType(), namespace, constructors, entryName, " value_type", shown, byName);
            }
            case NamespaceBody n -> {
                TypeRef key = n.keyType();
                validateTypeRef(key, namespace, constructors, entryName, " key_type");
                // A namespace's keys are names: `key_type` is typed meta.identifier_ref (§4.1, §7.4).
                if (constructors != null && namespace.containsKey(IDENTIFIER_TYPE)
                        && !KindMembers.isMember(key.name(), IDENTIFIER_TYPE, namespace::get, constructors,
                                namespace::get)) {
                    throw new SchemaValidationException("'" + entryName + "' key_type names '" + key.name()
                            + "', which is no identifier family -- a namespace's keys are names, so its key type "
                            + "is a member of meta.identifier_ref, a type built with identifier_type; a closed set "
                            + "of names is an identifier family with members");
                }
                validateSlot(n.valueType(), namespace, constructors, entryName, " value_type", shown, byName);
            }
            case ArrayBody a -> validateSlot(a.elementType(), namespace, constructors, entryName, " element_type",
                    shown, byName);
            case TupleBody t -> {
                int index = 0;
                for (TupleElement element : t.elements()) {
                    validateSlot(element.elementType(), namespace, constructors, entryName,
                            " element[" + index + "]", shown, byName);
                    index++;
                }
            }
            case ChoiceBody c -> {
                int index = 0;
                for (TypeExpr variant : c.variants()) {
                    validateSlot(variant, namespace, constructors, entryName, " variant[" + index + "]", shown,
                            byName);
                    index++;
                }
                checkVariantsAreDistinct(entryName, c, namespace);
                checkVariantsAreNotVoid(entryName, c, namespace);
            }
            // `of` resolves in the schema's one namespace and names any type or constructor ([TSON-SCHEMA] §4.2,
            // §5.12): what it admits is the names whose entries' values are of it, which is the slot's judgement
            // wherever a name is written. The index is the linker's own computation, so nothing else is validated.
            case ReferenceBody reference -> {
                // `of` holds a name (§4.2).
                if (namespace.get(AliasChain.terminal(reference.of().name(), namespace)) == null) {
                    throw new UnresolvedReference(entryName, " of", reference.of().name());
                }
                KindMembers.ofRefusal(reference, namespace::get).ifPresent(refusal -> {
                    throw new SchemaValidationException("'" + entryName + "' is a reference, but " + refusal);
                });
                checkReferenceNarrows(entryName, reference, namespace);
            }
            case VoidType ignored -> {
            }
            case IdentifierType ignored -> {
            }
            case EnumBody ignored -> {
            }
            case IntegerType ignored -> {
            }
            case TextType ignored -> {
            }
            case UriType ignored -> {
            }
            case IriType ignored -> {
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
            case BytesType ignored -> {
            }
            case DateType ignored -> {
            }
            case TimeType ignored -> {
            }
            case DateTimeType ignored -> {
            }
            case DurationType ignored -> {
            }
            case PeriodType ignored -> {
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
            case HostnameType ignored -> {
            }
            case HostType ignored -> {
            }
            case MediaTypeType ignored -> {
            }
            case ComplexType ignored -> {
            }
            case DeclaredType ignored -> {
            }
            // A record's dependent field is judged with its sibling (DependentFields) and never reaches here, so a
            // dependent type that does is declared, or written in place at some other slot: it is bound by the
            // record whose field's type it is, and nowhere else has a sibling to name (§5.2).
            case DependentBody dependent -> throw new SchemaValidationException("the dependent type '"
                    + dependent.text() + "' stands only directly at a record field's type, written in place: it names "
                    + "a field of that record, '" + dependent.on() + "', and a declaration or any other slot has none "
                    + "(§5.2)");
            // Every application closes where it is written but one inside an open record over its parameters, and an
            // open record is validated as its applications, so one here is no closing left undone: it was written so.
            case ApplyBody apply -> {
                String head = apply.head().name();
                if (!namespace.containsKey(head)) {
                    throw new UnresolvedReference(entryName, "", head);
                }
                checkArity(head, apply.arguments().size(), namespace, List.of(), "'" + entryName + "'");
                throw new SchemaValidationException("the application '" + apply.text() + "' stands written in "
                        + "place outside an open record: an application stays in place only inside an open record, "
                        + "with one of its parameters among its arguments, and closes everywhere else (§5.10)");
            }
            // A value entry was read against its type, so the read judged its shape; every reference it holds is at a
            // slot typed by a kind or a reference, recorded by the read and judged by its slot (ValuesByName), so
            // nothing is asked of the host class a value binds to.
            case ValueEntry ignored -> {
            }
        }
    }

    /**
     * §5.4: "The resolver validates that each variant resolves to a distinct type."
     *
     * <p><b>Judged at the end of each variant's alias chain, which is the whole point of the rule.</b>
     * §8.3 makes an alias and its
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
     * <p>A type written in place is compared by its constructor and fields, and never with a name: a name and a
     * construction are two types, even a name for the construction written beside it ([TSON-SCHEMA] §8.2).
     */
    private static void checkVariantsAreDistinct(String entryName, ChoiceBody choice,
                                                  Map<String, Top> namespace) {
        // A type written in place is its own construction, compared by its body; a name by where its chain ends.
        Map<TypeExpr, String> seen = new LinkedHashMap<>();
        for (TypeExpr variant : choice.variants()) {
            TypeExpr flattened = switch (variant) {
                case TypeRef reference -> TypeRef.of(AliasChain.terminal(reference.name(), namespace));
                case TypeInline inline -> inline;
            };
            String first = seen.putIfAbsent(flattened, variant.text());
            if (first == null) {
                continue;
            }
            throw new SchemaValidationException("'" + entryName + "' " + (first.equals(variant.text())
                    ? "lists the variant '" + variant.text() + "' twice"
                    : "variants '" + first + "' and '" + variant.text() + "' both resolve to '"
                            + flattened.text() + "'")
                    + " -- §5.4 requires each variant to resolve to a distinct type");
        }
    }

    /**
     * A variant must not resolve to {@code void} (§5.4): {@code (T | void)} spells
     * optionality as a choice, and optionality belongs to the position -- a field's {@code ?} state, the
     * {@code _} sentinel -- never to the type occupying it. Judged at the end of the chain, like
     * distinctness, so an alias of {@code void} is caught under whatever name the author wrote, and by its
     * {@code !meta.void_type} body, so a sibling of the kernel's {@code void} is caught as the kernel's is.
     */
    private static void checkVariantsAreNotVoid(String entryName, ChoiceBody choice,
                                                 Map<String, Top> namespace) {
        // A type written in place is a container, a tuple, a choice, a reference or a record, never void.
        for (TypeRef variant : choice.variants().stream().flatMap(v -> TypeExpr.named(v).stream()).toList()) {
            if (AliasChain.resolvesToVoid(variant.name(), namespace)) {
                throw new SchemaValidationException("'" + entryName + "' has a variant"
                        + (variant.name().equals("void") ? "" : " '" + variant.name() + "'")
                        + " resolving to 'void' -- optionality is not choice (§5.4): a value's absence is the "
                        + "position's own state, so mark the position optional ('?') instead of uniting its "
                        + "type with void");
            }
        }
    }

    /** {@code fallback} entries, overridden by {@code primary} on collision -- {@code primary} isn't mutated. */
    private static Map<String, Top> mergeWithFallback(Map<String, Top> primary,
                                                                   Map<String, Top> fallback) {
        Map<String, Top> combined = new LinkedHashMap<>(fallback);
        combined.putAll(primary);
        return combined;
    }

    /**
     * §5.10's parameter-usage rule: an <em>open</em> entry references every parameter it declares. {@code box
     * => <T> { v: text }} declares {@code T} and never uses it, so no application of it could differ from any
     * other -- the parameter is a mistake, not a degenerate-but-legal template.
     *
     * <p><b>A {@link SchemaValidationException}.</b> A parameter list is author-written, so an unused one
     * is the author's error rather than a library fault.
     *
     * <p>The converse -- §5.10's closed-entry rule, that a closed entry carries no parameter in a value slot
     * -- has no check of its own, a parameter and a literal sharing one slot: at a closed entry there are no
     * parameters for a token to resolve into, so a token there <em>is</em> a literal (§8.1's shadowing rule)
     * and there is nothing to detect. The rule's reference half is unaffected, and needs no code of its own:
     * {@link #validateTypeRef} accepts a name only if the namespace holds it, so at a closed entry a parameter
     * reference is already an unresolved one.
     *
     * <p>Deciding this rather than admitting the degenerate form also settles a question the open
     * representation would otherwise have to answer: with an unreferenced parameter rejected, a template
     * whose bindings are all concrete cannot exist, so nothing has to say whether unused parameters
     * participate in comparing two open entries.
     */
    private static void checkOpenEntryUsesEveryParameter(String entryName, Top def) {
        List<String> parameters = OpenEntry.parameterNames(def);
        if (parameters.isEmpty()) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        collectBodyNames(def, referenced);
        for (String parameter : parameters) {
            if (!referenced.contains(parameter)) {
                throw new SchemaValidationException("'" + entryName + "' declares the type parameter '"
                        + parameter + "' and never references it, so every application of it would denote the "
                        + "same type -- a declared parameter must be used (§5.10)");
            }
        }
    }

    /** Every name an entry's body mentions, for {@link #checkOpenEntryUsesEveryParameter}. */
    private static void collectBodyNames(Top body, Set<String> into) {
        if (OpenEntry.isOpen(body)) {
            // The one question an open record answers without being resolved: the names its references write.
            into.addAll(OpenEntry.names(body));
            return;
        }
        switch (body) {
            case RecordBody record -> record.fields().values().forEach(field -> {
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
            case NamespaceBody namespace -> {
                collectNames(namespace.keyType(), into);
                collectNames(namespace.valueType(), into);
            }
            case TupleBody tuple -> tuple.elements().forEach(e -> collectNames(e.elementType(), into));
            case ChoiceBody choice -> choice.variants().forEach(v -> collectNames(v, into));
            case Alias alias -> collectNames(alias.target(), into);
            case Atom ignored -> { } // an atom body names no type
            case DeclaredType ignored -> { } // its variants are the closure's, named by no field
            case DependentBody ignored -> { } // it names a field of the enclosing record, never an entry
            case ApplyBody apply -> {
                into.add(apply.head().name());
                apply.arguments().forEach(argument -> collectNames(argument, into));
            }
            case ValueEntry ignored -> { } // a value names no type of its own
        }
    }

    /** The names a type slot holds: a name's, or every name inside a type written in place. */
    private static void collectNames(TypeExpr type, Set<String> into) {
        switch (type) {
            case TypeRef reference -> collectNames(reference, into);
            case TypeInline inline -> collectBodyNames(inline.of(), into);
        }
    }

    private static void collectNames(TypeRef ref, Set<String> into) {
        into.add(ref.name());
    }

    /**
     * §8.2's materialisation-time coherence check, over <b>every</b> family: "Materialisation also runs the
     * value-level checks that open bindings deferred: family coherence rules whose operands were parameters
     * -- {@code min_items <= max_items} (§5.3) <b>and their kin</b> -- ... are verified once concrete, and a
     * violation is a resolver error reported at the materialising application."
     *
     * <p><b>"And their kin" needs no enumeration, which is why this is one line.</b> Each family already owns
     * its own coherence rule ({@link Atom#coherenceCheck}, {@link Product#coherenceCheck}) for the body an
     * author writes literally; the rule an application closes onto is the same rule over the same facets, so
     * asking every body here covers the named case and its kin together. Anything §5.5 adds to a family later
     * arrives here for free.
     *
     * <p><b>It runs on every entry</b>, because this phase is the one place that sees them all exactly once
     * however they were produced. A declared body was already asked at
     * resolution, beside its own constructor's vocabulary check -- and one that failed there is replaced by a
     * placeholder and never reaches this, so nothing is reported twice. What is left for this to catch is
     * exactly the set resolution never produced: the forms an application closes to.
     */
    private static void checkCoherent(Top body) {
        List<String> violations = switch (body) {
            case Atom atom -> atom.coherenceCheck();
            case Product product -> product.coherenceCheck();
            case Sum sum -> sum.coherenceCheck();
            case ApplyBody ignored -> List.of();
            case Alias ignored -> List.of();
            case ValueEntry ignored -> List.of();
        };
        if (!violations.isEmpty()) {
            // No name leads this one, deliberately. Only a form an application closed to can reach here -- a
            // declared body failed the same check one phase earlier, under its own declaration's name -- and the
            // location already names the declaration that wrote the application; what is left to say is what the
            // application closed onto.
            throw new SchemaValidationException("the constraints this application closes onto contradict "
                    + "each other: " + String.join("; ", violations) + " -- no value can satisfy them, so "
                    + "nothing could ever be a valid instance of the type it denotes");
        }
    }

    /**
     * [TSON-SCHEMA] §5.7's rule for a refinement of a reference, {@code !meta.type_ref ^ { of: … }}: its {@code of}
     * is its source's or IS-A it ({@link ReferenceBody#ofNarrowing}). The resolver binds the refinement before the
     * names {@code of} holds need be resolved, so the IS-A is asked here, over the one namespace both resolve in; the
     * source is the reference the refinement's chain names, past its alias hops.
     */
    private static void checkReferenceNarrows(String entryName, ReferenceBody reference, Map<String, Top> namespace) {
        for (String supertype : TypeChain.supertypesOf(reference)) {
            if (namespace.get(AliasChain.terminal(supertype, namespace)) instanceof ReferenceBody source) {
                List<String> violations = source.ofNarrowing(reference, (sub, sup) -> isA(sub, sup, namespace));
                if (!violations.isEmpty()) {
                    throw new SchemaValidationException("'" + entryName + "': not a valid refinement of '!"
                            + supertype + "' (§5.7): " + String.join("; ", violations));
                }
            }
        }
    }

    /**
     * [TSON-SCHEMA] §5.7's rule for a restated field's type, which §5.8 applies to a composition's tightening
     * entries: refinement only restricts, so a field a record restates is typed by its source's type or a type IS-A
     * it, §7.2's IS-A asked as {@link #checkReferenceNarrows} asks it, past alias hops on both sides, and nothing
     * structural beside it (§5.7): {@code [int8]} restating {@code [int32]} is no narrowing. §5.7's
     * one other narrowing is {@code void} at a voidable position, whose value set {@code {_}} is inside every voidable
     * position's; the voidable order itself is the resolver's ({@code DefinitionResolver.refines}). The resolver binds
     * the restatement before the names it holds need be ready, so the IS-A is asked here, over the one namespace both
     * resolve in.
     *
     * <p><b>Judged against the nearest closed record on the chain.</b> A supertype another supertype lists was judged
     * when that one linked, so only the minimal ones are asked, and the diagnostic names the source the author wrote.
     * An applied operand is judged through the closed entry its application denotes, where one exists. A field typed
     * by an application not yet closed, or by a name no entry holds, is the reference checks' to report.
     */
    private static void checkRestatedFieldTypes(String entryName, RecordBody record, Map<String, Top> namespace,
                                                Function<String, String> shown) {
        List<String> supertypes = TypeChain.supertypesOf(record);
        for (String supertype : supertypes) {
            boolean nearest = supertypes.stream().noneMatch(other -> !other.equals(supertype)
                    && TypeChain.supertypesOf(namespace.get(AliasChain.terminal(other, namespace))).contains(supertype));
            if (!nearest || !(namespace.get(AliasChain.terminal(supertype, namespace)) instanceof RecordBody source)) {
                continue;
            }
            List<String> violations = new ArrayList<>();
            for (Map.Entry<String, RecordField> inherited : source.fields().entrySet()) {
                RecordField restated = record.fields().get(inherited.getKey());
                if (restated == null) {
                    continue;
                }
                Optional<String> dependent = DependentFields.restates(inherited.getKey(), inherited.getValue(),
                        restated);
                if (dependent.isPresent()) {
                    violations.add(dependent.get());
                } else if (restated.sibling().isEmpty() && !narrows(inherited.getValue(), restated,
                        namespace)) {
                    boolean oneIsANameOneInPlace = restated.typeExpr() instanceof TypeInline
                            != inherited.getValue().typeExpr() instanceof TypeInline;
                    violations.add("field '" + inherited.getKey() + "' is typed '"
                            + shownType(restated.typeExpr(), shown) + "', which is not '"
                            + shownType(inherited.getValue().typeExpr(), shown) + "' or IS-A it"
                            + (oneIsANameOneInPlace ? " -- a name and a type written in place are never one type "
                                    + "(§8.2), so write '" + shownType(inherited.getValue().typeExpr(), shown)
                                    + "' as it is inherited" : ""));
                }
            }
            if (!violations.isEmpty()) {
                throw new SchemaValidationException("'" + entryName + "': not a valid refinement of '"
                        + shown.apply(supertype) + "' (§5.7): " + String.join("; ", violations));
            }
        }
    }

    /** What a slot holds as a message names it: a name as {@code shown} renders it, an inline type as written. */
    private static String shownType(TypeExpr type, Function<String, String> shown) {
        return switch (type) {
            case TypeRef reference -> shown.apply(reference.name());
            case TypeInline inline -> inline.text();
        };
    }

    /**
     * Whether {@code restated}'s type narrows {@code inherited}'s, as {@link #checkRestatedFieldTypes} states it. A
     * type written in place IS-A nothing but itself, and a name and a construction are never one type ([TSON-SCHEMA]
     * §8.2), even a name for the very construction written: two types written in place narrow exactly when they are
     * one, the same constructor and fields, and a name never narrows to a construction nor a construction to a name.
     */
    private static boolean narrows(RecordField inherited, RecordField restated, Map<String, Top> namespace) {
        if (inherited.typeExpr() instanceof TypeInline || restated.typeExpr() instanceof TypeInline) {
            return inherited.typeExpr().equals(restated.typeExpr());
        }
        TypeRef from = (TypeRef) inherited.typeExpr();
        TypeRef to = (TypeRef) restated.typeExpr();
        if (!namespace.containsKey(from.name()) || !namespace.containsKey(to.name())) {
            return true;
        }
        return isA(to.name(), from.name(), namespace) || restated.voidable()
                && namespace.get(AliasChain.terminal(to.name(), namespace)) instanceof VoidType;
    }

    /**
     * §7.2's IS-A between two names of {@code namespace}: past their alias hops they are one entry, or the first's
     * chain lists the second.
     */
    private static boolean isA(String sub, String sup, Map<String, Top> namespace) {
        String wanted = AliasChain.terminal(sup, namespace);
        String terminal = AliasChain.terminal(sub, namespace);
        if (terminal.equals(wanted)) {
            return true;
        }
        Top definition = namespace.get(terminal);
        return definition != null && TypeChain.supertypesOf(definition).stream()
                .anyMatch(supertype -> AliasChain.terminal(supertype, namespace).equals(wanted));
    }

    /**
     * [TSON-SCHEMA] §5.2's dependency between a field's two halves: a {@code ~}/{@code =} value must be a
     * value of the field's own declared type. The kernel states it on {@code meta.record_field.value} -- "the
     * type of fixed/default values, which must be the field's declared type" -- and calls it "a dependency
     * the schema language does not express directly", which is what leaves it to a check like this one.
     *
     * <p><b>This is the one place a pin is judged.</b> It runs once every entry has resolved, over the field
     * type's body rather than its compiled reader, so it waits on no reader ([TSON-SCHEMA] §5.2; a held value
     * orders nothing). The compiled record reader decodes the same value once every reader exists ({@code
     * RecordAbstractReader.settleHeldValues}), for what it injects and compares, and treats a refusal there as
     * this check and the reader disagreeing. The verdict does not change as this library improves, so by the
     * project's own classification test it is the author's error, and this phase is where an author error
     * against one declaration is already reported with the declaration's own name and line.
     *
     * <p><b>Atoms and enums only, and the rest is refused.</b> A field typed by a record, container or choice
     * carries no value ({@link #notAScalarType}). Only a closed record reaches here: an open record's member is
     * checked once materialisation has substituted its arguments, against the argument it was closed with.
     *
     * <p><b>A reference's value is a name.</b> At a reference over a constructor it is judged a member ({@link
     * #checkKindValue}); at one over any other type it is a value's qualified name, judged by what it reaches as a
     * value body's names are ({@link ValuesByName#reach}), and refused against this entry.
     */
    private static void checkFieldValue(String entryName, String fieldName, RecordField field,
                                        Map<String, Top> namespace,
                                        Function<String, Optional<String>> constructors, ValuesByName byName) {
        if (field.value().isEmpty()) {
            return;
        }
        // The chain end is what has to be checked, not the hop: a field typed by an alias
        // (`a => text`, `f: a = hello`) states a value of whatever the alias names. Resolved output states
        // the chain rather than rewriting the use site past it, so the walk happens here.
        // A type written in place is its own body, and has no entry an enum's label form is recorded under.
        String terminal = switch (field.typeExpr()) {
            case TypeRef reference -> AliasChain.terminal(reference.name(), namespace);
            case TypeInline inline -> null;
        };
        Top target = field.typeExpr() instanceof TypeInline inline ? inline.of() : namespace.get(terminal);
        // An unresolved reference is already reported by validateTypeRef; a target that is still open (or an
        // application of one) has no single body to check against until materialisation closes it. A body
        // still an Alias means the walk stopped on a cycle or an argument-bearing target, neither of
        // which has one either.
        if (target == null || OpenEntry.isOpen(target) || target instanceof ApplyBody || target instanceof Alias) {
            return;
        }
        Token value = field.value().get();
        if (target instanceof ReferenceBody reference && KindMembers.namesTypes(reference.of().name(), namespace::get)) {
            checkKindValue(entryName, fieldName, field, value, reference, namespace, constructors);
            return;
        }
        if (target instanceof ReferenceBody reference) {
            // A value's qualified name, judged by what it reaches as a value body's names are (§5.12).
            byName.reach("'" + entryName + "': field '" + fieldName + "''s "
                    + (field.role() == FieldRole.DEFAULT ? "default" : "fixed value"),
                    TypeRef.of(value.text()), reference.of().name());
            return;
        }
        Optional<AtomType<?>> parser = AtomParsers.forType(target, EnumLabels.labelForm(target,
                terminal == null ? Optional.empty() : constructors.apply(terminal), namespace, namespace::get));
        if (parser.isEmpty()) {
            throw notAScalarType(entryName, fieldName, field, value, target);
        }
        try {
            parser.get().read(value.text());
        } catch (AtomTypeException e) {
            // The field's two halves are what the author has to reconcile, so both are named, in the order
            // they are written, and the value is echoed as the schema spells it -- quoted if it was quoted,
            // so the author reads back their own line rather than a normalisation of it. The atom's own
            // message follows: it already states the rule and cites the section, so nothing here restates it.
            throw new SchemaValidationException("'" + entryName + "': field '" + fieldName + "' is "
                    + "declared '" + field.typeExpr().text() + "', but its "
                    + (field.role() == FieldRole.DEFAULT ? "default" : "fixed value") + " "
                    + asWritten(value) + " is not a value of that type -- " + e.getMessage() + ". §5.2 makes "
                    + "a field's fixed or default value a value of the field's own declared type");
        }
    }

    /**
     * A {@code ~}/{@code =} value at a field typed by a reference that names types: a type name, judged a member by
     * the walk over the entry it resolves to ([TSON-SCHEMA] §4.2) -- {@code enum => enum_type ^ { name_type?: =
     * identifier }} pins a name, and the pin is judged here, after resolution, in the schema's one namespace, which
     * holds the constructor pinning it and everything that constructor's schema reaches. A name resolving to
     * nothing, or to an entry whose value is not of the reference's {@code of}, is refused with the field's own two
     * halves named, as any other value of the wrong type is.
     */
    private static void checkKindValue(String entryName, String fieldName, RecordField field, Token value,
                                       ReferenceBody kind, Map<String, Top> namespace,
                                       Function<String, Optional<String>> constructors) {
        if (KindMembers.isMember(value.text(), kind.of().name(), namespace::get, constructors, namespace::get)) {
            return;
        }
        throw new SchemaValidationException("'" + entryName + "': field '" + fieldName + "' is "
                + "declared '" + field.typeExpr().text() + "', but its "
                + (field.role() == FieldRole.DEFAULT ? "default" : "fixed value") + " " + asWritten(value)
                + " is not a value of that type -- " + (namespace.get(value.text()) == null
                        ? "it names nothing in scope"
                        : "'" + value.text() + "' is not a member of the kind, the names of the entries whose "
                                + "values are of '" + kind.of().text() + "'")
                + ". §5.2 makes a field's fixed or default value a value of the field's own declared type");
    }

    /**
     * <b>Only a scalar-typed field may carry a value, and the verdict is about the field rather than about
     * the token.</b> §12.1 admits only a bare token after {@code ~}/{@code =} -- writing {@code ~ [...]} or
     * {@code ~ { ... }} is a syntax error, not another value -- so for a non-scalar field there is no better
     * token to suggest and saying "this token is not a record" would invite the author to look for one.
     *
     * <p><b>A record and a choice are refused too, though a token can reach them.</b> §5.6's positional form
     * fills a record with exactly one bare {@code REQUIRED} field from a bare value, and a choice
     * discriminates a token to an atom-typed variant, so both have tokens a <em>read</em> would accept.
     * Admitting them here would make "may this field have a default?" depend on the referenced type's field
     * count or variant list -- a rule an author has to compute rather than remember. One line settles it
     * instead: a fixed or default value is available on a scalar-typed field and nowhere else. §5.6 is a
     * spelling rule for data values, not a claim that a record <em>is</em> a token, and this reads §5.2's
     * "the value must be the field's declared type" as requiring a type a token denotes directly, which is
     * what §5.2's "Which fields may carry a value" states.
     */
    private static SchemaValidationException notAScalarType(String entryName, String fieldName, RecordField field,
                                                                 Token value, Top body) {
        return new SchemaValidationException("'" + entryName + "': field '" + fieldName + "' is "
                + "declared '" + field.typeExpr().text() + "', which is " + describe(body) + ", so it cannot "
                + "have " + (field.role() == FieldRole.DEFAULT ? "a default" : "a fixed value")
                + " -- " + asWritten(value) + " is a token, and §5.2 admits only a bare token there. A "
                + "fixed or default value is available on a field typed by an atom or an enum, and nowhere "
                + "else: drop the modifier, or declare the field with a scalar type");
    }

    /** What a non-scalar body is, for the message -- named the way an author would name it, not by class. */
    private static String describe(Top body) {
        return switch (body) {
            case ArrayBody ignored -> "an array";
            case MapBody ignored -> "a map";
            case NamespaceBody ignored -> "a namespace";
            case TupleBody ignored -> "a tuple";
            case RecordBody ignored -> "a record";
            case ChoiceBody ignored -> "a choice";
            case VoidType ignored -> "the void type";
            case DeclaredType ignored ->
                    "a declared type, whose values name their own type rather than being a token shape";
            default -> "not a scalar type";
        };
    }

    /** A token echoed the way the schema spells it, so a quoted value is visibly quoted in the message. */
    private static String asWritten(Token token) {
        return token.form() == Token.Form.UNQUOTED ? token.text() : "\"" + token.text() + "\"";
    }

    /**
     * What a type slot holds, validated: a name as {@link #validateTypeRef} judges it, and a type written in place
     * as its body would be judged as an entry, named in a message by its form as written ({@code [some_typo]},
     * {@code box<some_typo>}, [TSON-SCHEMA] §8.2) and reported against the entry that holds it. An unresolved name
     * inside a record in place is marked so, its text being an open record's ({@link #heldDeclarationNaming}).
     */
    private static void validateSlot(TypeExpr type, Map<String, Top> namespace,
                                     Function<String, Optional<String>> constructors, String subject, String trail,
                                     Function<String, String> shown, ValuesByName byName) {
        switch (type) {
            case TypeRef reference -> validateTypeRef(reference, namespace, constructors, subject, trail);
            case TypeInline inline -> {
                try {
                    validateBody(inline.text(), inline.of(), namespace, constructors, shown, byName);
                } catch (UnresolvedReference e) {
                    throw inline.of() instanceof RecordBody ? e.heldInPlace() : e.namedByForm();
                }
            }
        }
    }

    /**
     * One reference validated. The entry it sits in is passed as {@code subject} and the path within that
     * entry as {@code trail} ({@code " field 'w'"}, {@code " element[1]"}) rather than pre-joined, because
     * {@link UnresolvedReference} has to be able to re-state itself against a different subject: a defect an
     * open record deferred surfaces inside a record an application closes to in place and belongs to the open
     * record whose text wrote it, and only the subject differs between the two statements of it.
     *
     * <p>A slot typed {@code meta.type_ref} holds the name of a type a value can have: resolved, then judged a member of
     * the kind ({@link KindMembers}) where {@code constructors} is given, and a value entry refused with its own
     * diagnostic. A constructor is a member: its value is a record, whose instances are declarations. An alias's
     * target passes {@code null} and is judged by resolution alone, an alias of a constructor being applied as its
     * target is.
     */
    private static void validateTypeRef(TypeRef ref, Map<String, Top> namespace,
                                         Function<String, Optional<String>> constructors,
                                         String subject, String trail) {
        String context = "'" + subject + "'" + trail;
        if (ref instanceof ParamRef) {
            // A closed body holds no parameter, so one here would resolve only as a parameter (§8.1), and is judged
            // by the name it is, never by an entry that happens to share it.
            throw new UnresolvedReference(subject, trail, ref.name());
        }
        Top target = namespace.get(ref.name());
        if (target instanceof ValueEntry value) {
            // A value entry is a value and never a type. Without this the misuse resolves, links and compiles, and
            // fails only when a document is read against it.
            throw new SchemaValidationException(context + " names '" + ref.name() + "', which is "
                    + ValuesByName.aValueNoType(value));
        }
        if (!namespace.containsKey(ref.name())) {
            throw new UnresolvedReference(subject, trail, ref.name());
        }
        checkArity(ref.name(), 0, namespace, List.of(), context);
        // Membership walks construction to `type`, so it is judged only where `type` is in reach: a namespace built
        // without the kernel (a test's, a fragment's) gets resolution alone, as a kind with no meta gets no verdict.
        if (constructors != null
                && (namespace.containsKey(TYPE) || namespace.containsKey(TYPE))
                && !KindMembers.isMember(ref.name(), TYPE, namespace::get, constructors, namespace::get)) {
            throw new SchemaValidationException(context + " names '" + ref.name() + "', which is no type a value "
                    + "can have -- a slot typed 'meta.type_ref' holds the name of an entry whose value is of 'type'");
        }
    }

    /**
     * §5.10's arity rule over the references an open record writes -- {@code chain => <T> { tail:
     * chain<T, T>? }} applies two arguments to a one-parameter template, and nothing ever closes that
     * application, so deferring the check would let the template ship with the mistake in it.
     *
     * <p><b>It can be answered without substituting</b>, which is why it belongs here at all: arity compares
     * the argument count written against the parameter count the referenced entry declares, and neither
     * number depends on what the arguments resolve to.
     *
     * <p><b>An open record's every reference, bare ones included</b>: its field types and parents are references of
     * the model, so a template named bare there is refused at the template's own declaration (§5.10).
     */
    private static void checkOpenArity(String entryName, RecordBody open,
            Map<String, Top> namespace, List<String> ownParameters) {
        String context = "'" + entryName + "'";
        // A head is checked with its application's arguments, and every other name with none.
        Set<TypeRef> heads = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        TypeSlots.Rewrite arity = new TypeSlots.Rewrite() {
            @Override
            public TypeRef atName(TypeRef reference) {
                if (!heads.contains(reference)) {
                    checkArity(reference.name(), 0, namespace, ownParameters, context);
                }
                return reference;
            }

            @Override
            public io.ltr8.tson.schema.meta.Type inPlace(io.ltr8.tson.schema.meta.Type body) {
                if (body instanceof ApplyBody application) {
                    heads.add(application.head());
                    checkArity(application.head().name(), application.arguments().size(), namespace, ownParameters,
                            context);
                    requireOpen(context, application, namespace);
                }
                return body;
            }
        };
        open.fields().values().forEach(field -> TypeSlots.map(field.type(), arity));
        open.parents().forEach(parent -> TypeSlots.map(parent, arity));
    }

    /**
     * An application stays written in place only over a parameter of the open record holding it, and only an open
     * record's: a constructor's is its construction and one over names alone has closed ([TSON-SCHEMA] §5.10). A
     * resolved form read as output is held to it here, where its open records are linked.
     */
    private static void requireOpen(String context, ApplyBody application, Map<String, Top> namespace) {
        boolean overAParameter = application.arguments().stream().anyMatch(ParamRef.class::isInstance);
        Top head = namespace.get(AliasChain.terminal(application.head().name(), namespace));
        if (head != null && (!overAParameter || !OpenEntry.isOpen(head))) {
            throw new SchemaValidationException(context + ": the application '" + application.text() + "' stands "
                    + "written in place, and an application stays in place only inside an open record, over one of "
                    + "its parameters, applying an open record; " + (OpenEntry.isOpen(head)
                    ? "over names alone it closes to the record it denotes" : "a constructor's is its construction")
                    + " (§5.10)");
        }
    }

    /**
     * §5.10's arity rule, over every reference in the schema: a reference supplies exactly as many arguments
     * as the entry it names declares parameters. Three shapes of author error collapse into it -- too many
     * ({@code chain<T, T>} for a one-parameter {@code chain}), too few, and <b>none at all</b>
     * ({@code use => { u: box } } naming a template without applying it, which is the common one).
     *
     * <p><b>The zero-argument case is why this check exists here rather than at an application site.</b> An
     * unapplied template reference is not an application, so nothing on the materialisation path sees it: left
     * unchecked, it would link, compile and fail only at <em>read</em> time, as a library fault, for what is
     * plainly the author's error.
     *
     * <p>By the time linking runs, {@code TemplateMaterialiser} has rewritten every application it could
     * close, so what reaches here is the set that genuinely needs checking: references inside template
     * bodies, which stay open by design, and bare names anywhere.
     *
     * <p>A reference naming one of the enclosing declaration's own <em>parameters</em> has no arity to check
     * -- a parameter is not an entry and declares nothing -- but it is not simply skipped: §5.10 admits no
     * head abstraction, so a parameter carrying an argument list is refused here, where the author wrote it.
     * Left to run, {@code <T> { v: T<text> }} would substitute whatever {@code T} binds into an application's
     * head, which names none of what the author did.
     */
    private static void checkArity(String name, int supplied, Map<String, Top> namespace,
                                    List<String> ownParameters, String context) {
        if (ownParameters.contains(name)) {
            if (supplied > 0) {
                throw new SchemaValidationException(context + ": '" + name + "' is a type parameter "
                        + "applied to arguments -- a parameter stands for a type, never for a template, and "
                        + "§5.10 admits no head abstraction, so '" + name + "<...>' is no form. Name the "
                        + "template and apply that, or take the applied type as the parameter instead");
            }
            return;
        }
        // An application through an alias of a template applies the template ([TSON-SCHEMA] §8.3).
        Top referenced = namespace.get(supplied == 0 ? name : AliasChain.terminal(name, namespace));
        if (referenced == null) {
            return; // a name no entry holds is resolution's to refuse, not arity's
        }
        List<String> parameters = OpenEntry.parameterNames(referenced);
        int declared = parameters.size();
        if (declared == supplied) {
            return;
        }
        String head = AliasChain.terminal(name, namespace);
        if (supplied > 0 && ConstructionSugar.constructs(head, referenced)) {
            // Construction sugar (§5.3): one argument per argument slot of the constructor.
            try {
                ConstructionSugar.requireArity(name,
                        ConstructionSugar.slots((RecordBody) referenced, namespace::get), supplied);
            } catch (SchemaValidationException e) {
                throw new SchemaValidationException(context + ": " + e.getMessage());
            }
            return;
        }
        if (declared == 0) {
            throw new SchemaValidationException(context + ": '" + name + "' declares no type "
                    + "parameters, so '" + name + "<...>' applies arguments to something that takes "
                    + "none (§5.10); drop the argument list");
        }
        if (supplied == 0) {
            throw new SchemaValidationException(context + ": '" + name + "' is a template taking "
                    + declared + " type argument" + (declared == 1 ? "" : "s") + " " + parameters
                    + ", and a template is not a type -- write '" + name + "<...>' with its arguments, or, "
                    + "for any member of a family, name the abstract record its applications compose (§5.10)");
        }
        throw new SchemaValidationException(context + ": '" + name + "' takes " + declared
                + " type argument" + (declared == 1 ? "" : "s") + " " + parameters + ", but "
                + supplied + " " + (supplied == 1 ? "was" : "were") + " applied (§5.10)");
    }
}
