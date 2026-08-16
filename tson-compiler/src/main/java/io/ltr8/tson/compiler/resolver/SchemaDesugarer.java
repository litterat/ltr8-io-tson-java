package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.ArrayContainerDef;
import io.ltr8.tson.compiler.ast.schema.ChoiceRef;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.ContainerDef;
import io.ltr8.tson.compiler.ast.schema.ContainerTypeDef;
import io.ltr8.tson.compiler.ast.schema.ElementType;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.InlineArrayRef;
import io.ltr8.tson.compiler.ast.schema.InlineTupleRef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RecordEntry;
import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.SizeSpec;
import io.ltr8.tson.compiler.ast.schema.StructuralDef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TupleContainerDef;
import io.ltr8.tson.compiler.ast.schema.TemplateInstance;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeRef;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Expands the schema sugar forms into named declarations before anything resolves them -- the {@code
 * desugar} step of parse -&gt; desugar -&gt; resolve -&gt; link.
 *
 * <p><b>Why a phase rather than work inside the resolver or linker.</b> [TSON-SCHEMA] §5.3/§5.6 describe
 * {@code [T]} and {@code head&lt;args&gt;} as <em>desugarings</em>, and §3.3.1 calls their targets "the
 * implicit desugar targets of the sugar forms". Doing that expansion once, on the AST, leaves {@code
 * DefinitionResolver} with a bare reference or {@code !C value} -- and it already handles the second
 * generically through the governing meta's compiled reader. The arrangement this replaces split the same
 * construct by position: a declaration-position application became a real body in the resolver, while a
 * field-position one was deferred to {@code TsonSchemaLinker}, which sits in a module that cannot reach that
 * generic machinery and so needed a hand-written assembler per constructor shape -- of which only {@code
 * array} and {@code set} were ever written.
 *
 * <p><b>An application becomes an instance.</b> {@code map&lt;text, integer&gt;} injects
 * {@code map_text_integer_<i>hash</i> => !map { key_type: text  value_type: integer } } and the use site
 * becomes a plain reference to that name. Argument-to-field routing comes from the governing meta itself:
 * the constructor's own {@code parameters()} zip positionally against the arguments, and each vocabulary
 * field names the parameter it takes its value from ({@code key_type: type_ref = K}). Nothing here assembles
 * a body -- {@code resolveInstance} binds the emitted instance through the meta's compiled reader, which is
 * exactly how every other {@code !C value} is already handled.
 *
 * <p><b>Structural sharing.</b> Every method returns its input unchanged when nothing beneath it changed, so
 * a document with no sugar comes back as the same object graph. Source positions live in identity-keyed side
 * tables ({@code TsonSchemaParser.declarationPositions()} is an {@code IdentityHashMap}), so a rebuilt node
 * silently loses its position; sharing confines that to the declarations that genuinely contain sugar.
 *
 * <p><b>What is deliberately left alone.</b> Three positions keep their heads intact, because a name there is
 * being <em>declared</em> or <em>composed</em>, not applied: a declaration's own body reference
 * ({@code ReferenceTypeDef}), a refinement source, and a composition supertype. And nothing inside a
 * <em>parameterized</em> declaration is expanded at all -- a template's body references its own type
 * parameters ({@code set => <T> ~array<T> ^ { ... } }), so expanding {@code array<T>} there would inject a
 * declaration referring to an unbound {@code T}.
 *
 * <p><b>Applying a locally declared template is rejected, not passed through.</b> §5.10 parameter
 * substitution is a separate, unimplemented feature, so {@code box<text>} is not something this phase can
 * rewrite -- and leaving it alone produced a schema that linked and compiled and then failed on the first
 * read that reached the field. See {@link #rejectIfTemplateApplication} for what is and is not covered.
 */
final class SchemaDesugarer {

    /** §5.6's desugar target for {@code (A | B)}, fixed by the sugar form rather than named by the author. */
    private static final String CHOICE = "choice";

    /** The governing meta's entries -- where a constructor's parameter list and vocabulary field names come from. */
    private final Map<String, TypeDefinition> metaEntries;

    /**
     * Names already in scope from {@code !!import}. An application that generates a name an import already
     * declares is <em>referenced</em> rather than redeclared: the name is derived from the application
     * itself, so an identical application in an imported schema has already produced the same type. Without
     * this, meta.tn's own {@code array<type_name>} would redeclare the one it imports from the meta-kernel
     * and be rejected as a local-vs-import collision.
     */
    private final Set<String> imported;

    /** Declarations synthesised for applications encountered during the walk, keyed by their generated name. */
    private final Map<String, SchemaMap.Declaration> injected = new LinkedHashMap<>();

    /** This document's own declarations, for {@link #rejectIfTemplateApplication} -- set before the walk starts. */
    private Map<String, SchemaMap.Declaration> local = Map.of();

    private SchemaDesugarer(Map<String, TypeDefinition> metaEntries, Set<String> imported) {
        this.metaEntries = metaEntries;
        this.imported = imported;
    }

    /**
     * The document with every expandable application hoisted into its own declaration, or the same instance
     * when there was nothing to expand.
     */
    static SchemaDocument desugar(SchemaDocument document, Map<String, TypeDefinition> metaEntries,
            Set<String> imported) {
        SchemaDesugarer pass = new SchemaDesugarer(metaEntries, imported);
        pass.local = document.body().declarations();
        SchemaMap body = pass.schemaMap(document.body());
        if (body == document.body() && pass.injected.isEmpty()) {
            return document;
        }
        Map<String, SchemaMap.Declaration> declarations = new LinkedHashMap<>(body.declarations());
        for (Map.Entry<String, SchemaMap.Declaration> entry : pass.injected.entrySet()) {
            if (declarations.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalStateException("desugared name '" + entry.getKey()
                        + "' collides with a declaration already in this schema");
            }
        }
        return new SchemaDocument(document.id(), document.meta(), document.imports(),
                new SchemaMap(body.annotations(), declarations));
    }

    private SchemaMap schemaMap(SchemaMap map) {
        Map<String, SchemaMap.Declaration> rewritten = null;
        for (Map.Entry<String, SchemaMap.Declaration> entry : map.declarations().entrySet()) {
            SchemaMap.Declaration declaration = declaration(entry.getValue());
            if (declaration != entry.getValue() && rewritten == null) {
                rewritten = new LinkedHashMap<>(map.declarations());
            }
            if (rewritten != null) {
                rewritten.put(entry.getKey(), declaration);
            }
        }
        return rewritten == null ? map : new SchemaMap(map.annotations(), rewritten);
    }

    private SchemaMap.Declaration declaration(SchemaMap.Declaration declaration) {
        TypeDef typeDef = typeDef(declaration.typeDef());
        return typeDef == declaration.typeDef() ? declaration
                : new SchemaMap.Declaration(declaration.nameAnnotations(), declaration.name(),
                        declaration.typeDefAnnotations(), typeDef);
    }

    /**
     * {@code AtomRefinement} and {@code Instance} are passed through: their target is a bare {@code String}
     * type name and their payload is a {@code DataValue}, so neither can carry a sugar form.
     *
     * <p>A declaration carrying type parameters is passed through whole -- see the class Javadoc on why a
     * template's body must not be expanded.
     */
    private TypeDef typeDef(TypeDef typeDef) {
        return switch (typeDef) {
            case StructuralTypeDef structural -> {
                if (!structural.typeParams().isEmpty()) {
                    yield structural;
                }
                StructuralDef body = structuralDef(structural.body());
                yield body == structural.body() ? structural
                        : new StructuralTypeDef(structural.typeParams(), structural.constructor(), body);
            }
            case ContainerTypeDef container -> {
                if (!container.typeParams().isEmpty()) {
                    yield container;
                }
                // A size-less declaration-level array IS a top-level constructor application (§5.6), so it
                // becomes the instance directly. The sized forms desugar to array_min/array_max/array_ranged,
                // which are templates rather than constructors, and stay on their existing path.
                Optional<TypeDef> instance = declarationLevelArray(container.container());
                if (instance.isPresent()) {
                    yield instance.get();
                }
                Optional<GenericRef> sized = sizedArrayApplication(container.container());
                if (sized.isPresent()) {
                    // The application this stands for is instantiated like any other (§8.2): array_ranged and
                    // its siblings are templates, so this yields a TemplateInstance headed at `array`.
                    GenericRef application = sized.get();
                    Optional<TypeDef> instantiation = instanceFor(application.name(), application.args());
                    if (instantiation.isPresent()) {
                        yield instantiation.get();
                    }
                    rejectIfTemplateApplication(application.name());
                    yield new ReferenceTypeDef(List.of(), application);
                }
                ContainerDef def = containerDef(container.container());
                yield def == container.container() ? container
                        : new ContainerTypeDef(container.typeParams(), def);
            }
            // A declaration's own body reference names what this declaration *is*; only its arguments are
            // expandable, so the head stays put and its own handling is unchanged.
            case ReferenceTypeDef reference -> {
                if (!reference.typeParams().isEmpty()) {
                    yield reference;
                }
                // §5.4: a declaration whose body is the choice sugar *is* that construction, so it becomes
                // the instance itself rather than a reference to an injected one -- which is what makes
                // `contact => (email | phone)` a SUM entry with a real ChoiceBody instead of a REFERENCE to
                // one. Same treatment declarationLevelArray gives `[T]` at declaration position.
                if (reference.ref() instanceof ChoiceRef choice) {
                    List<TypeRef> variants = mapShared(choice.variants(), this::typeRef);
                    Optional<TypeDef> instance = choiceInstance(variants);
                    if (instance.isPresent()) {
                        yield instance.get();
                    }
                    yield variants == choice.variants() ? reference
                            : new ReferenceTypeDef(reference.typeParams(), new ChoiceRef(variants));
                }
                TypeRef ref = argumentsOnly(reference.ref());
                // §5.6: a declaration whose body is a fully-bound application resolves as a construction, so
                // it becomes the instance itself rather than a reference to an injected one -- which is what
                // keeps `x => map<K, V>` a PRODUCT with a real body instead of a REFERENCE to one.
                if (ref instanceof GenericRef generic) {
                    Optional<TypeDef> instance = instanceFor(generic.name(), generic.args());
                    if (instance.isPresent()) {
                        yield instance.get();
                    }
                    rejectIfTemplateApplication(generic.name());
                }
                yield ref == reference.ref() ? reference : new ReferenceTypeDef(reference.typeParams(), ref);
            }
            default -> typeDef;
        };
    }

    private StructuralDef structuralDef(StructuralDef def) {
        return switch (def) {
            case RecordDef record -> recordDef(record);
            case RefinedDef refined -> {
                TypeRef target = argumentsOnly(refined.target()); // §5.7 refines a *named* source
                RecordDef body = recordDef(refined.body());
                yield target == refined.target() && body == refined.body() ? refined
                        : new RefinedDef(target, body);
            }
            case ConstructionDef construction -> {
                // §5.8 composes with named supertypes; only their arguments are expandable.
                List<TypeRef> supertypes = mapShared(construction.supertypes(), this::argumentsOnly);
                Optional<RecordDef> body = construction.body().map(this::recordDef);
                boolean bodyChanged = construction.body().isPresent()
                        && body.orElseThrow() != construction.body().orElseThrow();
                yield supertypes == construction.supertypes() && !bodyChanged ? construction
                        : new ConstructionDef(supertypes, body, construction.removal());
            }
        };
    }

    private RecordDef recordDef(RecordDef record) {
        List<RecordEntry> entries = mapShared(record.entries(), this::recordEntry);
        return entries == record.entries() ? record : new RecordDef(entries);
    }

    private RecordEntry recordEntry(RecordEntry entry) {
        return switch (entry) {
            case FieldDef field -> {
                if (field.type().isEmpty()) {
                    yield field; // modifier-only entry (a tightening body restating state, §5.7)
                }
                FieldDef.FieldType fieldType = field.type().orElseThrow();
                TypeRef ref = typeRef(fieldType.typeRef());
                yield ref == fieldType.typeRef() ? field
                        : new FieldDef(field.annotations(), field.name(),
                                Optional.of(new FieldDef.FieldType(ref, fieldType.optional())), field.modifier());
            }
            case GroupDef group -> {
                List<GroupDef.Member> members = mapShared(group.members(), this::groupMember);
                yield members == group.members() ? group
                        : new GroupDef(group.annotations(), members, group.optional());
            }
        };
    }

    private GroupDef.Member groupMember(GroupDef.Member member) {
        TypeRef ref = typeRef(member.typeRef());
        return ref == member.typeRef() ? member : new GroupDef.Member(member.annotations(), member.name(), ref);
    }

    private ContainerDef containerDef(ContainerDef def) {
        return switch (def) {
            case ArrayContainerDef array -> {
                ElementType element = elementType(array.elementType());
                yield element == array.elementType() ? array : new ArrayContainerDef(element, array.size());
            }
            case TupleContainerDef tuple -> {
                List<ElementType> elements = mapShared(tuple.elementTypes(), this::elementType);
                yield elements == tuple.elementTypes() ? tuple : new TupleContainerDef(elements);
            }
        };
    }

    private ElementType elementType(ElementType element) {
        return switch (element.expr()) {
            case ElementType.Expr.Plain plain -> {
                TypeRef ref = typeRef(plain.typeRef());
                yield ref == plain.typeRef() ? element
                        : new ElementType(new ElementType.Expr.Plain(ref), element.optional());
            }
            case ElementType.Expr.Nested nested -> {
                ContainerDef def = containerDef(nested.container());
                yield def == nested.container() ? element
                        : new ElementType(new ElementType.Expr.Nested(def), element.optional());
            }
        };
    }

    /**
     * A reference at a position where an application <em>is</em> expandable: expands children first, so a
     * nested application is already a plain name by the time the enclosing one is built ({@code
     * map<text, [integer]>} injects the inner array, then the outer map referring to it).
     */
    private TypeRef typeRef(TypeRef ref) {
        return switch (ref) {
            case SimpleRef simple -> simple;
            case InlineArrayRef array -> apply("array", List.of(new TypeArg.Ref(typeRef(array.elementType()))), ref);
            case GenericRef generic -> {
                List<TypeArg> args = mapShared(generic.args(), this::typeArg);
                yield apply(generic.name(), args,
                        args == generic.args() ? generic : new GenericRef(generic.name(), args));
            }
            case ChoiceRef choice -> {
                List<TypeRef> variants = mapShared(choice.variants(), this::typeRef);
                yield hoistChoice(variants,
                        variants == choice.variants() ? choice : new ChoiceRef(variants));
            }
            // Not yet expandable: DefinitionResolver has never handled a tuple at a type-ref position, so
            // there is no behaviour here to preserve or break -- it joins the arc once tuple desugaring is
            // written (the other half of §5.3's variadic pair; see choiceInstance).
            case InlineTupleRef tuple -> {
                List<TypeRef> elements = mapShared(tuple.elementTypes(), this::typeRef);
                yield elements == tuple.elementTypes() ? tuple : new InlineTupleRef(elements);
            }
        };
    }

    /**
     * A size-less declaration-level array as its own constructor application, or empty for anything this
     * does not build -- a tuple container, a sized array, an optional element, or a non-plain element.
     */
    private Optional<TypeDef> declarationLevelArray(ContainerDef def) {
        if (!(def instanceof ArrayContainerDef array) || array.size().isPresent()
                || array.elementType().optional()
                || !(array.elementType().expr() instanceof ElementType.Expr.Plain plain)) {
            return Optional.empty();
        }
        return instanceFor("array", List.of(new TypeArg.Ref(typeRef(plain.typeRef()))));
    }

    /**
     * §5.3's sized sugar as the template application it stands for: {@code [T; N..]} is {@code
     * array_min<T, N>}, {@code [T; ..M]} is {@code array_max<T, M>}, {@code [T; N..M]} is {@code
     * array_ranged<T, N, M>}, and an exact {@code [T; N]} is {@code array_ranged<T, N, N>}.
     *
     * <p>Purely syntactic, which is why it belongs here even though the targets are <em>templates</em>
     * rather than constructors: this phase rewrites the spelling, and what a template application then
     * resolves to (§5.10 substitution, unimplemented) is a separate question it does not answer. A bound is
     * carried through as the raw token it was parsed as -- it may name a value parameter rather than a
     * literal, which is why {@code SizeSpec} keeps them as text.
     *
     * <p><b>{@code [T; 0..]} is rejected</b> rather than desugared. §5.3 calls it vacuous and asks the
     * resolver to warn while desugaring it anyway; rejecting the spelling is {@code SPEC-FEEDBACK.md} #42's
     * position, and here the warning would be guarding more than a style nit -- §5.3's own sentence notes
     * that identity is application-structural (§8.2), so the form lands on an entry <em>distinct from</em>
     * {@code [T]} that means exactly the same thing. That is an identity trap, and the author's fix is the
     * one §5.3 itself names. Only a literal {@code 0} is caught: a bound naming a value parameter is not
     * concrete here, and a parameter that turns out to be zero is §8.2's materialisation-time question.
     */
    private Optional<GenericRef> sizedArrayApplication(ContainerDef def) {
        if (!(def instanceof ArrayContainerDef array) || array.size().isEmpty()
                || array.elementType().optional()
                || !(array.elementType().expr() instanceof ElementType.Expr.Plain plain)) {
            return Optional.empty();
        }
        TypeArg element = new TypeArg.Ref(typeRef(plain.typeRef()));
        return Optional.of(switch (array.size().get()) {
            case SizeSpec.Min min when min.lower().equals("0") -> {
                String shown = plain.typeRef() instanceof SimpleRef simple ? simple.name() : "T";
                throw new TsonSchemaValidationException("'[" + shown + "; 0..]' pins a floor of zero, which "
                        + "every array already satisfies -- write '[" + shown + "]' for the unconstrained "
                        + "array (§5.3). The spelling is not merely redundant: identity is "
                        + "application-structural (§8.2), so it lands on an entry distinct from '[" + shown
                        + "]' that means the same thing");
            }
            case SizeSpec.Min min -> application("array_min", element, min.lower());
            case SizeSpec.Max max -> application("array_max", element, max.upper());
            case SizeSpec.Ranged ranged -> application("array_ranged", element, ranged.lower(), ranged.upper());
            case SizeSpec.Exact exact -> application("array_ranged", element, exact.bound(), exact.bound());
        });
    }

    private static GenericRef application(String template, TypeArg element, String... bounds) {
        List<TypeArg> args = new ArrayList<>();
        args.add(element);
        for (String bound : bounds) {
            args.add(new TypeArg.Value(new TokenValue(bound, TokenForm.UNQUOTED)));
        }
        return new GenericRef(template, args);
    }

    /** Expands only within a reference's arguments, leaving its own head in place. */
    private TypeRef argumentsOnly(TypeRef ref) {
        if (!(ref instanceof GenericRef generic)) {
            return ref;
        }
        List<TypeArg> args = mapShared(generic.args(), this::typeArg);
        return args == generic.args() ? generic : new GenericRef(generic.name(), args);
    }

    private TypeArg typeArg(TypeArg arg) {
        if (!(arg instanceof TypeArg.Ref ref)) {
            return arg;
        }
        TypeRef rewritten = typeRef(ref.ref());
        return rewritten == ref.ref() ? arg : new TypeArg.Ref(rewritten);
    }

    /**
     * Hoists {@code head<args>} into its own declaration and yields a reference to it, or returns {@code
     * unexpanded} when this application is not one this phase builds: an unknown head, a non-constructor head
     * (a template application -- §5.10, out of scope), a head whose vocabulary is not record-shaped, an arity
     * mismatch, or an argument that did not reduce to a plain name. Each of those keeps its existing
     * downstream handling rather than being turned into a differently-broken shape here.
     */
    private TypeRef apply(String head, List<TypeArg> args, TypeRef unexpanded) {
        Optional<TypeDef> instance = instanceFor(head, args);
        if (instance.isEmpty()) {
            rejectIfTemplateApplication(head);
            return unexpanded;
        }
        return hoist(syntheticName(head, args), instance.get());
    }

    /**
     * §5.4's {@code (A | B)} at a type-ref position, hoisted into its own declaration and replaced by a bare
     * reference to it -- the treatment {@link #apply} gives every other inline form. Returns {@code
     * unexpanded} when {@link #choiceInstance} cannot build the construction.
     *
     * <p>The name is derived through {@link #syntheticName} from the variants themselves, so two identical
     * inline choices anywhere in the document collapse to one declaration (§8.2's structural-equality rule).
     * There is no {@link #rejectIfTemplateApplication} counterpart here: §5.6 fixes this head, so it can
     * never be an author's template.
     */
    private TypeRef hoistChoice(List<TypeRef> variants, TypeRef unexpanded) {
        Optional<TypeDef> instance = choiceInstance(variants);
        if (instance.isEmpty()) {
            return unexpanded;
        }
        return hoist(syntheticName(CHOICE, variants.stream().<TypeArg>map(TypeArg.Ref::new).toList()),
                instance.get());
    }

    /** Records an injected declaration under a derived name and yields the reference that replaces the sugar. */
    private TypeRef hoist(String name, TypeDef instance) {
        if (!imported.contains(name)) {
            injected.computeIfAbsent(name, n -> new SchemaMap.Declaration(List.of(), n, List.of(), instance));
        }
        return new SimpleRef(name);
    }

    /**
     * Rejects an application whose head is a parameterized <em>template</em> (§5.10) rather than a
     * constructor -- {@code box => <T> { v: T } } applied as {@code box<text>}, or the meta-kernel's own
     * {@code array_ranged}, which §5.3's sized sugar targets. Substituting the arguments for the parameters
     * is a real unimplemented feature rather than a rewrite this phase can perform, and without it the
     * application resolves to the template's own body with its parameters still unbound.
     *
     * <p>Two namespaces are checked, for the two places a template can be declared. A head this document
     * declares is checked against its grammar-layer {@code TypeDef}, the only place its parameters exist
     * this early. A head in the <b>structure namespace</b> is checked against its resolved definition: a
     * generic-application head is one of §3.3.1's constructor roles, so that is where a container
     * constructor is found, and a non-constructor entry with parameters sitting in the same namespace is a
     * template reached the same way.
     *
     * <p>Catching the structure-namespace case is what makes {@code tags => [text; 1..5]} report the gap it
     * actually has. Left alone it reached the linker as a body reference to {@code array_ranged}, which is
     * validated against the type-name namespace only (§3.3.2), and failed as {@code unresolved reference
     * 'array_ranged'} -- misleading, because the name is genuinely reachable at the role it is used at; what
     * is missing is substitution, not the name.
     *
     * <p>Still not covered: a template declared by an {@code !!import}. Recognising one needs the imported
     * entries' resolved definitions, and this phase is given only their names (see {@link #imported}).
     */
    private void rejectIfTemplateApplication(String head) {
        SchemaMap.Declaration declaration = local.get(head);
        if (declaration != null) {
            reject(head, typeParams(declaration.typeDef()));
            return;
        }
        TypeDefinition meta = metaEntries.get(head);
        if (meta != null && !meta.constructor()) {
            reject(head, meta.parameters());
        }
    }

    private static void reject(String head, List<String> parameters) {
        if (parameters.isEmpty()) {
            return;
        }
        throw new UnsupportedOperationException("'" + head + "' is a parameterized template, and applying one "
                + "is not implemented -- §5.10 parameter substitution has no implementation, so '" + head
                + "<...>' would resolve to the template's own body with its parameters " + parameters
                + " still unbound. Declare a concrete type instead.");
    }

    private static List<String> typeParams(TypeDef typeDef) {
        return switch (typeDef) {
            case StructuralTypeDef structural -> structural.typeParams();
            case ContainerTypeDef container -> container.typeParams();
            case ReferenceTypeDef reference -> reference.typeParams();
            default -> List.of();
        };
    }

    /**
     * The {@code !C { field: arg ... }} an application denotes, or empty when this is not one this phase
     * builds: an unknown head, a non-constructor head (a template application -- §5.10, out of scope), a head
     * whose vocabulary is not record-shaped, an arity mismatch, or an argument that did not reduce to a plain
     * name. Each of those keeps its existing downstream handling rather than being turned into a differently
     * broken shape here.
     */
    private Optional<TypeDef> instanceFor(String head, List<TypeArg> args) {
        TypeDefinition applied = metaEntries.get(head);
        if (applied == null || !(applied.body() instanceof RecordBody vocabulary)
                || applied.parameters().size() != args.size()) {
            return Optional.empty();
        }
        // A template heads its binding record at the nearest `~` constructor in its source chain (§5.6), not
        // at itself; a constructor heads its own.
        String constructorHead = applied.constructor() ? head : nearestConstructor(applied);
        if (constructorHead == null) {
            return Optional.empty();
        }
        List<RecordValue.Field> fields = new ArrayList<>();
        for (RecordField field : vocabulary.fields()) {
            Optional<String> parameter = field.valueParam();
            if (parameter.isEmpty()) {
                continue; // a fixed or defaulted vocabulary field -- the reader supplies it
            }
            int index = applied.parameters().indexOf(parameter.get());
            if (index < 0) {
                return Optional.empty();
            }
            Optional<TokenValue> token = argumentToken(args.get(index));
            if (token.isEmpty()) {
                return Optional.empty();
            }
            fields.add(new RecordValue.Field(field.name(), scoped(token.get())));
        }
        if (fields.isEmpty()) {
            return Optional.empty();
        }
        Instance body = new Instance(
                new DataValue(List.of(), Optional.of(constructorHead), new RecordValue(fields)));
        if (applied.constructor()) {
            return Optional.of(body);
        }
        checkBounds(head, fields);
        return Optional.of(new TemplateInstance(new GenericRef(head, args), body));
    }

    /**
     * §5.6's {@code (A | B | ...)} as the construction it denotes: {@code !choice { variants: [A B ...] } }.
     *
     * <p><b>Why this is not {@link #instanceFor}.</b> That path routes one argument per vocabulary field, by
     * the field's own {@code value_param} ({@code element_type: type_ref = T}), which fixes the arity at the
     * constructor's parameter count. {@code choice} declares no parameters and its single vocabulary field is
     * a <em>collection</em> ({@code variants: [type_ref]}, no {@code value_param} to route through), so no
     * per-parameter routing can express it. §5.3 names the shape instead: for "the variadic pair, {@code
     * tuple} and {@code choice}, arguments map positionally onto {@code elements} and {@code variants}". Every
     * variant becomes one element of that one field. {@code tuple} is the other half of the pair and does not
     * share this method -- its elements are {@code tuple_element} records rather than bare references.
     *
     * <p>The head is fixed by the sugar form rather than written by the author, exactly as {@code [T]} fixes
     * {@code array} (§5.6's desugaring table), so it is not looked up from an application's own text. What
     * <em>is</em> read from the governing meta is the field the variants fill, by name, so the routing still
     * comes from the vocabulary rather than from a string written here.
     *
     * <p>Empty -- leaving the choice unexpanded for whoever handles it next -- when the governing meta cannot
     * supply that vocabulary ({@code choice} absent, not a constructor, or not the single-field record its
     * kernel declaration gives it), or when a variant did not reduce to a plain name. Variants are expected
     * already expanded: a nested inline form is hoisted by the caller first, so what arrives here is a
     * {@link SimpleRef} per variant.
     *
     * <p>Distinctness of the variants (§5.4: "the resolver validates that each variant resolves to a distinct
     * type") is deliberately not checked here -- it is a question about what the names <em>resolve</em> to,
     * after reference flattening, which this phase has no answer to.
     */
    private Optional<TypeDef> choiceInstance(List<TypeRef> variants) {
        TypeDefinition choice = metaEntries.get(CHOICE);
        if (choice == null || !choice.constructor() || !(choice.body() instanceof RecordBody vocabulary)
                || vocabulary.fields().size() != 1) {
            return Optional.empty();
        }
        List<ScopedValue> elements = new ArrayList<>();
        for (TypeRef variant : variants) {
            if (!(variant instanceof SimpleRef simple)) {
                return Optional.empty();
            }
            elements.add(scoped(new TokenValue(simple.name(), TokenForm.UNQUOTED)));
        }
        RecordValue.Field field = new RecordValue.Field(vocabulary.fields().getFirst().name(),
                scoped(new ArrayValue(elements)));
        return Optional.of(new Instance(
                new DataValue(List.of(), Optional.of(CHOICE), new RecordValue(List.of(field)))));
    }

    /** A bare value in a field or element position -- no schema directive, no annotations, no type-ref of its own. */
    private static ScopedValue scoped(CoreValue value) {
        return new ScopedValue(Optional.empty(), new DataValue(List.of(), Optional.empty(), value));
    }

    /**
     * The nearest {@code ~} constructor in a template's source chain (§5.6) -- {@code array} for {@code
     * array_ranged}, whose supertypes are {@code [array, product, top]} in IS-A order, so the first entry
     * that is itself a constructor is the nearest one. {@code null} when the chain reaches none, which leaves
     * the application unexpanded rather than guessing a head.
     */
    private String nearestConstructor(TypeDefinition template) {
        for (String supertype : template.supertypes()) {
            TypeDefinition candidate = metaEntries.get(supertype);
            if (candidate != null && candidate.constructor()) {
                return supertype;
            }
        }
        return null;
    }

    /**
     * §8.2's deferred value-level check: a family coherence rule whose operands were parameters is verified
     * once substitution makes them concrete, and a violation is "a resolver error reported at the
     * materialising application". The array family's {@code min_items <= max_items} (§5.3) is the one rule
     * reachable here -- it is the only one the kernel's own templates route parameters into, and the sugar
     * spelling {@code [T; 5..3]} is the way an author hits it.
     *
     * <p>Deliberately not a general facility. The remaining rules §8.2 gestures at ("bounds within a
     * width-derived range, and their kin") belong with the constraint families that own them, alongside
     * {@code AtomNarrowing}, not in a syntax rewrite -- see {@code BACKLOG.md}.
     */
    private void checkBounds(String head, List<RecordValue.Field> fields) {
        BigInteger min = null;
        BigInteger max = null;
        for (RecordValue.Field field : fields) {
            if (!(field.value().value().coreValue() instanceof TokenValue token)) {
                continue;
            }
            try {
                if (field.name().equals("min_items")) {
                    min = new BigInteger(token.text());
                } else if (field.name().equals("max_items")) {
                    max = new BigInteger(token.text());
                }
            } catch (NumberFormatException e) {
                return; // a bound that is not a literal -- nothing concrete to compare yet
            }
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new TsonSchemaValidationException("'" + head + "<...>' binds min_items " + min
                    + " above max_items " + max + " -- an array's size range must satisfy min <= max (§5.3), "
                    + "and no value can ever satisfy this one");
        }
    }

    /** An argument reduces to a token: a plain name after expansion, or a literal value argument (a size bound). */
    private static Optional<TokenValue> argumentToken(TypeArg arg) {
        return switch (arg) {
            case TypeArg.Ref ref when ref.ref() instanceof SimpleRef simple ->
                    Optional.of(new TokenValue(simple.name(), TokenForm.UNQUOTED));
            case TypeArg.Value value -> Optional.of(value.value());
            default -> Optional.empty();
        };
    }

    /**
     * {@code head_arg_arg_hash} -- deliberately the same convention {@code TsonSchemaLinker.syntheticName}
     * used, since the name is what a diagnostic shows and several tests recognise applications by that
     * prefix. §8.2's own naming is not conformance-relevant, so only readability and stability matter.
     */
    private static String syntheticName(String head, List<TypeArg> args) {
        StringBuilder name = new StringBuilder(head);
        for (TypeArg arg : args) {
            name.append('_').append(argumentToken(arg).map(TokenValue::text).orElse("arg"));
        }
        return name.append('_').append(String.format("%08x", (head + args).hashCode())).toString();
    }

    /**
     * Maps {@code items}, returning the original list when every element came back identical. The
     * reference-equality check is what lets an unchanged subtree propagate "nothing changed" all the way up
     * rather than rebuilding every ancestor.
     */
    private static <T> List<T> mapShared(List<T> items, UnaryOperator<T> rewrite) {
        List<T> rewritten = null;
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            T mapped = rewrite.apply(item);
            if (mapped != item && rewritten == null) {
                rewritten = new ArrayList<>(items);
            }
            if (rewritten != null) {
                rewritten.set(i, mapped);
            }
        }
        return rewritten == null ? items : rewritten;
    }
}
