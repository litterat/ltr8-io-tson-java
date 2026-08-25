package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.TsonSchemaSource;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UriType;

import java.util.ArrayList;
import java.util.Set;
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
 * bootstrap() == true} -- see that class's own Javadoc). This class is a stateless compiler/resolver,
 * the same shape as {@link TsonSchemaParser}/{@link DefinitionResolver} -- {@link
 * #getMetaKernelSchema()} returns a freshly-built value rather than being one itself. Its own output
 * is resolved-but-not-yet-linked -- a caller links it (via {@code TsonSchemaLinker#linkBootstrap},
 * never {@code TsonSchemaRegistry#register} directly -- see that class's own Javadoc for why) and,
 * separately, compiles it -- a distinct, later stage this class has nothing to do with.
 *
 * <p>Deliberately locked down to exactly one public method, taking no arguments -- this class exists
 * to bootstrap *the* real meta-kernel document (see {@link TsonBundledSchemas#META_KERNEL_ID}),
 * nothing else.
 *
 * <p><b>{@link SchemaDesugarer} runs first, exactly as it does for every other schema</b> -- meta-kernel
 * writes plenty of sugar ({@code fields: [record_field]}, <code>schema =&gt; {type_name =&gt;
 * type_definition}</code>) and there is no reason for the kernel to resolve a different set of forms than
 * anything else does. It needs no accommodation at all: the desugar table is fixed by the sugar forms
 * (§5.3), so the phase consults no governing meta -- which for meta-kernel would have been the very entries
 * this class is in the middle of producing. What this buys is that linking has nothing left to
 * materialize -- the desugared entries are ordinary declarations by the time the linker sees them.
 *
 * <p><b>Every {@code Instance} declaration resolves through {@link #instanceBody}, a closed,
 * hand-written switch</b> -- a schema-driven compiled reader can't safely bootstrap meta-kernel from
 * its own in-progress state: {@code integer_size => { bits: ... signed: boolean }} is a first-pass entry
 * whose {@code signed} field already references {@code boolean}, which is not resolved until the second
 * pass, so there is no point at which a reader could be compiled against a complete schema. Rather than
 * deciding case by case which of meta-kernel's own instances need hand-picking, {@link #instanceBody}
 * hand-picks all of them uniformly -- meta-kernel only ever instantiates constructors in three known
 * shapes: a bare {@code {}}, a bare array of tokens ({@code enum}), and the binding record {@link
 * SchemaDesugarer} emits for a container application.
 *
 * <p>{@link #getMetaKernelSchema()} reads meta-kernel.tn packaged as a classpath resource, via
 * {@link TsonBundledSchemas#fetch} (`tson-schema` -- see that class's own Javadoc for why its own
 * `build.gradle.kts` copies the file straight from the repo's own {@code spec/m/meta-kernel.tn}
 * snapshot into that module's own resources, not this one's), so the bootstrap works from a built
 * jar, not just a repo checkout. This class hard-codes that one real, bundled source deliberately,
 * per the lock-down above, rather than accepting a caller-supplied {@link TsonSchemaSource}.
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
        String source = TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID);
        SchemaDocument document = new TsonSchemaParser(source).parseSchemaDocument();
        // §8.3 applies here as it does to any other schema. The bootstrap is a shorter route to the same
        // resolved form, not a different one, and this output governs anything whose !!meta is meta-kernel
        // -- so a use site flattened by ordinary resolution and unflattened here would be two answers to
        // one question. No minted entries to stop at: the bootstrap runs no materialisation, and meta-kernel
        // imports nothing, so its own map is the whole namespace a chain can walk.
        Map<String, TypeDefinition> resolved = resolveEntries(document);
        Map<String, TypeDefinition> entries = ReferenceFlattener.flatten(resolved, resolved, Set.of());
        String id = document.id().orElseThrow(() -> new IllegalStateException(
                "meta-kernel.tn has no !!id -- this should never happen for the real, bundled fixture"));
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
        // Meta-kernel desugars like every other schema, and needs no special case: the desugar table is fixed
        // by the sugar forms (§5.3), so the phase consults no governing meta -- which for meta-kernel would
        // have been the very entries this method is in the middle of producing.
        document = SchemaDesugarer.desugar(document, Set.of());
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
            // Emitted by SchemaDesugarer above, never written by hand in the fixture. array and set differ
            // only in the defaults set tightens (§5.7): ordered/duplicating vs unordered/unique.
            case "array" -> Optional.of(toArrayBody(instance.value(), false));
            case "set" -> Optional.of(toArrayBody(instance.value(), true));
            case "map" -> Optional.of(toMapBody(instance.value()));
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

    /** {@code !array { element_type: T }} / {@code !set { element_type: T }} as the body each denotes. */
    private static ArrayBody toArrayBody(DataValue value, boolean unique) {
        TypeRef element = TypeRef.of(bindingField(value, "element_type"));
        return new ArrayBody(element, ElementState.REQUIRED, unique, unique, Optional.empty(), Optional.empty());
    }

    /** {@code !map { key_type: K  value_type: V }} as the body it denotes. */
    private static MapBody toMapBody(DataValue value) {
        return MapBody.of(TypeRef.of(bindingField(value, "key_type")),
                TypeRef.of(bindingField(value, "value_type")));
    }

    /** One field of a desugared instance's binding record -- always a bare token naming a type. */
    private static String bindingField(DataValue value, String name) {
        if (!(value.coreValue() instanceof RecordValue record)) {
            throw new IllegalStateException("expected a binding record, found " + value.coreValue());
        }
        for (RecordValue.Field field : record.fields()) {
            if (field.name().equals(name) && field.value().value().coreValue() instanceof TokenValue token) {
                return token.text();
            }
        }
        throw new IllegalStateException("no '" + name + "' in " + record);
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
