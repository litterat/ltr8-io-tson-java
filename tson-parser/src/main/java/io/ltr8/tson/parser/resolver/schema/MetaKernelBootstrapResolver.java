package io.ltr8.tson.parser.resolver.schema;

import io.ltr8.tson.parser.TsonSchemaParser;
import io.ltr8.tson.parser.ast.ArrayValue;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.EmptyBrace;
import io.ltr8.tson.parser.ast.ScopedValue;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UriType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses meta-kernel's own source text into its pre-loaded {@link TsonSchema} (Part 2 §1.5): "The
 * {@code !!meta} directive names this file itself -- the one deliberate circularity in the series,
 * closed by pre-loading rather than by resolution: implementations ship the kernel's resolved
 * structure, and this document describes it." Ordinary schema resolution can't bootstrap
 * meta-kernel from nothing -- resolving a constructor-*application* instance ({@code !C value},
 * §5.5, e.g. {@code integer => !integer_type {}}) needs {@code C}'s own vocabulary already known,
 * and for meta-kernel, every {@code C} it uses is defined *within the same file* -- so this class
 * resolves what {@link DefinitionResolver} already can in one source-order pass, then makes a second
 * pass over the deferred {@code Instance} declarations now that every constructor they reference
 * (including ones declared *later* in the file, e.g. {@code boolean => !enum [true false]} comes
 * before {@code enum}'s own declaration) has a resolved entry to transfer a kind from.
 *
 * <p>Produces a plain, unmaterialized {@link TsonSchema} ({@code materialised() == false}, {@code
 * bootstrap() == true} -- see that class's own Javadoc). This class is a stateless parser/resolver,
 * the same shape as {@link TsonSchemaParser}/{@link DefinitionResolver} -- {@link
 * #getMetaKernelSchema()} returns a freshly-built value rather than being one itself. Its own output
 * is resolved-but-not-yet-linked -- a caller links it (via {@code TsonSchemaLinker#linkBootstrap},
 * never {@code TsonSchemaRegistry#register} directly -- see that class's own Javadoc for why) and,
 * separately, compiles it -- a distinct, later stage this class has nothing to do with.
 *
 * <p>Deliberately locked down to exactly one public method, taking no arguments -- this class exists
 * to bootstrap *the* real meta-kernel document (see {@link BundledSchemaSource#META_KERNEL_ID}),
 * nothing else.
 *
 * <p><b>Every {@code Instance} declaration resolves through {@link #instanceBody}, a closed,
 * hand-written switch</b> -- a schema-driven compiled reader can't safely bootstrap meta-kernel from
 * its own in-progress state: {@code enum}'s own {@code members: set<token>} field is
 * argument-bearing, and only a materialization pass over the *whole* schema (never run over
 * meta-kernel while meta-kernel is still being produced) makes that safe to compile a reader
 * against -- running materialization over the first-pass-only state doesn't work either, since
 * {@code integer_size => { bits: ... signed: boolean }} is itself a first-pass entry whose {@code
 * signed} field already references {@code boolean}, unresolved until the second pass. Rather than
 * deciding case by case which of meta-kernel's own instances need hand-picking, {@link
 * #instanceBody} hand-picks all of them uniformly -- meta-kernel only ever instantiates its own six
 * real constructors in two known shapes (a bare {@code {}} or a bare array of tokens).
 *
 * <p>{@link #getMetaKernelSchema()} reads meta-kernel.tn1 packaged as a classpath resource, via
 * {@link BundledSchemaSource} (see this module's {@code build.gradle.kts}, which copies it straight
 * from the repo's own {@code spec/m/meta-kernel.tn1} snapshot into this module's resources at build
 * time), so the bootstrap works from a built jar, not just a repo checkout.
 */
public final class MetaKernelBootstrapResolver {

    private MetaKernelBootstrapResolver() {
    }

    /**
     * Parses and resolves meta-kernel's own real, bundled source text (see class Javadoc). The one
     * and only place in this codebase that ever constructs a {@link TsonSchema} with {@code
     * bootstrap: true} -- {@link TsonSchema#bootstrap()}'s own Javadoc explains why that matters:
     * {@code TsonSchemaRegistry.register}/{@code materializeBootstrap} both gate on it specifically so
     * meta-kernel's own identity can only ever be registered by something that genuinely came from
     * here, not merely something shaped like it.
     */
    public static TsonSchema getMetaKernelSchema() {
        String source = BundledSchemaSource.INSTANCE.fetch(BundledSchemaSource.META_KERNEL_ID);
        SchemaDocument document = new TsonSchemaParser(source).parseSchemaDocument();
        Map<String, TypeDefinition> entries = resolveEntries(document);
        String id = document.id().orElseThrow(() -> new IllegalStateException(
                "meta-kernel.tn1 has no !!id -- this should never happen for the real, bundled fixture"));
        return new TsonSchema(id, document.meta(), document.imports(), entries, true);
    }

    /**
     * A {@link DefinitionMetaReader} that always throws -- this class's own first pass only ever
     * calls {@link DefinitionResolver#resolve}, and never on an {@code Instance}
     * declaration (those are filtered out below, deferred to the second pass, which resolves them
     * through {@link #instanceBody} directly, never through {@code DefinitionResolver} at all -- see
     * this class's own Javadoc), so {@code DefinitionResolver#bindAtomInstance} can never actually be
     * reached from here. A loud failure if that assumption is ever wrong is safer than silently
     * handing {@code DefinitionResolver} a reader that could return something meaningless.
     */
    private static final DefinitionMetaReader NEVER_CALLED = (type, value) -> {
        throw new UnsupportedOperationException("'" + type + "': meta-kernel's own bootstrap resolves every "
                + "Instance declaration through instanceBody directly -- this reader should never be called");
    };

    /** Meta-kernel governs itself, so it has no separate structure namespace to fall back to -- this class's own first pass never reaches {@link DefinitionResolver#resolveInstance} at all, the one place a structure namespace is ever consulted. */
    private static final DefinitionGetter EMPTY_META_DEFINITIONS = name -> null;

    private static Map<String, TypeDefinition> resolveEntries(SchemaDocument document) {
        Map<String, TypeDefinition> entries = new LinkedHashMap<>();
        DefinitionResolver resolver = new DefinitionResolver(NEVER_CALLED, EMPTY_META_DEFINITIONS, entries::get);
        List<SchemaMap.Declaration> instances = new ArrayList<>();

        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            if (declaration.typeDef() instanceof Instance) {
                // Deferred to the second pass: an Instance's own kind is transferred from its
                // target, which (e.g. "enum", declared long after "boolean" uses it) may not be
                // resolved yet in source order.
                instances.add(declaration);
                continue;
            }
            entries.put(declaration.name(), resolver.resolve(declaration));
        }

        for (SchemaMap.Declaration declaration : instances) {
            Instance instance = (Instance) declaration.typeDef();
            TypeDefinition target = entries.get(instance.target());
            if (target == null) {
                continue;
            }
            // §5.5: constructor application transfers only the target's kind; no supertypes, no
            // parameters -- this is construction, not composition or refinement.
            instanceBody(instance).ifPresent(body -> entries.put(declaration.name(),
                    new TypeDefinition(Optional.of(TypeRef.of(instance.target())), target.kind(), List.of(),
                            false, List.of(), List.of(), Optional.empty(), body)));
        }
        return entries;
    }

    /**
     * The direct, hand-written construction for one of meta-kernel's own six real constructor
     * targets (see this class's own Javadoc) -- {@link Optional#empty()} for anything else, left
     * for the caller to decide what that means (today: the declaration is simply left out of the
     * result, rather than failing the whole bootstrap; unexercised against the real fixture, since
     * all six real targets are covered).
     *
     * <p>Package-private, not {@code private} -- {@code MetaKernelBootstrapResolverTest} exercises the
     * unrecognized-target and wrong-shape-body branches directly, since neither is reachable through
     * the real fixture (every real target is one of the six, and every empty-bodied one really is
     * empty).
     */
    static Optional<Top> instanceBody(Instance instance) {
        return switch (instance.target()) {
            case "unit" -> {
                requireEmptyBody(instance);
                yield Optional.of(new Unit());
            }
            case "integer_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(IntegerType.UNCONSTRAINED);
            }
            case "text_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(TextType.UNCONSTRAINED);
            }
            case "uri_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(UriType.UNCONSTRAINED);
            }
            case "regex_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(RegexType.UNCONSTRAINED);
            }
            case "enum" -> Optional.of(toEnumBody(instance.value()));
            default -> Optional.empty();
        };
    }

    /** Every empty-bodied target above is only ever instantiated as a bare {@code {}} in the real fixture -- checked rather than assumed, since each one's own constraint value is a hand-picked constant, not parsed from the instance body. */
    private static void requireEmptyBody(Instance instance) {
        if (!(instance.value().coreValue() instanceof EmptyBrace)) {
            throw new IllegalStateException(
                    "expected {} for !" + instance.target() + ", found " + instance.value().coreValue());
        }
    }

    /**
     * {@code !enum [true false]}'s value is a bare array (§5.6's positional form for a
     * single-field constructor), not {@code { members: [...] } }.
     */
    static EnumBody toEnumBody(DataValue value) {
        if (!(value.coreValue() instanceof ArrayValue array)) {
            throw new IllegalStateException("expected an array for !enum, found " + value.coreValue());
        }
        List<String> members = new ArrayList<>();
        for (ScopedValue element : array.elements()) {
            if (!(element.value().coreValue() instanceof TokenValue token)) {
                throw new IllegalStateException("expected a token enum member, found " + element.value().coreValue());
            }
            members.add(token.text());
        }
        return new EnumBody(members);
    }
}
