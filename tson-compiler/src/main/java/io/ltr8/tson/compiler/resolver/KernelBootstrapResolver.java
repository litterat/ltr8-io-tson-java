package io.ltr8.tson.compiler.resolver;

import io.ltr8.annotation.AnnotatedMap;
import io.ltr8.tson.base.policy.IdentifierPolicy;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.compiler.SchemaLocation;
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
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.compiler.reader.PendingTypeReader;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.compiler.stream.ListEventSource;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonSchema;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.VoidType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.numeric.IntegerSize;
import io.ltr8.tson.schema.numeric.IntegerType;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.unicode.TextType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.tson.schema.meta.TypeRef;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the kernel's own source text into the {@link TsonSchema} that governs its ordinary resolution: the
 * kernel is never resolved from a document but pre-loaded by special resolution ([TSON-SCHEMA] §3.4), its header an
 * {@code !!id} alone. Every constructor the kernel applies is declared in the kernel, so no compiled vocabulary
 * exists to read its instances through until this class has produced one.
 *
 * <p><b>The order is [TSON-SCHEMA] §3.4.1's.</b> Reading a value against a type waits on that type being
 * ready, and on each type a part of the value is read against as the read reaches it; a reference and a held value
 * (a field's default or pin) order nothing. So:
 *
 * <ul>
 *   <li><b>The pre-loaded set is hand-built</b> ({@link #instanceBody}, {@link #refinedBody}): the instances and the
 *       one refinement on the kernel's one wait cycle, the cycle holding {@code record} -- {@link #PRE_LOADED},
 *       built before anything is read. Rule 4 makes the set a measurement of the kernel, and {@code
 *       KernelBootstrapResolverTest} holds it to the measured cycle, so a kernel edit that moves the cycle fails
 *       there rather than here.</li>
 *   <li><b>A declaration no read produces is resolved from its grammar</b> by {@link DefinitionResolver}, as {@link
 *       SchemaResolver} resolves a record: a composition, a refinement of a record, an alias, a template. A record
 *       holding a type written in place reads it, through its constructor's reader, and waits on what that read
 *       reaches; {@code meta.type_inline}, the wrapper a type slot's choice recovers a brace to, is made ready with
 *       the pre-loaded set, so the first such read classifies both of the choice's variants.</li>
 *   <li><b>Every other instance and atom refinement is read through the ordinary path</b>, {@link
 *       DefinitionResolver}'s, whose {@link ConstructionReader} reads through readers compiled from the entries
 *       ready so far ({@link TsonSchemaLinker#linkReady}, {@link TsonSchemaCompiler#compileReady}). An entry not
 *       yet ready compiles to a {@link PendingTypeReader}: a read that reaches one stops ({@link
 *       PendingTypeReader.NotReady}), the entry it names is made ready on demand, the ready entries are compiled
 *       again, and the read starts over. A held value reached through one is bound as written, since it orders
 *       nothing -- which is how {@code iri} is read against {@code iri_type}, whose {@code spec} pin is typed by
 *       {@code iri} itself.</li>
 * </ul>
 *
 * <p>That is {@link WaitOrder}, the algorithm every schema's resolution runs; the bootstrap differs only in what is
 * ready before it starts. The order is computed, never the order entries are written in: an entry is made ready the
 * first time anything asks for it, and an entry that waits on itself is refused, naming the cycle -- a wait cycle
 * outside the pre-loaded set, which only a kernel edit can introduce. Everything closes here:
 * every entry is ready before {@link #kernelSchema()} returns, and its output is the kernel's resolved form,
 * entry for entry what the kernel's ordinary resolution against it produces.
 *
 * <p>The output is resolved and not linked: a caller links it through {@link TsonSchemaLinker#linkBootstrap} --
 * never {@code TsonSchemaRegistry#register}, which refuses a bootstrap schema -- and compiles it. {@link
 * SchemaDesugarer} runs first, as for every schema: the desugar table is fixed by the sugar forms (§5.3), so it
 * consults no imported vocabulary. The class is locked down to one public method taking no arguments: it bootstraps
 * the bundled kernel, meta.tn ({@link TsonBundledSchemas#META_ID}), fetched from the classpath so the
 * bootstrap works from a built jar, and nothing else.
 */
public final class KernelBootstrapResolver {

    /**
     * The kernel's named instances and refinements on its one wait cycle, built by hand: the {@code boolean} and
     * {@code meta.field_role} enums, {@code enum}'s member set, the {@code integer} and {@code text} atoms, {@code
     * meta.type_ref}, the reference a type slot's name is read by, {@code field_map}, the map type a record's fields
     * are a value of, the refinement {@code numeric.count}, and the choice a type slot is typed by, {@code
     * meta.type_expr}: a choice's own {@code variants} is a list of {@code meta.type_expr}, so reading {@code
     * type_expr}'s declaration reaches {@code type_expr}.
     */
    private static final Set<String> PRE_LOADED = Set.of("boolean", "meta.field_role", "meta.enum_set",
            "integer", "numeric.count", "text", "meta.type_ref", "meta.field_map",
            "meta.type_expr", "meta.record_extension_type", "void");

    /** The kernel's wrapper for a type written in place, {@code { of: meta.type }}. */
    private static final String TYPE_INLINE = KernelNames.of("type_inline");

    private KernelBootstrapResolver() {
    }

    /**
     * Parses and resolves the kernel's own bundled source text (see class Javadoc). The one place in this codebase
     * that constructs a {@link TsonSchema} with {@code bootstrap: true} -- {@link TsonSchema#bootstrap()} says why
     * that matters: {@code TsonSchemaRegistry.register} gates on it, so the kernel's own identity can only be
     * registered by something that came from here.
     */
    public static TsonSchema kernelSchema() {
        TsonSchemaParser parser = new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID));
        return resolve(parser.parseSchemaDocument(), parser.schemaPositions());
    }

    /**
     * {@link #kernelSchema()} over {@code parsed}, the kernel's parsed source: package-private so {@code
     * KernelBootstrapResolverTest} can hand it the kernel's declarations in another order, and see the order
     * they are written in consulted for nothing.
     */
    static TsonSchema resolve(SchemaDocument parsed, SchemaPositions positions) {
        SchemaDocument document = SchemaDesugarer.desugar(parsed);
        Map<String, TsonSchema.EntryFacts> facts = new LinkedHashMap<>();
        Map<String, Top> entries = new Bootstrap(document, positions, facts).resolve();
        return new TsonSchema(id(document), document.imports(), AnnotatedMap.of(entries), true,
                Map.of(), facts);
    }

    /**
     * The entries {@link #kernelSchema()} builds by hand -- what {@code KernelBootstrapResolverTest} holds against the
     * measured wait cycle.
     */
    static Set<String> preLoaded() {
        SchemaDocument document = SchemaDesugarer.desugar(
                new TsonSchemaParser(TsonBundledSchemas.fetch(TsonBundledSchemas.META_ID)).parseSchemaDocument());
        Set<String> names = new LinkedHashSet<>();
        for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
            if (isPreLoaded(declaration)) {
                names.add(declaration.name());
            }
        }
        return names;
    }

    private static String id(SchemaDocument document) {
        return document.id().orElseThrow(() -> new IllegalStateException(
                "meta.tn has no !!id -- this should never happen for the real, bundled fixture"));
    }


    /** Whether {@code declaration} is one of the pre-loaded set's: one that is read, and named in {@link #PRE_LOADED}. */
    private static boolean isPreLoaded(SchemaMap.Declaration declaration) {
        return WaitOrder.reads(declaration) && PRE_LOADED.contains(declaration.name());
    }

    /**
     * One bootstrap: the kernel's declarations, made ready in wait order ({@link WaitOrder}), the one algorithm
     * every schema's resolution runs, over what the kernel has ready before it starts -- its pre-loaded set, built by
     * hand here ({@link Readiness#resolve}).
     */
    private static final class Bootstrap implements WaitOrder.Readiness {

        private final String id;
        private final SchemaDocument document;
        private final Map<String, SchemaMap.Declaration> declarations = new LinkedHashMap<>();
        private final Set<String> preLoaded = new LinkedHashSet<>();
        private final SchemaPositions positions;
        private final Map<String, Top> entries = new LinkedHashMap<>();
        private final Map<String, TsonSchema.EntryFacts> facts;
        private final WaitOrder order;
        private final DefinitionResolver resolver;
        private final DataBindContext context = SchemaModelNameBinder.defaultContext();

        Bootstrap(SchemaDocument document, SchemaPositions positions,
                  Map<String, TsonSchema.EntryFacts> facts) {
            this.id = id(document);
            this.document = document;
            this.positions = positions;
            this.facts = facts;
            for (SchemaMap.Declaration declaration : document.body().declarations().values()) {
                declarations.put(declaration.name(), declaration);
                if (isPreLoaded(declaration)) {
                    preLoaded.add(declaration.name());
                }
            }
            // The kernel is the library's own, so it is resolved under the default limits whatever a caller set.
            this.order = new WaitOrder(CanonicalIdentity.canonicalize(id), entries, declarations,
                    LimitsPolicy.defaults().maxWaitDepth(), this::compile);
            order.readyWith(this);
            // The kernel is read over itself: the entries a construction's head resolves in are the ones being built,
            // every name it holds is the kernel's own, and a head declared beside its instance is read in wait order.
            this.resolver = new DefinitionResolver(this::read, null, order, order, null, positions, facts,
                    name -> true, name -> true);
        }

        /** Every entry, ready, in the order the kernel declares them. */
        Map<String, Top> resolve() {
            // The pre-loaded set first, built by hand before anything is read (rule 4), and with it the wrapper a type
            // written in place takes: a record read from its grammar, which reads nothing, and the variant of
            // `meta.type_expr` an untagged value at a type slot is told from a name by. A record's field written in
            // place is read against that choice, whose recovery classifies both its variants (§5.4), so both are
            // ready before the first such read.
            preLoaded.forEach(order::definition);
            order.definition(TYPE_INLINE);
            order.readyAll(preLoaded::contains);
            resolver.judgeDeferred((name, error) -> {
                throw error;
            });
            completeChains();
            checkParameters();
            Map<String, Top> ordered = new LinkedHashMap<>();
            declarations.keySet().forEach(name -> ordered.put(name, entries.get(name)));
            return ordered;
        }

        @Override
        public Top resolve(String name, SchemaMap.Declaration declaration) {
            Optional<io.ltr8.tson.base.SourcePosition> position = positions.of(declaration);
            return preLoaded.contains(name) ? handBuilt(declaration, position) : resolver.resolve(declaration, position);
        }

        /** The kernel is the library's own: a declaration that fails is a broken kernel, never a stand-in. */
        @Override
        public Top failed(String name, SchemaMap.Declaration declaration, RuntimeException error) {
            throw error;
        }

        /** A pre-loaded entry, with the facts ordinary resolution would record for it. */
        private Top handBuilt(SchemaMap.Declaration declaration,
                              Optional<io.ltr8.tson.base.SourcePosition> position) {
            String name = declaration.name();
            if (declaration.typeDef() instanceof AtomRefinement refinement) {
                Top source = order.definition(refinement.target());
                facts.put(name, facts.getOrDefault(refinement.target(), TsonSchema.EntryFacts.NONE)
                        .withPosition(position));
                List<String> supertypes = new ArrayList<>(List.of(refinement.target()));
                if (source instanceof Type sourceType) {
                    supertypes.addAll(sourceType.chain().supertypes());
                }
                return refinedBody(name, refinement, source).withSupertypes(List.copyOf(supertypes));
            }
            Instance instance = (Instance) declaration.typeDef();
            facts.put(name, TsonSchema.EntryFacts.constructedBy(instance.target()).withPosition(position));
            return instanceBody(name, instance);
        }

        /**
         * The {@link ConstructionReader}: {@code value} read against constructor {@code type}'s reader, compiled
         * from the entries ready now; a read that reaches an entry not yet ready makes it ready and starts over.
         */
        private Top read(String type, DataValue value) {
            return order.read(type, readers -> (Top) readers.get(type).read(TsonReadContext.schemaDocument(
                    new ListEventSource(DataValueEvents.of(value)), IdentifierPolicy.none(),
                    new TsonReadContext.HeldByName() {

                        @Override
                        public void held(List<String> slot, TsonSchema.SlotType slotType,
                                         TypeRef reference, io.ltr8.tson.schema.meta.Ref<?> cell) {
                        }

                        @Override
                        public void reached(SchemaLocation declaration) {
                            order.reached(declaration);
                        }
                    }, false)));
        }

        /** The readers of the kernel entries ready now, in object-binding mode under the schema model's binder. */
        private TsonCompiledSchema compile(Set<String> pending, boolean tree) {
            TsonSchema ready = new TsonSchema(id, document.imports(), AnnotatedMap.of(new LinkedHashMap<>(entries)),
                    true, Map.of(), new LinkedHashMap<>(facts));
            return TsonSchemaCompiler.compileReady(TsonSchemaLinker.linkReady(ready, null, pending),
                    ValueReaderFactoryRegistry.bind(context), pending);
        }

        /**
         * Every chain transitively closed, as {@link SchemaResolver} closes a schema's once every entry is ready: an
         * entry IS-A every entry its supertypes are. The kernel applies no template in a chain, so the
         * closure is over names alone, to a fixed point.
         */
        private void completeChains() {
            for (int round = 0; round <= entries.size(); round++) {
                boolean changed = false;
                for (Map.Entry<String, Top> entry : entries.entrySet()) {
                    if (!(entry.getValue() instanceof Type type)) {
                        continue;
                    }
                    Set<String> chain = new LinkedHashSet<>(type.supertypes());
                    for (String member : List.copyOf(chain)) {
                        chain.addAll(Ancestry.closedNames(entries.get(member)));
                    }
                    if (chain.size() != type.supertypes().size()) {
                        entry.setValue(TypeChain.withChain(type, type.chain().withSupertypes(List.copyOf(chain))));
                        changed = true;
                    }
                }
                if (!changed) {
                    return;
                }
            }
        }

        /**
         * §5.10's parameters, checked once every entry is ready, as {@link SchemaResolver} checks a schema's: each
         * written bound. A failure is a broken kernel; the kernel declares no open record, so nothing is checked.
         */
        private void checkParameters() {
            ParameterTypes.check(entries, declarations.keySet(), entries::get,
                    (name, error) -> {
                        throw error;
                    });
        }
    }

    /**
     * The hand-written merge for the pre-loaded set's atom refinement, bounded as {@link #instanceBody} is: for
     * {@code numeric.count} the integer family's {@code min}, {@code max} and {@code size}, and the rest refused by
     * name rather than pretending to be general. Ordinary resolution merges through a {@code DataClassObjectWriter}
     * round trip and the compiled reader of the vocabulary declaring the constructor ({@code
     * DefinitionResolver.resolveAtomRefinement}), which is the reader being produced.
     */
    private static Type refinedBody(String name, AtomRefinement refinement, Top target) {
        if (!(target instanceof IntegerType source)) {
            throw new IllegalStateException("'" + name + "' refines '" + refinement.target() + "', whose body is a "
                    + target.getClass().getSimpleName() + "; the bootstrap merges a pre-loaded refinement of the "
                    + "integer family only");
        }
        Optional<BigInteger> min = source.min();
        Optional<BigInteger> max = source.max();
        Optional<IntegerSize> size = source.size();
        for (RecordValue.Field binding : bindingFields(name, refinement)) {
            switch (binding.name()) {
                case "min" -> min = Optional.of(new BigInteger(bindingText(name, binding)));
                case "max" -> max = Optional.of(new BigInteger(bindingText(name, binding)));
                case "size" -> size = Optional.of(integerSize(name, binding));
                default -> throw new IllegalStateException("'" + name + "' refines '" + refinement.target()
                        + "' with '" + binding.name() + "'; the bootstrap merges min, max and size only");
            }
        }
        return new IntegerType(size, min, source.exclusiveMin(), max, source.exclusiveMax(),
                source.multipleOf(), source.members());
    }

    /** A {@code size} binding, {@code { bits: N  signed: B }}: a record of two tokens, as {@code integer_size} reads. */
    private static IntegerSize integerSize(String name, RecordValue.Field binding) {
        if (!(binding.value().coreValue() instanceof RecordValue record)) {
            throw new IllegalStateException("'" + name + "': 'size' takes a record in a pre-loaded refinement");
        }
        BigInteger bits = null;
        Boolean signed = null;
        for (RecordValue.Field field : record.fields()) {
            switch (field.name()) {
                case "bits" -> bits = new BigInteger(bindingText(name, field));
                case "signed" -> signed = Boolean.valueOf(bindingText(name, field));
                default -> throw new IllegalStateException("'" + name + "': 'size' has no field '" + field.name()
                        + "'");
            }
        }
        if (bits == null || signed == null) {
            throw new IllegalStateException("'" + name + "': 'size' states both 'bits' and 'signed'");
        }
        return new IntegerSize(bits, signed);
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
     * its constructors in four shapes, each checked rather than assumed: a bare {@code {}} for
     * {@code integer_type} and {@code text_type}, each the target's unconstrained constant; a bare token array for
     * {@code enum}; a record of tokens for {@code reference_type} and for {@code map}; and a record holding a token
     * array for {@code choice}, whose {@code variants} are names.
     *
     * <p>Package-private so {@code KernelBootstrapResolverTest} can reach the refusals the real kernel never
     * does.
     */
    static Top instanceBody(String name, Instance instance) {
        return switch (instance.target()) {
            case "numeric.integer_type" -> {
                requireEmptyBody(instance);
                yield IntegerType.UNCONSTRAINED;
            }
            case "unicode.text_type" -> {
                requireEmptyBody(instance);
                yield TextType.UNCONSTRAINED;
            }
            case "enum" -> toEnumBody(instance.value());
            // `!meta.reference_type { of: type }`: `of` is the authored field, and the index is derived when the
            // kernel links, as every reference's is.
            case "meta.reference_type" -> new ReferenceBody(TypeRef.of(bindingField(instance.value(), "of")));
            // `field_map => !meta.map { key_type: text  value_type: meta.record_field  ordered: true }`.
            case "meta.map" -> new MapBody(TypeRef.of(bindingField(instance.value(), "key_type")),
                    TypeRef.of(bindingField(instance.value(), "value_type")),
                    fieldToken(instance.value(), "voidable").map(Boolean::parseBoolean).orElse(false),
                    fieldToken(instance.value(), "ordered").map(Boolean::parseBoolean).orElse(false),
                    optionalCount(instance.value(), "min_items"), optionalCount(instance.value(), "max_items"));
            // `enum_set => !meta.set_type { element_type: text  min_items: 1 }`: an array with the facets set pins
            // (§5.3), unordered and unique.
            case "meta.set_type" -> toSetBody(instance.value());
            // `void => !meta.void_type {}`: the one value it admits is `_`, and it has no facets.
            case "meta.void_type" -> {
                requireEmptyBody(instance);
                yield new VoidType(TypeChain.EMPTY);
            }
            // `type_expr => @meta.disjoint ( meta.type_ref | meta.type_inline )`, each variant a name. A slot of the
            // records on the cycle is read against it before `meta.type_inline` is ready, so it states the fact its
            // `@meta.disjoint` asserts -- a name string-class, the wrapper brace-class -- and the whole kernel's
            // link derives it again and holds the assertion to it.
            case "meta.choice" -> new ChoiceBody(nameArray(name, instance.value(), "variants").stream()
                    .map(TypeRef::of).toList(), Optional.of(true));
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
     * {@code !meta.set_type { element_type: T  min_items?: N  max_items?: M }} as the body it denotes -- every fact the
     * binding record states, so a hand-built body is the one the ordinary path would read.
     */
    private static ArrayBody toSetBody(DataValue value) {
        TypeRef element = TypeRef.of(bindingField(value, "element_type"));
        return new ArrayBody(element, false, false, true, optionalCount(value, "min_items"),
                optionalCount(value, "max_items"));
    }

    /** A field of a binding record holding an array of bare tokens, each a name: a choice's {@code variants}. */
    private static List<String> nameArray(String name, DataValue value, String field) {
        if (!(value.coreValue() instanceof RecordValue record)) {
            throw new IllegalStateException("'" + name + "': expected a binding record, found " + value.coreValue());
        }
        for (RecordValue.Field written : record.fields()) {
            if (written.name().equals(field) && written.value().coreValue() instanceof ArrayValue array) {
                List<String> names = new ArrayList<>();
                for (DataValue element : array.elements()) {
                    if (!(element.coreValue() instanceof TokenValue token)) {
                        throw new IllegalStateException("'" + name + "': '" + field + "' takes bare names in a "
                                + "pre-loaded instance, found " + element.coreValue());
                    }
                    names.add(token.text());
                }
                return names;
            }
        }
        throw new IllegalStateException("'" + name + "': no '" + field + "' array in " + value.coreValue());
    }

    /** An optional count field of a desugared instance's binding record, a bare integer token where written. */
    private static Optional<Long> optionalCount(DataValue value, String name) {
        return fieldToken(value, name).map(Long::parseLong);
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
