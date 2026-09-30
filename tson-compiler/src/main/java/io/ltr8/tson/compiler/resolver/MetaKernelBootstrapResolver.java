package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.SchemaPositions;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueType;
import io.ltr8.tson.schema.meta.VoidType;
import io.ltr8.tson.schema.meta.UriType;

import java.math.BigInteger;
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
 * resolves what {@link DefinitionResolver} already can in one source-order pass, holding the kernel's own
 * templates as it goes; closes the declarations that name an application ({@code enum =>
 * enum_of<identifier>}) through {@link TemplateMaterialiser}; and makes a pass over the deferred {@code
 * Instance} declarations, split around that closing, now that every constructor they reference (including
 * ones declared *later* in the file, e.g. {@code boolean => !enum [true false]} comes before {@code enum})
 * has a resolved entry to transfer a kind from.
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
 * this class is in the middle of producing. With the application pass, what this buys is that linking has
 * nothing left to materialize -- the desugared and closed entries are ordinary entries by the time the linker
 * sees them.
 *
 * <p><b>Every {@code Instance} declaration resolves through {@link #instanceBody}, a closed,
 * hand-written switch</b> -- a schema-driven compiled reader can't safely bootstrap meta-kernel from
 * its own in-progress state: {@code integer_size => { bits: ... signed: boolean }} is a first-pass entry
 * whose {@code signed} field already references {@code boolean}, which is not resolved until the second
 * pass, so there is no point at which a reader could be compiled against a complete schema. Rather than
 * deciding case by case which of meta-kernel's own instances need hand-picking, {@link #instanceBody}
 * hand-picks all of them uniformly -- meta-kernel only ever instantiates constructors in three known
 * shapes: a bare {@code {}}, a bare array of tokens ({@code enum}), and the binding record {@link
 * SchemaDesugarer} emits for a container application. Closing a template reads its substituted body the
 * same way, through {@link #heldBody}.
 *
 * <p>{@link #getMetaKernelSchema()} reads meta-kernel.tn packaged as a classpath resource, via
 * {@link TsonBundledSchemas#fetch} (`tson-schema` -- see that class's own Javadoc for why its own
 * `build.gradle.kts` copies the file straight from the repo's own {@code spec/m/meta-kernel.tn}
 * snapshot into that module's own resources, not this one's), so the bootstrap works from a built
 * jar, not just a repo checkout. This class hard-codes that one real, bundled source deliberately,
 * per the lock-down above, rather than accepting a caller-supplied {@link SchemaSource}.
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
        Map<String, TypeDefinition> entries = resolveEntries(document);
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
        List<SchemaMap.Declaration> applications = new ArrayList<>();
        List<SchemaMap.Declaration> instances = new ArrayList<>();
        List<SchemaMap.Declaration> refinements = new ArrayList<>();

        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            if (declaration.typeDef() instanceof Instance instance && !instance.typeParams().isEmpty()) {
                // An *open* instance is a §5.10 template, not a construction: its body is held -- the
                // application as written -- and stays unread until materialisation substitutes the parameters
                // away. Constructing it instead would resolve `element_type: T` into a reference to a type
                // called T. Held here rather than with the instances below because holding needs nothing
                // resolved, and the application pass must find it.
                entries.put(declaration.name(), new TypeDefinition(Optional.of(TypeRef.of(instance.target())),
                        TypeKind.TEMPLATE, List.of(), List.of(),
                        HeldBody.held(instance.typeParams(), instance.value())));
                continue;
            }
            if (declaration.typeDef() instanceof Instance) {
                // Deferred to the instance pass: an Instance's own kind is transferred from its target, which
                // (e.g. "enum", declared long after "boolean" uses it) may not be resolved yet in source order.
                instances.add(declaration);
                continue;
            }
            if (declaration.typeDef() instanceof AtomRefinement) {
                // Deferred for the same reason, one step further along: a refinement's source is an instance,
                // so it cannot resolve until the instance pass below has produced one.
                refinements.add(declaration);
                continue;
            }
            if (isClosedApplication(declaration)) {
                // Deferred to the application pass: closing `enum_of<identifier>` substitutes into a template
                // that may be declared later in the file, and needs its parameters stamped first.
                applications.add(declaration);
                continue;
            }
            entries.put(declaration.name(), resolver.resolve(declaration));
        }

        // Instances in two rounds around the application pass: closing walks a template's held body against
        // the vocabulary of the constructors it applies, which needs the containers the desugar phase lifted
        // (`fields: [record_field]`) already built, while `!enum [...]` needs `enum`, which the application
        // pass produces.
        constructInstances(instances, entries);
        closeApplications(applications, entries);
        constructInstances(instances, entries);
        for (SchemaMap.Declaration declaration : refinements) {
            AtomRefinement refinement = (AtomRefinement) declaration.typeDef();
            TypeDefinition target = entries.get(refinement.target());
            if (target == null) {
                throw new IllegalStateException("'" + declaration.name() + "': refines '" + refinement.target()
                        + "', which meta-kernel does not declare");
            }
            entries.put(declaration.name(), new TypeDefinition(target.source(), target.kind(),
                    List.of(refinement.target()), List.of(),
                    refinedBody(declaration.name(), refinement, target)));
        }
        return entries;
    }

    /**
     * Constructs each deferred instance whose constructor has an entry, removing it from {@code instances}; the
     * rest wait for a later round. §5.5: construction transfers only the target's kind -- no supertypes, no
     * parameters.
     */
    private static void constructInstances(List<SchemaMap.Declaration> instances,
                                           Map<String, TypeDefinition> entries) {
        instances.removeIf(declaration -> {
            Instance instance = (Instance) declaration.typeDef();
            TypeDefinition target = entries.get(instance.target());
            if (target == null) {
                return false;
            }
            instanceBody(instance).ifPresent(body -> entries.put(declaration.name(),
                    new TypeDefinition(Optional.of(TypeRef.of(instance.target())), target.kind(),
                            List.of(), List.of(), body)));
            return true;
        });
    }

    /** {@code enum => enum_of<identifier>}: a declaration naming a fully-bound application (§8.2). */
    private static boolean isClosedApplication(SchemaMap.Declaration declaration) {
        return declaration.typeDef() instanceof ReferenceTypeDef reference && reference.typeParams().isEmpty()
                && reference.ref() instanceof GenericRef;
    }

    /**
     * The application pass: each declaration naming a fully-bound application becomes the entry it denotes, as
     * ordinary resolution makes it (§8.2), through the same {@link TemplateMaterialiser}.
     *
     * <p><b>What differs from ordinary resolution is only the reader.</b> Closing substitutes into a held body
     * and reads the result through its constructor, and the kernel's constructors have no compiled reader yet --
     * they are what this class is producing. {@link #heldBody} stands in, over the two shapes the kernel's
     * templates hold: the record a composition template closes to, and the {@code set_type} binding record.
     *
     * <p>The templates' parameters are stamped first ({@link ParameterTypes}), so each argument is classified
     * and checked by the same rules an ordinary application meets, and the kernel's held entries carry the same
     * recorded parameter types the registered kernel does.
     */
    private static void closeApplications(List<SchemaMap.Declaration> applications,
                                          Map<String, TypeDefinition> entries) {
        if (applications.isEmpty()) {
            return;
        }
        Map<String, Map<String, TemplateParam>> parameters = ParameterTypes.inferAll(entries,
                Set.copyOf(entries.keySet()), Map.of(), entries::get, (name, error) -> {
                    throw error;
                });
        entries.replaceAll((name, definition) -> parameters.containsKey(name)
                && definition.body() instanceof TemplateBody held
                ? definition.withBody(held.withParameters(parameters.get(name))) : definition);
        TemplateMaterialiser materialiser = new TemplateMaterialiser(entries::get, entries::put,
                MetaKernelBootstrapResolver::heldBody, Set.of(), entries::get, () -> null);
        materialiser.parameterKinds(ParameterTypes.kinds(parameters));
        DefinitionResolver closing = new DefinitionResolver(NEVER_CALLED, null, EMPTY_META_DEFINITIONS,
                entries::get, new ApplicationCloser() {

                    @Override
                    public String closeApplication(TypeRef application) {
                        return materialiser.closeApplication(application);
                    }

                    @Override
                    public TypeDefinition closeApplicationInto(String declaredName, TypeRef application) {
                        return materialiser.closeApplicationInto(declaredName, application);
                    }
                }, SchemaPositions.none());
        for (SchemaMap.Declaration declaration : applications) {
            TypeDefinition closed = closing.resolve(declaration);
            if (closed.body() instanceof Reference) {
                throw new IllegalStateException("'" + declaration.name() + "': the application it names did not "
                        + "close -- meta-kernel's own templates are declared in the kernel, so every application "
                        + "it writes closes");
            }
            entries.put(declaration.name(), closed);
        }
    }

    /**
     * A substituted held body read through its constructor, by hand: {@link #closeApplications}' stand-in for
     * the compiled reader ordinary materialisation uses. Bounded the way {@link #instanceBody} is -- the two
     * shapes the kernel's templates hold, and a refusal by name for any other.
     */
    private static Top heldBody(String constructor, DataValue value) {
        return switch (constructor) {
            case "set_type" -> toArrayBody(value, true);
            case "record" -> toRecordBody(value);
            default -> throw new UnsupportedOperationException("a held body applying '" + constructor + "'; "
                    + "meta-kernel's bootstrap closes the kernel's own templates by hand and covers record and "
                    + "set_type only");
        };
    }

    /**
     * A composition template's closed record -- {@code enum_of}'s {@code atom & { members: set<T> }} -- as the
     * {@link RecordBody} it denotes: supertypes, and required fields each naming one type.
     */
    private static RecordBody toRecordBody(DataValue value) {
        if (!(value.coreValue() instanceof RecordValue record)) {
            throw new IllegalStateException("expected a record body, found " + value.coreValue());
        }
        List<TypeRef> supertypes = new ArrayList<>();
        List<RecordField> fields = new ArrayList<>();
        for (RecordValue.Field slot : record.fields()) {
            switch (slot.name()) {
                case "supertypes" -> elements(slot).forEach(element -> supertypes.add(TypeRef.of(typeName(element))));
                case "fields" -> elements(slot).forEach(element -> fields.add(toRecordField(element)));
                default -> throw new UnsupportedOperationException("a held record body writing '" + slot.name()
                        + "'; meta-kernel's bootstrap reads supertypes and fields only");
            }
        }
        return new RecordBody(supertypes, fields, List.of(), RecordExtensionType.OPEN);
    }

    private static RecordField toRecordField(CoreValue value) {
        if (!(value instanceof RecordValue record)) {
            throw new IllegalStateException("expected a record_field, found " + value);
        }
        String name = null;
        String type = null;
        for (RecordValue.Field slot : record.fields()) {
            switch (slot.name()) {
                case "name" -> name = typeName(slot.value().value().coreValue());
                case "type" -> type = typeName(slot.value().value().coreValue());
                default -> throw new UnsupportedOperationException("a held record_field writing '" + slot.name()
                        + "'; meta-kernel's bootstrap reads a required field's name and type only");
            }
        }
        if (name == null || type == null) {
            throw new IllegalStateException("a held record_field without a name and a type: " + record);
        }
        return RecordField.required(name, TypeRef.of(type));
    }

    private static List<CoreValue> elements(RecordValue.Field slot) {
        if (!(slot.value().value().coreValue() instanceof ArrayValue array)) {
            throw new IllegalStateException("expected an array for '" + slot.name() + "'");
        }
        return array.elements().stream().map(element -> element.value().coreValue()).toList();
    }

    /** A name as a closed held body spells it: a bare token, or a {@code type_ref} carrying only its name. */
    private static String typeName(CoreValue value) {
        if (value instanceof TokenValue token) {
            return token.text();
        }
        if (value instanceof RecordValue record && record.fields().size() == 1
                && record.fields().getFirst().name().equals("name")
                && record.fields().getFirst().value().value().coreValue() instanceof TokenValue token) {
            return token.text();
        }
        throw new IllegalStateException("expected a closed type name, found " + value);
    }

    /**
     * The hand-written merge for an atom refinement, {@link #instanceBody}'s twin and bounded the same way:
     * meta-kernel refines exactly one family, so this handles that one and refuses the rest by name rather
     * than pretending to be general.
     *
     * <p>Ordinary resolution does this through a {@code DataClassObjectWriter} round trip and the governing
     * meta's compiled reader ({@code DefinitionResolver.resolveAtomRefinement}). The bootstrap has neither:
     * the reader it would use is the one being produced. So the kernel pays for a refinement in hand-written
     * code, which is the same bargain {@link #instanceBody} already strikes -- and the reason to keep the
     * kernel's own refinements few.
     */
    private static Top refinedBody(String name, AtomRefinement refinement, TypeDefinition target) {
        if (!(target.body() instanceof IntegerType source)) {
            throw new UnsupportedOperationException("'" + name + "' refines '" + refinement.target()
                    + "', whose body is a " + target.body().getClass().getSimpleName() + ". meta-kernel's "
                    + "bootstrap merges an atom refinement by hand and covers the integer family only; "
                    + "extend refinedBody to add another");
        }
        Optional<BigInteger> min = source.min();
        Optional<BigInteger> max = source.max();
        for (RecordValue.Field binding : bindingFields(name, refinement)) {
            BigInteger value = new BigInteger(bindingText(name, binding));
            switch (binding.name()) {
                case "min" -> min = Optional.of(value);
                case "max" -> max = Optional.of(value);
                default -> throw new UnsupportedOperationException("'" + name + "' refines '"
                        + refinement.target() + "' with '" + binding.name() + "'; meta-kernel's bootstrap "
                        + "merges min and max only");
            }
        }
        return new IntegerType(source.size(), min, source.exclusiveMin(), max, source.exclusiveMax(),
                source.multipleOf(), source.members());
    }

    private static List<RecordValue.Field> bindingFields(String name, AtomRefinement refinement) {
        if (!(refinement.bindings().coreValue() instanceof RecordValue record)) {
            throw new UnsupportedOperationException("'" + name + "': a refinement's bindings are a record");
        }
        return record.fields();
    }

    private static String bindingText(String name, RecordValue.Field binding) {
        if (!(binding.value().value().coreValue() instanceof TokenValue token)) {
            throw new UnsupportedOperationException("'" + name + "': '" + binding.name()
                    + "' takes a token in meta-kernel's own bootstrap");
        }
        return token.text();
    }

    /**
     * The direct, hand-written construction for one of meta-kernel's own eight real constructor
     * targets (see this class's own Javadoc) -- {@link Optional#empty()} for anything else, left
     * for the caller to decide what that means (today: the declaration is simply left out of the
     * result, rather than failing the whole bootstrap; unexercised against the real fixture, since
     * all eight real targets are covered).
     *
     * <p>Package-private, not {@code private} -- {@code MetaKernelBootstrapResolverTest} exercises the
     * unrecognized-target and wrong-shape-body branches directly, since neither is reachable through
     * the real fixture (every real target is one of the eight, and every empty-bodied one really is
     * empty).
     */
    static Optional<Top> instanceBody(Instance instance) {
        return switch (instance.target()) {
            case "value_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(new ValueType());
            }
            case "void_type" -> {
                requireEmptyBody(instance);
                yield Optional.of(new VoidType());
            }
            case "identifier_type" -> {
                requireIdentifierProfile(instance);
                yield Optional.of(IdentifierType.IDENTIFIER);
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
            case "set_type" -> Optional.of(toArrayBody(instance.value(), true));
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

    /**
     * {@code identifier => !identifier_type { continue_add: "-" }}, checked to be exactly that: the hand-picked
     * constant is the profile the lexer, parser, resolver and linker already hold, since the kernel's own names
     * are read by it before the kernel exists. The kernel stating another would be a kernel that describes a
     * profile this implementation does not run.
     */
    private static void requireIdentifierProfile(Instance instance) {
        if (!(instance.value().coreValue() instanceof RecordValue record) || record.fields().size() != 1
                || !record.fields().getFirst().name().equals("continue_add")
                || !(record.fields().getFirst().value().value().coreValue() instanceof TokenValue token)
                || !token.text().equals("-")) {
            throw new IllegalStateException("expected { continue_add: \"-\" } for !identifier_type, found "
                    + instance.value().coreValue());
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
