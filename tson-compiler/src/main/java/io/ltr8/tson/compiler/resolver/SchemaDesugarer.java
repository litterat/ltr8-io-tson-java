package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.ArrayRef;
import io.ltr8.tson.compiler.ast.schema.ChoiceRef;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.ElementType;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.InstanceTemplate;
import io.ltr8.tson.compiler.ast.schema.MapRef;
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
import io.ltr8.tson.compiler.ast.schema.TupleRef;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeRef;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Expands the schema sugar forms into the constructor applications they denote before anything resolves them
 * -- the {@code desugar} step of parse -&gt; desugar -&gt; resolve -&gt; link.
 *
 * <p><b>Why a phase rather than work inside the resolver or linker.</b> [TSON-SCHEMA] §5.3/§5.6 describe
 * {@code [T]} and {@code {K =&gt; V}} as <em>desugarings</em>, and §3.3.1 calls their targets "the implicit
 * desugar targets of the sugar forms". Doing that expansion once, on the AST, leaves {@code
 * DefinitionResolver} with a bare reference or {@code !C value} -- and it already handles the second
 * generically through the governing meta's compiled reader. The arrangement this replaces split the same
 * construct by position: a declaration-position application became a real body in the resolver, while a
 * field-position one was deferred to {@code TsonSchemaLinker}, which sits in a module that cannot reach that
 * generic machinery and so needed a hand-written assembler per constructor shape.
 *
 * <p><b>Purely syntactic, and per declaration.</b> The sugar set is closed and grammar-supplied, so the
 * head each form desugars to and the vocabulary field each argument fills are a fixed table (§5.3):
 *
 * <pre>
 * [T]              !array { element_type: T }
 * [T; N..M]        !array { element_type: T  min_items: N  max_items: M }
 * [T?; ...]        the corresponding form with state: OPTIONAL bound directly
 * [T, U]           !tuple { elements: [{ element_type: T } { element_type: U }] }
 * (A | B)          !choice { variants: [A B] }
 * {K =&gt; V}         !map   { key_type: K  value_type: V }
 * {K =&gt; V; N..M}   the same, with min_items/max_items
 * </pre>
 *
 * This phase therefore consults no governing meta at all. It used to: constructors carried parameter lists
 * and their vocabulary fields named the parameter each drew from ({@code element_type: type_ref = T}), so
 * routing was read off the meta and the meta-kernel's own bootstrap had to hand-write a stand-in table for
 * the three constructors it applies to itself. With the constructors parameterless the table above is the
 * whole rule, and the bootstrap needs no special case.
 *
 * <p><b>A nested bracket or brace form expands innermost first.</b> §5.3's declaration-level container
 * syntax nests inside itself -- {@code [[T; N]; N]}, {@code {text =&gt; [order; 1..]}} -- so a position
 * holding one has the inner form injected under its own derived name and becomes a bare reference to it.
 * The enclosing container then routes a plain name like any other, at any depth. See {@link #elementRef}.
 *
 * <p><b>An application is a user template, and applying one is not implemented.</b> {@code name&lt;args&gt;}
 * resolves its head through the type-name namespace only (§3.3.1) -- parameters, then locals, then imports
 * -- so it can only ever be a §5.10 template application. Substitution has no implementation, and leaving
 * the application alone produced a schema that linked and compiled and then failed on the first read that
 * reached the field, so it is rejected here, at the site that writes it. See {@link
 * #rejectTemplateApplication}.
 *
 * <p><b>Structural sharing.</b> Every method returns its input unchanged when nothing beneath it changed, so
 * a document with no sugar comes back as the same object graph. Source positions live in identity-keyed side
 * tables ({@code TsonSchemaParser.declarationPositions()} is an {@code IdentityHashMap}), so a rebuilt node
 * silently loses its position; sharing confines that to the declarations that genuinely contain sugar.
 *
 * <p><b>What is deliberately left alone.</b> Three positions keep their heads intact, because a name there is
 * being <em>declared</em> or <em>composed</em>, not applied: a declaration's own body reference
 * ({@code ReferenceTypeDef}), a refinement source, and a composition supertype. And nothing inside a
 * <em>parameterized</em> declaration is expanded at all: the desugared structure of a template body is its
 * recorded open form, and every nested form inside it becomes concrete only at materialisation, so lifting
 * one eagerly here would mint an entry for a template that may never be instantiated.
 *
 * <p><b>An invalid sugar form is reported per declaration, not thrown</b>, when a {@link
 * DesugarFailureReporter} is supplied -- the same one-pass treatment {@code SchemaResolver} and {@code
 * TsonSchemaLinker} give their own phases, so an author sees every independent problem at once rather than
 * one per run. {@link #desugarOrReport} has the mechanics and {@link #ABSORBED} what a reported declaration
 * leaves behind.
 */
final class SchemaDesugarer {

    /** §5.3's desugar target for {@code [T]} and the sized forms, fixed by the sugar rather than by the author. */
    private static final String ARRAY = "array";

    /** §5.3's desugar target for <code>{K =&gt; V}</code>, fixed the same way {@link #ARRAY} is. */
    private static final String MAP = "map";

    /** §5.3's desugar target for {@code [T, U]}. */
    private static final String TUPLE = "tuple";

    /** §5.4's desugar target for {@code (A | B)}. */
    private static final String CHOICE = "choice";

    /** The vocabulary fields the desugar table binds -- fixed by the table, not looked up in a governing meta. */
    private static final String ELEMENT_TYPE = "element_type";
    private static final String KEY_TYPE = "key_type";
    private static final String VALUE_TYPE = "value_type";
    private static final String STATE = "state";
    private static final String MIN_ITEMS = "min_items";
    private static final String MAX_ITEMS = "max_items";
    private static final String ELEMENTS = "elements";
    private static final String VARIANTS = "variants";

    /**
     * What a declaration whose sugar form was reported is replaced with: a fresh, zero-field record. The
     * counterpart of {@code SchemaResolver.unresolved} one phase later, and the same javac model -- an error
     * type that answers every question, rather than Swift's, where every questioner must first check whether
     * it is looking at one. A dependent that composes with a failed declaration resolves cleanly, contributing
     * no fields, instead of failing a second time over a problem that is purely a consequence of the first.
     *
     * <p><b>Producing one means a diagnostic has already been reported</b> -- Swift's {@code ErrorType}
     * obligation, and the half of its model worth keeping. It can only ever be reached through a {@link
     * DesugarFailureReporter}, so a document that expanded cleanly can never contain one.
     *
     * <p>Shared rather than built per failure: it carries nothing declaration-specific, and it is deliberately
     * never in {@code TsonSchemaParser.declarationPositions()} -- the position belongs to the diagnostic
     * already reported against the real declaration, not to this stand-in.
     */
    private static final TypeDef ABSORBED = new StructuralTypeDef(List.of(), false, new RecordDef(List.of()));

    /**
     * Names already in scope from {@code !!import}. A sugar form that generates a name an import already
     * declares is <em>referenced</em> rather than redeclared: the name is derived from the resolved binding
     * record itself, so an identical form in an imported schema has already produced the same type. Without
     * this, meta.tn's own {@code [type_name]} would redeclare the one it imports from the meta-kernel and be
     * rejected as a local-vs-import collision.
     */
    private final Set<String> imported;

    /** Declarations synthesised for sugar forms encountered during the walk, keyed by their generated name. */
    private final Map<String, SchemaMap.Declaration> injected = new LinkedHashMap<>();

    /** This document's own declarations, for {@link #checkTemplateApplication} -- set before the walk starts. */
    private Map<String, SchemaMap.Declaration> local = Map.of();

    /**
     * The type parameters of the declaration currently being walked, or empty outside a template. A sugar
     * form mentioning one of these is <em>open</em> -- it cannot become a closed entry, because the entry
     * would carry a reference to a parameter nothing has bound -- so it is left exactly as written for the
     * open representation to handle. Every other form in the same declaration lifts normally.
     */
    private List<String> currentParameters = List.of();

    /** Where an invalid sugar form is reported, or {@code null} to rethrow it and abandon the document. */
    private final DesugarFailureReporter reporter;

    /**
     * The identity-keyed declaration positions, carried across a rewrite -- see {@link #schemaMap}. Never
     * {@code null}; a caller with no positions to keep passes an empty map it then discards.
     */
    private final Map<SchemaMap.Declaration, SourcePosition> positions;

    private SchemaDesugarer(Set<String> imported, DesugarFailureReporter reporter,
            Map<SchemaMap.Declaration, SourcePosition> positions) {
        this.imported = imported;
        this.reporter = reporter;
        this.positions = positions;
    }

    /** {@link #desugar(SchemaDocument, Set, DesugarFailureReporter, Map)}, throwing at the first invalid sugar form. */
    static SchemaDocument desugar(SchemaDocument document, Set<String> imported) {
        return desugar(document, imported, null, new IdentityHashMap<>());
    }

    /**
     * The document with every expandable sugar form hoisted into its own declaration, or the same instance
     * when there was nothing to expand.
     *
     * <p>A declaration whose sugar form is invalid is reported to {@code reporter} and replaced with {@link
     * #ABSORBED}, so the declarations around it still expand and go on to resolve -- see {@link
     * #desugarOrReport}. With a {@code null} {@code reporter} the first such form throws instead.
     */
    static SchemaDocument desugar(SchemaDocument document, Set<String> imported,
            DesugarFailureReporter reporter, Map<SchemaMap.Declaration, SourcePosition> positions) {
        SchemaDesugarer pass = new SchemaDesugarer(imported, reporter, positions);
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

    /**
     * <b>The one place a rewritten declaration replaces an original</b>, and so the one place the original's
     * source position has to be carried over. {@code positions} is identity-keyed (see {@code
     * TsonSchemaParser.declarationPositions()}), which is what makes structural sharing load-bearing
     * everywhere else -- but a declaration that genuinely contains sugar <em>is</em> rebuilt, and without
     * this every diagnostic against it would lose its line. That is not a rare case: any record with a single
     * {@code [T]} field is rewritten whole.
     */
    private SchemaMap schemaMap(SchemaMap map) {
        Map<String, SchemaMap.Declaration> rewritten = null;
        for (Map.Entry<String, SchemaMap.Declaration> entry : map.declarations().entrySet()) {
            SchemaMap.Declaration declaration = desugarOrReport(entry.getValue());
            if (declaration != entry.getValue()) {
                SourcePosition position = positions.get(entry.getValue());
                if (position != null) {
                    positions.put(declaration, position);
                }
                if (rewritten == null) {
                    rewritten = new LinkedHashMap<>(map.declarations());
                }
            }
            if (rewritten != null) {
                rewritten.put(entry.getKey(), declaration);
            }
        }
        return rewritten == null ? map : new SchemaMap(map.annotations(), rewritten);
    }

    /**
     * One declaration expanded, or -- when its sugar form is invalid and a {@link DesugarFailureReporter} is
     * in play -- reported and replaced with {@link #ABSORBED}. Per declaration, which is both the granularity
     * {@code SchemaResolver} reports at one phase later and the finest source positions this project has.
     *
     * <p><b>The substitution is not optional.</b> Leaving the declaration un-expanded would hand {@code
     * DefinitionResolver} the very {@code ContainerTypeDef} this phase exists to remove, and it answers that
     * with an {@code UnsupportedOperationException} -- which {@code SchemaResolver} deliberately does not
     * catch, since a library gap is not a verdict on the author's schema. So passing through would convert a
     * reported author error into an unreported abort: worse than the fail-fast behaviour it replaces.
     *
     * <p>Only {@link TsonSchemaValidationException} is reported. The {@code UnsupportedOperationException}
     * for applying a template is a genuine §5.10 gap and keeps propagating, by the same test {@code
     * SchemaResolver} applies: <em>a schema error's verdict doesn't change when this library improves; a
     * gap's does.</em>
     *
     * <p><b>Anything already injected on behalf of a failed declaration stays injected</b>, and is not rolled
     * back. Injected names are derived from the binding record itself, so a later declaration containing the
     * same form finds and references the existing entry (§8.2's structural-equality rule, which is what makes
     * the injection shared rather than per-use in the first place); removing one because the declaration that
     * happened to reach it first went on to fail would break whichever declaration referenced it second. Left
     * behind, it is an ordinary unreferenced declaration that resolves and links like any other.
     */
    private SchemaMap.Declaration desugarOrReport(SchemaMap.Declaration declaration) {
        try {
            return declaration(declaration);
        } catch (TsonSchemaValidationException e) {
            if (reporter == null) {
                throw e;
            }
            reporter.reportFailedDeclaration(declaration, e);
            return new SchemaMap.Declaration(declaration.nameAnnotations(), declaration.name(),
                    declaration.typeDefAnnotations(), ABSORBED);
        }
    }

    private SchemaMap.Declaration declaration(SchemaMap.Declaration declaration) {
        currentParameters = typeParams(declaration.typeDef());
        try {
            TypeDef typeDef = typeDef(declaration.typeDef());
            return typeDef == declaration.typeDef() ? declaration
                    : new SchemaMap.Declaration(declaration.nameAnnotations(), declaration.name(),
                            declaration.typeDefAnnotations(), typeDef);
        } finally {
            currentParameters = List.of();
        }
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
                // A template's body *is* walked: a concrete form inside it lifts to an ordinary closed
                // entry, exactly as it would outside a template, leaving a plain record template that
                // applies. Only a form mentioning one of the declaration's own parameters is left alone --
                // see currentParameters.
                StructuralDef body = structuralDef(structural.body());
                yield body == structural.body() ? structural
                        : new StructuralTypeDef(structural.typeParams(), structural.constructor(), body);
            }
            // A declaration's own body reference names what this declaration *is*; only its arguments are
            // expandable, so the head stays put and its own handling is unchanged.
            case ReferenceTypeDef reference -> {
                if (!reference.typeParams().isEmpty()) {
                    yield reference;
                }
                // **A declaration's own body never lifts** (D5): the form *is* the construction, so it
                // becomes the instance directly rather than a reference to an injected one. That is what
                // keeps `score_list => [integer; 1..]` a PRODUCT entry with a real body, and
                // `contact => (email | phone)` a SUM entry with a real ChoiceBody, instead of REFERENCEs to
                // ones. Every sugar form takes this path now -- the bracket forms reach it through
                // `type-ref` like the rest, since there is no separate declaration-level tier.
                Optional<Binding> binding = binding(reference.ref());
                if (binding.isPresent()) {
                    yield instance(binding.get());
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

    /**
     * A reference at a position where a sugar form <em>is</em> expandable: expands children first, so a
     * nested form is already a plain name by the time the enclosing one is built (<code>{text =&gt;
     * [integer]}</code> injects the inner array, then the outer map referring to it).
     */
    private TypeRef typeRef(TypeRef ref) {
        if (isOpen(ref)) {
            // Parameter-bearing: it cannot close, and its open form is a separate, unimplemented feature.
            // Left as written so `checkTemplateApplication` still refuses an application of the template
            // that holds it, rather than letting a half-expanded shape through.
            return ref;
        }
        return switch (ref) {
            case SimpleRef simple -> simple;
            case ArrayRef _, TupleRef _, MapRef _ -> hoistOrKeep(binding(ref), ref);
            case ChoiceRef _ -> hoistOrKeep(binding(ref), ref);
            case GenericRef generic -> {
                checkTemplateApplication(generic.name());
                List<TypeArg> args = mapShared(generic.args(), this::typeArg);
                yield args == generic.args() ? generic : new GenericRef(generic.name(), args);
            }
        };
    }

    /**
     * Whether {@code ref} mentions one of the declaration's own type parameters -- the test that decides
     * whether a sugar form can lift now or must wait for the open representation. A {@link GenericRef} is
     * excluded: an application is not a sugar form, and one carrying a parameter closes when the enclosing
     * template is materialised.
     */
    private boolean isOpen(TypeRef ref) {
        return !currentParameters.isEmpty() && !(ref instanceof GenericRef) && mentionsParameter(ref);
    }

    private boolean mentionsParameter(TypeRef ref) {
        return switch (ref) {
            case SimpleRef simple -> currentParameters.contains(simple.name());
            case ArrayRef array -> mentionsParameter(array.elementType().typeRef());
            case MapRef map -> mentionsParameter(map.keyType())
                    || mentionsParameter(map.valueType().typeRef());
            case TupleRef tuple -> tuple.elementTypes().stream()
                    .anyMatch(e -> mentionsParameter(e.typeRef()));
            case ChoiceRef choice -> choice.variants().stream().anyMatch(this::mentionsParameter);
            case GenericRef generic -> currentParameters.contains(generic.name())
                    || generic.args().stream().anyMatch(arg ->
                            arg instanceof TypeArg.Ref r && mentionsParameter(r.ref()));
        };
    }

    /** Expands only within a reference's arguments, leaving its own head in place. */
    private TypeRef argumentsOnly(TypeRef ref) {
        if (!(ref instanceof GenericRef generic)) {
            return ref;
        }
        checkTemplateApplication(generic.name());
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
     * Checks a generic application, which after §3.3.1's type-name-only head resolution can only ever be a
     * §5.10 user-template application: {@code box => <T> { v: T }} applied as {@code box<text>}.
     *
     * <p><b>A record template passes through</b> -- substitution happens over the <em>resolved</em> open
     * form, not over the AST, so this phase leaves the application for {@code SchemaResolver} to materialise
     * and the head keeps its arguments into resolution.
     *
     * <p><b>A template containing a sugar form does not.</b> {@code box => <T> { v: [T] } } needs the open
     * representation of §5.3's forms, which is a separate, unimplemented feature; left alone the application
     * resolves against a body whose {@code [T]} became a reference to {@code array}, a name a user schema's
     * type-name namespace does not hold, so the author is told their schema has an unresolved reference to
     * something they never wrote. Failing at the site that writes it says what is actually missing.
     *
     * <p><b>An imported head passes through too</b>, and needs no check here even though this phase is handed
     * only the imported names (see {@link #imported}). A template carrying a sugar form cannot link, so it
     * cannot have been registered, so it cannot be imported: every imported template is sugar-free by
     * construction.
     *
     * <p>A head this document neither declares nor imports is left alone -- the reference is simply
     * unresolved, which is {@code TsonSchemaLinker}'s verdict to deliver. A local head that declares no
     * parameters is an ordinary author error: nothing there takes type arguments.
     */
    private void checkTemplateApplication(String head) {
        SchemaMap.Declaration declaration = local.get(head);
        if (declaration == null) {
            return;
        }
        List<String> parameters = typeParams(declaration.typeDef());
        if (parameters.isEmpty()) {
            throw new TsonSchemaValidationException("'" + head + "' declares no type parameters, so '"
                    + head + "<...>' applies arguments to something that takes none (§5.10); drop the "
                    + "argument list");
        }
        if (containsOpenSugarForm(declaration.typeDef(), parameters)) {
            throw new UnsupportedOperationException("'" + head + "' is a template whose body writes a "
                    + "container sugar form over one of its own parameters, and applying one is not "
                    + "implemented -- §5.3's forms have no open representation yet, so '" + head
                    + "<...>' cannot be materialised. A template whose parameters occupy field types and "
                    + "values applies normally, and a sugar form that mentions no parameter lifts like any "
                    + "other.");
        }
    }

    /**
     * Whether a template's body writes a §5.3/§5.4 sugar form <em>over one of its own parameters</em> -- the
     * only forms whose representation is still missing. A concrete form has already lifted to an ordinary
     * closed entry by the time anyone applies the template, so it is no obstacle; refusing on any sugar at
     * all rejected `<T> { a: T  b: [order] }`, which needs nothing this phase cannot do.
     *
     * <p>Deliberately syntactic, and run against the declaration <em>as written</em> rather than as
     * desugared: an application may be met before the template it names has been walked, and the answer
     * must not depend on that order.
     */
    private static boolean containsOpenSugarForm(TypeDef typeDef, List<String> parameters) {
        return switch (typeDef) {
            case ReferenceTypeDef reference -> openSugarInRef(reference.ref(), parameters);
            case StructuralTypeDef structural -> openSugarInStructuralDef(structural.body(), parameters);
            default -> false;
        };
    }

    private static boolean openSugarInStructuralDef(StructuralDef def, List<String> parameters) {
        return switch (def) {
            case RecordDef record -> record.entries().stream().anyMatch(e -> openSugarInEntry(e, parameters));
            case RefinedDef refined -> openSugarInRef(refined.target(), parameters)
                    || refined.body().entries().stream().anyMatch(e -> openSugarInEntry(e, parameters));
            case ConstructionDef construction -> construction.supertypes().stream()
                    .anyMatch(ref -> openSugarInRef(ref, parameters))
                    || construction.body().map(body -> body.entries().stream()
                            .anyMatch(e -> openSugarInEntry(e, parameters))).orElse(false);
        };
    }

    private static boolean openSugarInEntry(RecordEntry entry, List<String> parameters) {
        return switch (entry) {
            case FieldDef field -> field.type()
                    .map(type -> openSugarInRef(type.typeRef(), parameters)).orElse(false);
            case GroupDef group -> group.members().stream()
                    .anyMatch(member -> openSugarInRef(member.typeRef(), parameters));
        };
    }

    private static boolean openSugarInRef(TypeRef ref, List<String> parameters) {
        return switch (ref) {
            case SimpleRef _ -> false;
            case ArrayRef _, MapRef _, TupleRef _, ChoiceRef _ -> namesParameter(ref, parameters);
            case GenericRef generic -> generic.args().stream()
                    .anyMatch(arg -> arg instanceof TypeArg.Ref r && openSugarInRef(r.ref(), parameters));
        };
    }

    /** {@link #mentionsParameter} against an explicit list -- the same question asked from outside the walk. */
    private static boolean namesParameter(TypeRef ref, List<String> parameters) {
        return switch (ref) {
            case SimpleRef simple -> parameters.contains(simple.name());
            case ArrayRef array -> namesParameter(array.elementType().typeRef(), parameters);
            case MapRef map -> namesParameter(map.keyType(), parameters)
                    || namesParameter(map.valueType().typeRef(), parameters);
            case TupleRef tuple -> tuple.elementTypes().stream()
                    .anyMatch(e -> namesParameter(e.typeRef(), parameters));
            case ChoiceRef choice -> choice.variants().stream().anyMatch(v -> namesParameter(v, parameters));
            case GenericRef generic -> parameters.contains(generic.name())
                    || generic.args().stream().anyMatch(arg ->
                            arg instanceof TypeArg.Ref r && namesParameter(r.ref(), parameters));
        };
    }

    private static List<String> typeParams(TypeDef typeDef) {
        return switch (typeDef) {
            case InstanceTemplate template -> template.typeParams();
            case StructuralTypeDef structural -> structural.typeParams();

            case ReferenceTypeDef reference -> reference.typeParams();
            default -> List.of();
        };
    }

    // ── The desugar table (§5.3): one sugar form, one binding record ─────────────────────────────

    /**
     * A sugar form reduced to what it denotes: a fixed constructor head and the vocabulary fields the form
     * binds, in the order the table above lists them. Everything downstream -- the emitted {@code !C { ... }},
     * the derived entry name, the bound-coherence check -- reads this and nothing else.
     */
    private record Binding(String head, List<RecordValue.Field> fields) {
    }

    /** One position of a tuple after expansion: the type it names, and whether it is marked {@code OPTIONAL}. */
    private record Position(TypeRef typeRef, boolean optional) {
    }

    /**
     * A declaration-level container as the binding record it denotes, or empty when a position holds a form
     * this phase cannot reduce to a name -- which leaves the whole container unexpanded, since a partially
     * reduced one would be a differently-broken shape rather than a recognisable sugar form.
     */
    private Optional<Binding> binding(TypeRef ref) {
        return switch (ref) {
            case ArrayRef array -> arrayBinding(elementRef(array.elementType()),
                    array.elementType().optional(), array.size(), shownElement(array.elementType()));
            case MapRef map -> mapBinding(typeRef(map.keyType()), elementRef(map.valueType()), map.size());
            case TupleRef tuple -> tupleBinding(tuple.elementTypes().stream()
                    .map(e -> new Position(elementRef(e), e.optional())).toList());
            case ChoiceRef choice -> choiceBinding(mapShared(choice.variants(), this::typeRef));
            default -> Optional.empty();
        };
    }

    /**
     * {@code !array { element_type: T [state: OPTIONAL] [min_items: N] [max_items: M] }} -- the whole array
     * row of the desugar table, the unsized and sized spellings alike.
     *
     * <p><b>The element {@code ?} binds {@code state} directly</b>, alongside the bounds rather than through
     * them: §5.3's {@code [T?; 3]} states both at once and both land on the one record. An unmarked element
     * states nothing at all and lets §5.2's REQUIRED_DEFAULT injection supply it, exactly as a REQUIRED tuple
     * position omits its own {@code state}.
     */
    private static Optional<Binding> arrayBinding(TypeRef element, boolean optional, Optional<SizeSpec> size,
            String shown) {
        if (!(element instanceof SimpleRef simple)) {
            return Optional.empty();
        }
        List<RecordValue.Field> fields = new ArrayList<>();
        fields.add(nameField(ELEMENT_TYPE, simple.name()));
        if (optional) {
            fields.add(nameField(STATE, ElementState.OPTIONAL.name()));
        }
        size.ifPresent(spec -> fields.addAll(sizeFields(spec, "[" + shown + "; 0..]")));
        checkBounds(fields);
        return Optional.of(new Binding(ARRAY, fields));
    }

    /**
     * <code>!map { key_type: K  value_type: V [min_items: N] [max_items: M] }</code> -- the map row of the
     * desugar table. Neither side carries a {@code state}: {@code map} declares no such field, absence has no
     * defined meaning for a map value, and an absent key is already a Part 1 resolver error.
     */
    private static Optional<Binding> mapBinding(TypeRef key, TypeRef value, Optional<SizeSpec> size) {
        if (!(key instanceof SimpleRef keyName) || !(value instanceof SimpleRef valueName)) {
            return Optional.empty();
        }
        List<RecordValue.Field> fields = new ArrayList<>();
        fields.add(nameField(KEY_TYPE, keyName.name()));
        fields.add(nameField(VALUE_TYPE, valueName.name()));
        size.ifPresent(spec -> fields.addAll(
                sizeFields(spec, "{" + keyName.name() + " => " + valueName.name() + "; 0..}")));
        checkBounds(fields);
        return Optional.of(new Binding(MAP, fields));
    }

    /**
     * {@code !tuple { elements: [{ element_type: T } { element_type: U }] }} -- the tuple row of the table.
     *
     * <p><b>Why this is not {@link #choiceBinding}.</b> Both are variadic and both fill one collection-typed
     * vocabulary field. What differs is what one position <em>is</em>: a variant is a bare {@code type_ref},
     * while an element is a {@code tuple_element} record carrying a type <em>and</em> its own {@link
     * ElementState}, so each position needs a record built for it rather than a name token. {@code state} is
     * written only for an {@code OPTIONAL} position, for the reason {@link #arrayBinding} omits an unmarked
     * element's.
     */
    private static Optional<Binding> tupleBinding(List<Position> positions) {
        List<ScopedValue> elements = new ArrayList<>();
        for (Position position : positions) {
            if (!(position.typeRef() instanceof SimpleRef simple)) {
                return Optional.empty();
            }
            List<RecordValue.Field> members = new ArrayList<>();
            members.add(nameField(ELEMENT_TYPE, simple.name()));
            if (position.optional()) {
                members.add(nameField(STATE, ElementState.OPTIONAL.name()));
            }
            elements.add(scoped(new RecordValue(members)));
        }
        return Optional.of(new Binding(TUPLE,
                List.of(new RecordValue.Field(ELEMENTS, scoped(new ArrayValue(elements))))));
    }

    /**
     * {@code !choice { variants: [A B ...] }} -- the choice row of the table. Variants arrive already
     * expanded: a nested inline form is hoisted by the caller first, so what reaches here is a {@link
     * SimpleRef} per variant.
     *
     * <p>Distinctness of the variants (§5.4: "the resolver validates that each variant resolves to a distinct
     * type") is deliberately not checked here -- it is a question about what the names <em>resolve</em> to,
     * after reference flattening, which this phase has no answer to.
     */
    private static Optional<Binding> choiceBinding(List<TypeRef> variants) {
        List<ScopedValue> members = new ArrayList<>();
        for (TypeRef variant : variants) {
            if (!(variant instanceof SimpleRef simple)) {
                return Optional.empty();
            }
            members.add(scoped(new TokenValue(simple.name(), TokenForm.UNQUOTED)));
        }
        return Optional.of(new Binding(CHOICE,
                List.of(new RecordValue.Field(VARIANTS, scoped(new ArrayValue(members))))));
    }

    /**
     * §5.3's size specifier as the {@code min_items}/{@code max_items} pair it binds -- one rule for arrays
     * and maps alike, since both constructors declare the same two fields. An exact {@code N} pins both, so
     * {@code [T; 3]} and {@code [T; 3..3]} land on the very same entry.
     *
     * <p><b>A zero floor is rejected</b> rather than desugared. §5.3 calls it vacuous and asks the resolver to
     * warn while desugaring it anyway; rejecting the spelling is {@code SPEC-FEEDBACK.md} #42's position, and
     * here the warning would be guarding more than a style nit -- identity is structural (§8.2), so the form
     * lands on an entry <em>distinct from</em> the unbounded one that means exactly the same thing. That is an
     * identity trap, and the author's fix is the one §5.3 itself names. Only a literal {@code 0} is caught: a
     * bound naming a value parameter is not concrete until materialisation.
     */
    private static List<RecordValue.Field> sizeFields(SizeSpec size, String shown) {
        return switch (size) {
            case SizeSpec.Min min when min.lower().equals("0") ->
                    throw new TsonSchemaValidationException("'" + shown + "' pins a floor of zero, which every "
                            + "container already satisfies -- drop the size specifier for the unconstrained "
                            + "form (§5.3). The spelling is not merely redundant: identity is structural "
                            + "(§8.2), so it lands on an entry distinct from the unconstrained one that means "
                            + "the same thing");
            case SizeSpec.Min min -> List.of(nameField(MIN_ITEMS, min.lower()));
            case SizeSpec.Max max -> List.of(nameField(MAX_ITEMS, max.upper()));
            case SizeSpec.Ranged ranged ->
                    List.of(nameField(MIN_ITEMS, ranged.lower()), nameField(MAX_ITEMS, ranged.upper()));
            case SizeSpec.Exact exact ->
                    List.of(nameField(MIN_ITEMS, exact.bound()), nameField(MAX_ITEMS, exact.bound()));
        };
    }

    /**
     * §5.3's bound-coherence rule on the {@code min_items}/{@code max_items} pair, applying identically to
     * arrays and maps: a resolver error where the bounds are literal at schema load. A bound that names a
     * value parameter is not concrete here, and checking it is §8.2's materialisation-time question.
     *
     * <p>Deliberately not a general facility. The remaining rules §8.2 gestures at ("bounds within a
     * width-derived range, and their kin") belong with the constraint families that own them, alongside
     * {@code AtomNarrowing}, not in a syntax rewrite -- see {@code BACKLOG.md}.
     */
    private static void checkBounds(List<RecordValue.Field> fields) {
        BigInteger min = null;
        BigInteger max = null;
        for (RecordValue.Field field : fields) {
            if (!(field.value().value().coreValue() instanceof TokenValue token)) {
                continue;
            }
            try {
                if (field.name().equals(MIN_ITEMS)) {
                    min = new BigInteger(token.text());
                } else if (field.name().equals(MAX_ITEMS)) {
                    max = new BigInteger(token.text());
                }
            } catch (NumberFormatException e) {
                return; // a bound that is not a literal -- nothing concrete to compare yet
            }
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new TsonSchemaValidationException("a size specifier binds min_items " + min
                    + " above max_items " + max + " -- a container's size range must satisfy min <= max "
                    + "(§5.3), and no value can ever satisfy this one");
        }
    }

    // ── Hoisting: a sugar form becomes a declaration plus a reference to it ──────────────────────

    /** {@code !head { field: value ... }} -- the construction a binding record denotes. */
    private static TypeDef instance(Binding binding) {
        return new Instance(new DataValue(List.of(), Optional.of(binding.head()),
                new RecordValue(binding.fields())));
    }

    /**
     * A binding hoisted into its own declaration and replaced by a bare reference, or {@code unexpanded} when
     * the form did not reduce.
     */
    private TypeRef hoistOrKeep(Optional<Binding> binding, TypeRef unexpanded) {
        return binding.<TypeRef>map(this::hoist).orElse(unexpanded);
    }

    /** Records an injected declaration under its derived name and yields the reference that replaces the sugar. */
    private TypeRef hoist(Binding binding) {
        String name = bindingName(binding);
        if (!imported.contains(name)) {
            injected.computeIfAbsent(name, n -> new SchemaMap.Declaration(List.of(), n, List.of(),
                    instance(binding)));
        }
        return new SimpleRef(name);
    }

    /**
     * The type-ref an element or map-value position denotes: an ordinary reference expanded the usual way, or
     * a <b>nested declaration-level form</b> hoisted into its own declaration and replaced by its name.
     *
     * <p>§5.3's declaration-level container syntax nests inside itself ({@code [[T; 2], U]},
     * <code>{text =&gt; [order; 1..]}</code>), and the inner form desugars first. That is the bottom-up hoist
     * {@link #typeRef} already performs for an inline form, one tier down: the inner container's own binding
     * record is built and injected, and the position that held it becomes a bare reference, so the enclosing
     * container routes a plain name like any other.
     *
     * <p>Empty when the position holds a form this phase cannot build, which leaves the enclosing container
     * unexpanded and keeps its existing handling.
     */
    private TypeRef elementRef(ElementType element) {
        return typeRef(element.typeRef());
    }

    /**
     * How an element position was spelled, for the one diagnostic that quotes the sugar form back at its
     * author: the position's own name when it has one, and a stand-in when it is an inline or nested form
     * whose expansion carries a derived name the author never wrote.
     */
    private static String shownElement(ElementType element) {
        return element.typeRef() instanceof SimpleRef simple ? simple.name() : "T";
    }

    private static RecordValue.Field nameField(String name, String text) {
        return new RecordValue.Field(name, scoped(new TokenValue(text, TokenForm.UNQUOTED)));
    }

    /** A bare value in a field or element position -- no schema directive, no annotations, no type-ref of its own. */
    private static ScopedValue scoped(CoreValue value) {
        return new ScopedValue(Optional.empty(), new DataValue(List.of(), Optional.empty(), value));
    }

    // ── Internal names (§8.2) ────────────────────────────────────────────────────────────────────

    /**
     * {@code head_arg_arg_hash} -- §8.2's own recommendation for an internal name, "a readable head plus a
     * structural hash". The readable half is what a diagnostic shows and what several tests recognise a form
     * by; the hash separates forms the readable half spells alike.
     *
     * <p><b>The name is derived from the resolved binding record, not from the spelling that produced it.</b>
     * That is the one identity rule for internal entries: one entry per distinct concrete form, schema-wide,
     * so {@code [T; 3]} and {@code [T; 3..3]} collapse onto the same entry and a form arising from two
     * different declarations is written once.
     *
     * <p><b>The hash runs over a rendering this class builds itself</b> ({@link #canonical}), never over the
     * AST's own {@code toString}. Both of the JDK's ready-made answers are unusable here: {@code
     * Record::toString}'s format is documented as "subject to change" (and shifts whenever a record's
     * components are renamed or reordered), and {@code Record::hashCode} "need not remain consistent from one
     * execution of an application to another execution of the same application". {@code String.hashCode} is
     * specified exactly, so hashing a string built here is the one construction that is deterministic by
     * contract rather than by accident.
     *
     * <p>That determinism is load-bearing, not cosmetic. An entry name is part of the resolved form, and an
     * importing schema reaches an <em>imported</em> entry by deriving the same name for the same form --
     * meta.tn's {@code extern.types: [type_name]?} landing on the entry meta-kernel already produced.
     */
    private static String bindingName(Binding binding) {
        StringBuilder readable = new StringBuilder(binding.head());
        for (RecordValue.Field field : binding.fields()) {
            appendReadable(readable, field.value().value().coreValue());
        }
        return readable.append('_').append(String.format("%08x", canonical(binding).hashCode())).toString();
    }

    /** The readable half of a derived name: every scalar the binding record holds, in order, under {@code _}. */
    private static void appendReadable(StringBuilder out, CoreValue value) {
        switch (value) {
            case TokenValue token -> out.append('_').append(token.text());
            case RecordValue record -> record.fields()
                    .forEach(field -> appendReadable(out, field.value().value().coreValue()));
            case ArrayValue array -> array.elements()
                    .forEach(element -> appendReadable(out, element.value().coreValue()));
            default -> out.append("_v");
        }
    }

    /**
     * The binding record rendered as one string, structurally and injectively: every value shape is written
     * under its own tag, nested records and arrays recurse, and each piece of author text is written
     * length-first ({@code 4:text}), so no arrangement of delimiters inside a token can spell a different
     * record. Two renderings are equal exactly when the binding records are.
     */
    private static String canonical(Binding binding) {
        StringBuilder out = new StringBuilder();
        appendText(out.append('A'), binding.head());
        appendFields(out, binding.fields());
        return out.toString();
    }

    private static void appendFields(StringBuilder out, List<RecordValue.Field> fields) {
        out.append('(');
        for (RecordValue.Field field : fields) {
            appendText(out.append('f'), field.name());
            appendValue(out, field.value().value().coreValue());
        }
        out.append(')');
    }

    private static void appendValue(StringBuilder out, CoreValue value) {
        switch (value) {
            case TokenValue token -> {
                // The form by name, not ordinal: inserting a TokenForm constant would renumber every ordinal
                // invisibly, which is the same hazard as hashing a record's toString.
                appendText(out.append('v'), token.form().name());
                appendText(out, token.text());
            }
            case RecordValue record -> appendFields(out.append('r'), record.fields());
            case ArrayValue array -> {
                out.append("a(");
                array.elements().forEach(element -> appendValue(out, element.value().coreValue()));
                out.append(')');
            }
            default -> out.append('?');
        }
    }

    /** Length-first, so concatenation stays unambiguous whatever the text contains. */
    private static void appendText(StringBuilder out, String text) {
        out.append(text.length()).append(':').append(text);
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
