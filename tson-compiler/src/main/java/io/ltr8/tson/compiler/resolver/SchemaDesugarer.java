package io.ltr8.tson.compiler.resolver;

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
import io.ltr8.tson.compiler.ast.schema.StructuralDef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TupleContainerDef;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeRef;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.TypeDefinition;

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
 * declaration referring to an unbound {@code T}. A head that is not a constructor (a template application
 * such as {@code box<text>}) is also passed through untouched: §5.10 parameter substitution is a separate
 * feature, so that residue continues down the existing path.
 */
final class SchemaDesugarer {

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
                TypeRef ref = argumentsOnly(reference.ref());
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
            // Not yet expandable: DefinitionResolver has never handled either at a type-ref position, so
            // there is no behaviour here to preserve or break -- they join the arc once tuple/choice
            // desugaring is written.
            case InlineTupleRef tuple -> {
                List<TypeRef> elements = mapShared(tuple.elementTypes(), this::typeRef);
                yield elements == tuple.elementTypes() ? tuple : new InlineTupleRef(elements);
            }
            case ChoiceRef choice -> {
                List<TypeRef> variants = mapShared(choice.variants(), this::typeRef);
                yield variants == choice.variants() ? choice : new ChoiceRef(variants);
            }
        };
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
        TypeDefinition constructor = metaEntries.get(head);
        if (constructor == null || !constructor.constructor()
                || !(constructor.body() instanceof RecordBody vocabulary)
                || constructor.parameters().size() != args.size()) {
            return unexpanded;
        }
        List<RecordValue.Field> fields = new ArrayList<>();
        for (RecordField field : vocabulary.fields()) {
            Optional<String> parameter = field.valueParam();
            if (parameter.isEmpty()) {
                continue; // a fixed or defaulted vocabulary field -- the reader supplies it
            }
            int index = constructor.parameters().indexOf(parameter.get());
            if (index < 0) {
                return unexpanded;
            }
            Optional<TokenValue> token = argumentToken(args.get(index));
            if (token.isEmpty()) {
                return unexpanded;
            }
            fields.add(new RecordValue.Field(field.name(),
                    new ScopedValue(Optional.empty(), new DataValue(List.of(), Optional.empty(), token.get()))));
        }
        if (fields.isEmpty()) {
            return unexpanded;
        }
        String name = syntheticName(head, args);
        if (!imported.contains(name)) {
            injected.computeIfAbsent(name, n -> new SchemaMap.Declaration(List.of(), n, List.of(),
                    new Instance(new DataValue(List.of(), Optional.of(head), new RecordValue(fields)))));
        }
        return new SimpleRef(name);
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
