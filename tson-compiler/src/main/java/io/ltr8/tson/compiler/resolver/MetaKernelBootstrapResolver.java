package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.compiler.SchemaPositions;
import io.ltr8.tson.compiler.TsonCompiledSchema;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonSchemaCompiler;
import io.ltr8.tson.compiler.TsonSchemaLinker;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.config.SchemaMetaNameBinder;
import io.ltr8.tson.compiler.reader.PendingTypeReader;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.KindBody;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.TemplateParam;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.ValueType;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;

/**
 * Resolves meta-kernel's own source text into the {@link TsonSchema} that governs its ordinary resolution (Part 2
 * §1.5): the {@code !!meta} directive names the file itself, the one circularity in the series, closed by
 * pre-loading. Every constructor the kernel applies is declared in the kernel, so no compiled governing meta exists
 * to read its instances through until this class has produced one.
 *
 * <p><b>The order is {@code SPEC-FEEDBACK.md #4}'s.</b> Reading a value against a type waits on that type being
 * ready, and on each type a part of the value is read against as the read reaches it; a reference and a held value
 * (a field's default or pin) order nothing. So:
 *
 * <ul>
 *   <li><b>The pre-loaded set is hand-built</b> ({@link #instanceBody}, {@link #refinedBody}): the instances and the
 *       one refinement on the kernel's one wait cycle, the cycle holding {@code record} -- {@link #PRE_LOADED} and
 *       the arrays lifted over {@link #PRE_LOADED_ELEMENTS}. Rule 4 makes the set a measurement of the kernel, and
 *       {@code MetaKernelBootstrapResolverTest} holds it to the measured cycle, so a kernel edit that moves the cycle
 *       fails there rather than here.</li>
 *   <li><b>A declaration no read produces is resolved from its grammar</b> by {@link DefinitionResolver}, as {@link
 *       SchemaResolver} resolves a record: a composition, a refinement of a record, an alias, a template. It reads
 *       nothing, so it is ready as soon as the entries it composes are.</li>
 *   <li><b>Every other instance and atom refinement is read through the ordinary path</b>, {@link
 *       DefinitionResolver}'s, whose {@link DefinitionMetaReader} reads through readers compiled from the entries
 *       ready so far ({@link TsonSchemaLinker#linkBootstrap(TsonSchema, Set)}, {@link
 *       TsonSchemaCompiler#compileBootstrap}). An entry not yet ready compiles to a {@link PendingTypeReader}: a
 *       read that reaches one stops ({@link PendingTypeReader.NotReady}), the entry it names is made ready on
 *       demand, the ready entries are compiled again, and the read starts over. A held value reached through one
 *       is bound as written, since it orders nothing -- which is how {@code iri} is read against {@code iri_type},
 *       whose {@code spec} pin is typed by {@code iri} itself.</li>
 * </ul>
 *
 * <p>The order is computed, never the order entries are written in: an entry is made ready the first time
 * anything asks for it, and the {@link Bootstrap#resolving} stack refuses an entry that waits on itself, naming the
 * chain -- a wait cycle outside the pre-loaded set, which only a kernel edit can introduce. Everything closes here:
 * every entry is ready before {@link #getMetaKernelSchema()} returns, and its output is the kernel's resolved form,
 * entry for entry what the kernel's ordinary resolution against it produces.
 *
 * <p>The output is resolved and not linked: a caller links it through {@link TsonSchemaLinker#linkBootstrap(
 * TsonSchema)} -- never {@code TsonSchemaRegistry#register}, which refuses a bootstrap schema -- and compiles it.
 * {@link SchemaDesugarer} runs first, as for every schema: the desugar table is fixed by the sugar forms (§5.3), so
 * it consults no governing meta. The class is locked down to one public method taking no arguments: it bootstraps
 * the bundled meta-kernel ({@link TsonBundledSchemas#META_KERNEL_ID}), fetched from the classpath so the bootstrap
 * works from a built jar, and nothing else.
 */
public final class MetaKernelBootstrapResolver {

    /**
     * The kernel's named instances and refinements on its one wait cycle, built by hand: {@code value}, the
     * {@code boolean} and {@code field_role} enums, {@code enum}'s member set, the {@code integer}, {@code text} and
     * {@code identifier} atoms, {@code type_kind}, and {@code non_negative_integer}, the one refinement.
     */
    private static final Set<String> PRE_LOADED = Set.of("value", "boolean", "field_role", "enum_set", "integer",
            "non_negative_integer", "text", "identifier", "type_kind");

    /**
     * The element types whose lifted arrays are on the cycle: {@code [type_name]}, {@code [field_group]} and {@code
     * [text; 1..]}, and an array of one of those ({@code [[text; 1..]; 1..]}). A lifted array is named by its
     * content (§8.2), so it is designated by what it holds rather than by its name.
     */
    private static final Set<String> PRE_LOADED_ELEMENTS = Set.of("type_name", "field_group", "text");

    private MetaKernelBootstrapResolver() {
    }

    /**
     * Parses and resolves meta-kernel's own bundled source text (see class Javadoc). The one place in this codebase
     * that constructs a {@link TsonSchema} with {@code bootstrap: true} -- {@link TsonSchema#bootstrap()} says why
     * that matters: {@code TsonSchemaRegistry.register} gates on it, so meta-kernel's own identity can only be
     * registered by something that came from here.
     */
    public static TsonSchema getMetaKernelSchema() {
        TsonSchemaParser parser = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID));
        SchemaDocument document = SchemaDesugarer.desugar(parser.parseSchemaDocument(), Set.of());
        Map<String, TsonSchema.EntryFacts> facts = new LinkedHashMap<>();
        Map<String, Top> entries = new Bootstrap(document, parser.schemaPositions(), facts).resolve();
        return new TsonSchema(id(document), document.meta(), document.imports(), AnnotatedMap.of(entries), true,
                Map.of(), facts);
    }

    /**
     * The entries {@link #getMetaKernelSchema()} builds by hand, by the names the desugared kernel gives them -- what
     * {@code MetaKernelBootstrapResolverTest} holds against the measured wait cycle.
     */
    static Set<String> preLoaded() {
        SchemaDocument document = SchemaDesugarer.desugar(new TsonSchemaParser(
                TsonBundledSchemas.fetch(TsonBundledSchemas.META_KERNEL_ID)).parseSchemaDocument(), Set.of());
        Set<String> names = new LinkedHashSet<>();
        Set<String> lifted = new LinkedHashSet<>();
        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            if (isPreLoaded(declaration, lifted)) {
                names.add(declaration.name());
            }
        }
        return names;
    }

    private static String id(SchemaDocument document) {
        return document.id().orElseThrow(() -> new IllegalStateException(
                "meta-kernel.tn has no !!id -- this should never happen for the real, bundled fixture"));
    }

    /** Whether {@code declaration} is read: a closed instance or an atom refinement, as opposed to resolved. */
    private static boolean isRead(SchemaMap.Declaration declaration) {
        return declaration.typeDef() instanceof Instance instance ? instance.typeParams().isEmpty()
                : declaration.typeDef() instanceof AtomRefinement;
    }

    /**
     * Whether {@code declaration} is one of the pre-loaded set's, {@code lifted} collecting the lifted arrays already
     * found to be: a lifted array's element is declared before it, its lift being depth first.
     */
    private static boolean isPreLoaded(SchemaMap.Declaration declaration, Set<String> lifted) {
        if (!isRead(declaration)) {
            return false;
        }
        if (PRE_LOADED.contains(declaration.name())) {
            return true;
        }
        if (declaration.typeDef() instanceof Instance instance && instance.target().equals("array")) {
            String element = bindingField(instance.value(), "element_type");
            if (PRE_LOADED_ELEMENTS.contains(element) || lifted.contains(element)) {
                lifted.add(declaration.name());
                return true;
            }
        }
        return false;
    }

    /**
     * One bootstrap: the kernel's declarations, made ready on demand. The {@link DefinitionGetter} every {@link
     * DefinitionResolver} lookup goes through, as {@link SchemaResolver}'s on-demand getter is, so an entry resolves
     * the first time anything asks for it.
     */
    private static final class Bootstrap implements DefinitionGetter {

        private final String id;
        private final SchemaDocument document;
        private final Map<String, SchemaMap.Declaration> declarations = new LinkedHashMap<>();
        private final Set<String> preLoaded = new LinkedHashSet<>();
        private final SchemaPositions positions;
        private final Map<String, Top> entries = new LinkedHashMap<>();
        private final Map<String, TsonSchema.EntryFacts> facts;

        /** The entries being made ready, innermost last -- an entry arriving twice waits on itself. */
        private final SequencedSet<String> resolving = new LinkedHashSet<>();

        private final DefinitionResolver resolver;
        private final DataBindContext context = SchemaMetaNameBinder.defaultContext();

        /** The readers of the entries ready when it was compiled; {@code null} until a read first needs one. */
        private TsonCompiledSchema compiled;

        Bootstrap(SchemaDocument document, SchemaPositions positions, Map<String, TsonSchema.EntryFacts> facts) {
            this.id = id(document);
            this.document = document;
            this.positions = positions;
            this.facts = facts;
            for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
                declarations.put(declaration.name(), declaration);
                if (isPreLoaded(declaration, preLoaded)) {
                    preLoaded.add(declaration.name());
                }
            }
            // The kernel is read over itself: the entries a construction's head resolves in are the ones being built,
            // every name it holds is the kernel's own, and a head declared beside its instance is read in wait order.
            this.resolver = new DefinitionResolver(this::read, null, this, this, null, positions, facts,
                    name -> true, name -> true, name -> false);
        }

        /** Every entry, ready, in the order the kernel declares them. */
        Map<String, Top> resolve() {
            // What no read produces first -- the pre-loaded set (rule 4: ready before any declaration is read) and the
            // declarations resolved from their grammar -- so the first compile already holds every head a read
            // applies. The reads then follow their own waits, whichever declaration the loop reaches first.
            for (SchemaMap.Declaration declaration : declarations.values()) {
                if (!isRead(declaration) || preLoaded.contains(declaration.name())) {
                    definition(declaration.name());
                }
            }
            for (String name : declarations.keySet()) {
                definition(name);
            }
            stampParameters();
            Map<String, Top> ordered = new LinkedHashMap<>();
            declarations.keySet().forEach(name -> ordered.put(name, entries.get(name)));
            return ordered;
        }

        @Override
        public Top definition(String name) {
            Top ready = entries.get(name);
            if (ready != null) {
                return ready;
            }
            SchemaMap.Declaration declaration = declarations.get(name);
            if (declaration == null) {
                return null; // not the kernel's: a reference the linker judges
            }
            if (!resolving.add(name)) {
                throw new IllegalStateException("meta-kernel: '" + name + "' waits on itself ("
                        + String.join(" -> ", resolving) + " -> " + name + "), a wait cycle outside the pre-loaded "
                        + "set -- the kernel's one cycle is the set this bootstrap builds by hand (rule 4)");
            }
            try {
                Optional<io.ltr8.tson.base.SourcePosition> position = positions.of(declaration);
                Top resolved = preLoaded.contains(name) ? handBuilt(declaration, position)
                        : resolver.resolve(declaration, position);
                entries.put(name, resolved);
                return resolved;
            } finally {
                resolving.remove(name);
            }
        }

        /** A pre-loaded entry, with the facts ordinary resolution would record for it. */
        private Top handBuilt(SchemaMap.Declaration declaration,
                              Optional<io.ltr8.tson.base.SourcePosition> position) {
            String name = declaration.name();
            if (declaration.typeDef() instanceof AtomRefinement refinement) {
                Top source = definition(refinement.target());
                facts.put(name, facts.getOrDefault(refinement.target(), TsonSchema.EntryFacts.NONE)
                        .withPosition(position));
                return refinedBody(name, refinement, source).withSupertypes(List.of(refinement.target()));
            }
            Instance instance = (Instance) declaration.typeDef();
            facts.put(name, TsonSchema.EntryFacts.constructedBy(instance.target()).withPosition(position));
            return instanceBody(name, instance);
        }

        /**
         * The {@link DefinitionMetaReader}: {@code value} read against constructor {@code type}'s reader, compiled
         * from the entries ready now. A read that reaches an entry not yet ready makes it ready and starts over
         * against a compile that holds it.
         */
        private Top read(String type, DataValue value) {
            while (true) {
                TsonCompiledSchema readers = readersFor(type);
                try {
                    return (Top) readers.get(type).read(TsonReadContext.schemaDocument(
                            new ListEventSource(DataValueEvents.of(value)), IdentifierPolicy.none(),
                            (slot, slotType, reference, cell) -> { }, false));
                } catch (PendingTypeReader.NotReady wait) {
                    if (entries.containsKey(wait.entry()) && readers.schema().entries().containsKey(wait.entry())) {
                        throw new IllegalStateException("meta-kernel: '" + wait.entry() + "' is ready and "
                                + "compiled, and its reader still reports it pending", wait);
                    }
                    definition(wait.entry());
                    compiled = null;
                }
            }
        }

        /** The readers of every entry ready now, compiled again only where {@code type} has none yet. */
        private TsonCompiledSchema readersFor(String type) {
            if (compiled == null || !compiled.schema().entries().containsKey(type)) {
                Set<String> pending = new LinkedHashSet<>(declarations.keySet());
                pending.removeAll(entries.keySet());
                TsonSchema ready = new TsonSchema(id, document.meta(), document.imports(),
                        AnnotatedMap.of(new LinkedHashMap<>(entries)), true, Map.of(), new LinkedHashMap<>(facts));
                compiled = TsonSchemaCompiler.compileBootstrap(TsonSchemaLinker.linkBootstrap(ready, pending),
                        context, pending);
            }
            return compiled;
        }

        /**
         * §5.10's parameter types, derived once every entry is ready, as {@link SchemaResolver} derives a schema's
         * -- a held body names its parameters and nothing about what each is read as.
         */
        private void stampParameters() {
            Map<String, Map<String, TypeRef>> written = new LinkedHashMap<>();
            declarations.forEach((name, declaration) -> {
                Map<String, TypeRef> types = new LinkedHashMap<>();
                declaration.parameterTypes().forEach((parameter, ref) ->
                        types.put(parameter, resolver.parameterType(parameter, ref)));
                if (!types.isEmpty()) {
                    written.put(name, types);
                }
            });
            Map<String, Map<String, TemplateParam>> parameters = ParameterTypes.inferAll(entries,
                    declarations.keySet(), written, entries::get, (name, error) -> {
                        throw error;
                    });
            entries.replaceAll((name, definition) -> parameters.containsKey(name)
                    ? OpenEntry.withParameters(definition, parameters.get(name)) : definition);
        }
    }

    /**
     * The hand-written merge for the pre-loaded set's one atom refinement, {@code non_negative_integer}, bounded as
     * {@link #instanceBody} is: it covers the integer family's {@code min} and {@code max} and refuses the rest by
     * name rather than pretending to be general. Ordinary resolution merges through a {@code DataClassObjectWriter}
     * round trip and the governing meta's compiled reader ({@code DefinitionResolver.resolveAtomRefinement}), which
     * is the reader being produced.
     */
    private static Type refinedBody(String name, AtomRefinement refinement, Top target) {
        if (!(target instanceof IntegerType source)) {
            throw new IllegalStateException("'" + name + "' refines '" + refinement.target() + "', whose body is a "
                    + target.getClass().getSimpleName() + "; the bootstrap merges a pre-loaded refinement of the "
                    + "integer family only");
        }
        Optional<BigInteger> min = source.min();
        Optional<BigInteger> max = source.max();
        for (RecordValue.Field binding : bindingFields(name, refinement)) {
            BigInteger value = new BigInteger(bindingText(name, binding));
            switch (binding.name()) {
                case "min" -> min = Optional.of(value);
                case "max" -> max = Optional.of(value);
                default -> throw new IllegalStateException("'" + name + "' refines '" + refinement.target()
                        + "' with '" + binding.name() + "'; the bootstrap merges min and max only");
            }
        }
        return new IntegerType(source.size(), min, source.exclusiveMin(), max, source.exclusiveMax(),
                source.multipleOf(), source.members());
    }

    private static List<RecordValue.Field> bindingFields(String name, AtomRefinement refinement) {
        if (!(refinement.bindings().coreValue() instanceof RecordValue record)) {
            throw new IllegalStateException("'" + name + "': a refinement's bindings are a record");
        }
        return record.fields();
    }

    private static String bindingText(String name, RecordValue.Field binding) {
        if (!(binding.value().coreValue() instanceof TokenValue token)) {
            throw new IllegalStateException("'" + name + "': '" + binding.name() + "' takes a token in a pre-loaded "
                    + "refinement");
        }
        return token.text();
    }

    /**
     * The hand-written construction of a pre-loaded instance (see the class Javadoc). The pre-loaded set applies
     * its constructors in four shapes, each checked rather than assumed: a bare {@code {}} for {@code value_type},
     * {@code integer_type} and {@code text_type}, each the target's unconstrained constant; {@code identifier}'s
     * profile, checked against the constant the lexer and resolver already hold; a bare token array for {@code
     * enum}; and a record of tokens for {@code kind_type} and for the {@code array}/{@code set_type} binding records
     * {@link SchemaDesugarer} lifts.
     *
     * <p>Package-private so {@code MetaKernelBootstrapResolverTest} can reach the refusals the real kernel never
     * does.
     */
    static Top instanceBody(String name, Instance instance) {
        return switch (instance.target()) {
            case "value_type" -> {
                requireEmptyBody(instance);
                yield new ValueType();
            }
            case "identifier_type" -> {
                requireIdentifierProfile(instance);
                yield IdentifierType.IDENTIFIER;
            }
            case "integer_type" -> {
                requireEmptyBody(instance);
                yield IntegerType.UNCONSTRAINED;
            }
            case "text_type" -> {
                requireEmptyBody(instance);
                yield TextType.UNCONSTRAINED;
            }
            case "enum" -> toEnumBody(instance.value());
            // `!kind_type { of: type }`: `of` is the one authored field, and the members are derived when the kernel
            // links, as every kind's are.
            case "kind_type" -> new KindBody(bindingField(instance.value(), "of"));
            // array and set differ only in the defaults set tightens (§5.7): ordered/duplicating vs unordered/unique.
            case "array" -> toArrayBody(instance.value(), false);
            case "set_type" -> toArrayBody(instance.value(), true);
            default -> throw new IllegalStateException("'" + name + "' applies '" + instance.target()
                    + "', which no pre-loaded instance applies -- an instance outside the pre-loaded set is read, "
                    + "not built by hand");
        };
    }

    /** A target whose body is a hand-picked constant is instantiated as a bare {@code {}}, checked. */
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
                || !(record.fields().getFirst().value().coreValue() instanceof TokenValue token)
                || !token.text().equals("-")) {
            throw new IllegalStateException("expected { continue_add: \"-\" } for !identifier_type, found "
                    + instance.value().coreValue());
        }
    }

    /**
     * {@code !array { element_type: T  min_items?: N  max_items?: M }}, or the same for {@code !set_type}, as the
     * body it denotes -- every fact the binding record states, so a hand-built body is the one the ordinary path
     * would read.
     */
    private static ArrayBody toArrayBody(DataValue value, boolean set) {
        TypeRef element = TypeRef.of(bindingField(value, "element_type"));
        return new ArrayBody(element, false, !set, set, optionalCount(value, "min_items"),
                optionalCount(value, "max_items"));
    }

    /** An optional count field of a desugared instance's binding record, a bare integer token where written. */
    private static Optional<BigInteger> optionalCount(DataValue value, String name) {
        return fieldToken(value, name).map(BigInteger::new);
    }

    /** One field of a desugared instance's binding record -- always a bare token naming a type. */
    private static String bindingField(DataValue value, String name) {
        return fieldToken(value, name).orElseThrow(() -> new IllegalStateException("no '" + name + "' in "
                + value.coreValue()));
    }

    private static Optional<String> fieldToken(DataValue value, String name) {
        if (!(value.coreValue() instanceof RecordValue record)) {
            throw new IllegalStateException("expected a binding record, found " + value.coreValue());
        }
        for (RecordValue.Field field : record.fields()) {
            if (field.name().equals(name) && field.value().coreValue() instanceof TokenValue token) {
                return Optional.of(token.text());
            }
        }
        return Optional.empty();
    }

    /**
     * {@code !enum [true false]}'s value is a bare array (§5.6's positional form for a
     * single-field constructor), not {@code { members: [...] } }.
     */
    private static EnumBody toEnumBody(DataValue value) {
        if (!(value.coreValue() instanceof ArrayValue array)) {
            throw new IllegalStateException("expected an array for !enum, found " + value.coreValue());
        }
        List<String> members = new ArrayList<>();
        for (DataValue element : array.elements()) {
            if (!(element.coreValue() instanceof TokenValue token)) {
                throw new IllegalStateException("expected a token enum member, found " + element.coreValue());
            }
            members.add(token.text());
        }
        return new EnumBody(members);
    }
}
