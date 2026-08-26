package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.Annotation;
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
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.meta.ElementState;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.SourcePosition;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
 * <p><b>An application is a user template, and it passes through.</b> {@code name&lt;args&gt;} resolves its
 * head through the type-name namespace only (§3.3.1) -- parameters, then locals, then imports -- so it can
 * only ever be a §5.10 template application, and {@code TemplateMaterialiser} closes it over the
 * <em>resolved</em> form one phase later. What is checked here is the one thing an AST alone decides: a head
 * this document declares with no parameters takes no arguments at all. See {@link #checkTemplateApplication}.
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
 * one per run. {@link #desugarOrReport} has the mechanics and {@link #absorbed} what a reported declaration
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

    /** §5.2's desugar target for a bare record body {@code { x: T }} -- the constructor it denotes. */
    private static final String RECORD = "record";

    /** The vocabulary fields the desugar table binds -- fixed by the table, not looked up in a governing meta. */
    private static final String ELEMENT_TYPE = "element_type";
    private static final String KEY_TYPE = "key_type";
    private static final String VALUE_TYPE = "value_type";
    private static final String STATE = "state";
    private static final String MIN_ITEMS = "min_items";
    private static final String MAX_ITEMS = "max_items";
    private static final String ELEMENTS = "elements";
    private static final String VARIANTS = "variants";

    /** {@code record}'s own collection fields, and {@code record_field}'s scalar ones. */
    private static final String FIELDS = "fields";
    private static final String GROUPS = "groups";
    private static final String MEMBERS = "members";
    private static final String FIELD_NAME = "name";
    private static final String TYPE = "type";
    private static final String SUPERTYPES = "supertypes";

    /** {@code type_ref}'s own two fields, for the record form a slot takes when it holds an application. */
    private static final String NAME = "name";
    private static final String ARGUMENTS = "arguments";
    private static final String VALUE = "value";


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
     * <p><b>It keeps the declaration's own type parameters</b>, and that is the one declaration-specific
     * thing it carries. Answering "how many type parameters?" with zero makes a downstream {@code bl<text>}
     * report that {@code bl} "declares no type parameters ... drop the argument list" -- advice that is
     * wrong, since the fix is upstream at the declaration that actually failed. Absorbing means answering
     * every question, not answering them all with nothing.
     *
     * <p>It is deliberately never in {@code TsonSchemaParser.declarationPositions()} -- the position belongs
     * to the diagnostic already reported against the real declaration, not to this stand-in.
     *
     * <p><b>Keeping those parameters used to make it the last parameterised {@code RecordBody} in the
     * system</b>, and so kept a whole second substitution path alive in {@code TemplateMaterialiser} to
     * serve a body with no fields to substitute into. It is held like every other open body now
     * ({@link #heldEmptyRecord}, applied by {@code DefinitionResolver.holdIfOpen} where this resolves), which
     * is what lets that path delete.
     */
    private static TypeDef absorbed(SchemaMap.Declaration declaration) {
        return new StructuralTypeDef(typeParams(declaration.typeDef()), false, new RecordDef(List.of()));
    }

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
     * form naming one of these lifts to an <em>open</em> entry rather than a closed one -- a closed entry
     * would carry a reference to a parameter nothing has bound. Every other form in the same declaration
     * lifts exactly as it would outside a template (D5's one rule).
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
     * The names {@code desugared} holds that {@code original} did not: the entries this phase lifted, and so
     * exactly the schema's <b>synthetic entries</b> ([TSON-SCHEMA] §5.3's lift rule -- closed for a concrete
     * form, open for a parameter-bearing one). Both callers of {@link #desugar} need the set: one to mark
     * each of them {@code @synthetic} at its key (§8.2), and {@code SchemaResolver} also to tell a generated
     * head closing its own intermediate form from an authored one.
     *
     * <p>A set difference rather than a field on the pass, because {@link #hoist} does not inject a form an
     * {@code !!import} already declares -- the imported entry <em>is</em> the same form, resolved by the
     * schema that owns it, and marking it here would put this schema's own derived marker on someone else's
     * key. What the difference reports is what this document gained.
     */
    static Set<String> lifted(SchemaDocument original, SchemaDocument desugared) {
        Set<String> lifted = new LinkedHashSet<>(desugared.body().declarations().keySet());
        lifted.removeAll(original.body().declarations().keySet());
        return lifted;
    }

    /**
     * The document with every expandable sugar form hoisted into its own declaration, or the same instance
     * when there was nothing to expand.
     *
     * <p>A declaration whose sugar form is invalid is reported to {@code reporter} and replaced with {@link
     * #absorbed}, so the declarations around it still expand and go on to resolve -- see {@link
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
     * in play -- reported and replaced with {@link #absorbed}. Per declaration, which is both the granularity
     * {@code SchemaResolver} reports at one phase later and the finest source positions this project has.
     *
     * <p><b>The substitution is not optional.</b> Leaving the declaration un-expanded would hand {@code
     * DefinitionResolver} the very {@code ContainerTypeDef} this phase exists to remove, and it answers that
     * with an {@code UnsupportedOperationException} -- which {@code SchemaResolver} deliberately does not
     * catch, since a library gap is not a verdict on the author's schema. So passing through would convert a
     * reported author error into an unreported abort: worse than the fail-fast behaviour it replaces.
     *
     * <p><b>A gap is reported too, and absorbed the same way</b> -- as {@code Diagnostic.Code.NOT_IMPLEMENTED},
     * not as an author error. Thrown instead, it took every other declaration's verdict with it: one
     * unimplemented construct and a document with three ordinary mistakes in it reported none of them. The
     * classification the exception policy draws is unchanged and is what picks the code; what changes is
     * that it no longer decides whether the pass survives. Fail-fast (a {@code null} reporter) still
     * rethrows the original exception untouched, so every caller that never took a receiver sees exactly
     * what it always did.
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
        } catch (TsonSchemaValidationException | UnsupportedOperationException e) {
            if (reporter == null) {
                throw e;
            }
            reporter.reportFailedDeclaration(declaration, e);
            return new SchemaMap.Declaration(declaration.nameAnnotations(), declaration.name(),
                    declaration.typeDefAnnotations(), absorbed(declaration));
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
                // §5.2's own rewrite, applied where the body is written: a bare record body denotes
                // `!record { fields: [ ... ] }`, so a record template becomes the construction it always
                // was and is held like every other open form. See recordBinding.
                if (!structural.typeParams().isEmpty() && !structural.constructor()
                        && body instanceof RecordDef record) {
                    yield instance(recordBinding(record), structural.typeParams());
                }
                yield body == structural.body() ? structural
                        : new StructuralTypeDef(structural.typeParams(), structural.constructor(), body);
            }
            // A declaration's own body reference names what this declaration *is*; only its arguments are
            // expandable, so the head stays put and its own handling is unchanged.
            case ReferenceTypeDef reference -> {
                // **A declaration's own body never lifts** (D5): the form *is* the construction, so it
                // becomes the instance directly rather than a reference to an injected one. That is what
                // keeps `score_list => [integer; 1..]` a PRODUCT entry with a real body, and
                // `contact => (email | phone)` a SUM entry with a real ChoiceBody, instead of REFERENCEs to
                // ones. Every sugar form takes this path now -- the bracket forms reach it through
                // `type-ref` like the rest, since there is no separate declaration-level tier.
                Optional<Binding> binding = binding(reference.ref());
                if (binding.isPresent()) {
                    // The same rule one tier up: a template's own body is the open construction, so
                    // `vector => <T, N> [T; N]` *is* the instance template rather than a reference to one.
                    // Its parameters are the declaration's own list as written, not the subset the body
                    // happens to name -- a declared parameter the body never uses is an error the linker
                    // reports (§5.10), and dropping it here would hide the very thing it looks for.
                    yield instance(binding.get(), reference.typeParams());
                }
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

    /**
     * A reference at a position where a sugar form <em>is</em> expandable: expands children first, so a
     * nested form is already a plain name by the time the enclosing one is built (<code>{text =&gt;
     * [integer]}</code> injects the inner array, then the outer map referring to it).
     */
    private TypeRef typeRef(TypeRef ref) {
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
     * <p><b>A template containing a sugar form passes through as well</b>, now that {@code box => <T> { v:
     * [T] } } lifts that form to an open synthetic instead of leaving it where it was. What used to be
     * refused here is the mechanism itself.
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
    }

    /** A declaration's declared type parameters, or none -- also how a placeholder learns its own arity. */
    static List<String> typeParams(TypeDef typeDef) {
        return switch (typeDef) {
            case Instance instance -> instance.typeParams();
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
    private record Binding(String head, List<RecordValue.Field> fields,
                            Map<String, TypeRef> applicationSlots) {

        Binding(String head, List<RecordValue.Field> fields) {
            this(head, fields, Map.of());
        }
    }

    /**
     * A scalar type slot as both of the things downstream needs it as: the wire field a closed construction
     * writes, and -- when the reference carries arguments -- the reference itself, kept whole for the open
     * form to bind.
     *
     * <p><b>Why an application needs the second half.</b> A closed slot is a bare token, the positional form
     * of a {@code type_ref} (§5.6). An application has no bare-token spelling: the entry it denotes does not
     * exist until materialisation, one phase later. Its wire form is the record one, which keeps the name and
     * the arguments apart -- structurally right, and what {@link #internalName} hashes -- but an open binding
     * holds a {@code type_ref} directly rather than reading one, so it wants the reference as written.
     */
    private static void refSlot(String slot, TypeRef ref, List<RecordValue.Field> fields,
            Map<String, TypeRef> applicationSlots) {
        fields.add(new RecordValue.Field(slot, scoped(refValue(ref))));
        if (!(ref instanceof SimpleRef)) {
            applicationSlots.put(slot, ref);
        }
    }

    /**
     * What a {@code type_ref}-typed slot holds: a bare token for a plain name, {@code type_ref}'s record
     * form for an application. <b>One spelling per shape, produced in one place</b> -- a slot written two
     * ways is a slot two phases disagree about, and {@link #internalName} hashes what is written, so a
     * second spelling of one reference splits one type across two entries.
     */
    private static CoreValue refValue(TypeRef ref) {
        return ref instanceof SimpleRef simple ? new TokenValue(simple.name(), TokenForm.UNQUOTED)
                : refRecord((GenericRef) ref);
    }

    /**
     * <code>{ name: head  arguments: [ { name: A } ] }</code> -- {@code type_ref}'s record form, which is how
     * a closed slot carries an application through the constructor's own reader.
     *
     * <p>A <em>value</em> argument makes the trip intact: {@code type_argument}'s value channel binds a raw
     * {@code Token}, §5.10 describing a type argument's literal as a bare token rather than as the value it
     * denotes, so the reader that fills it preserves the token instead of decoding it ({@code
     * RawTokenParser}). What that costs -- identity keyed on the spelling, so {@code <255>} and {@code
     * <0xFF>} are two applications -- is {@code SPEC-FEEDBACK.md} #4.
     */
    private static RecordValue refRecord(GenericRef generic) {
        List<ScopedValue> arguments = new ArrayList<>();
        for (TypeArg argument : generic.args()) {
            arguments.add(scoped(new RecordValue(List.of(switch (argument) {
                case TypeArg.Ref reference when reference.ref() instanceof GenericRef nested ->
                        new RecordValue.Field(NAME, scoped(refRecord(nested)));
                case TypeArg.Ref reference ->
                        nameField(NAME, ((SimpleRef) reference.ref()).name());
                case TypeArg.Value value -> new RecordValue.Field(VALUE, scoped(value.value()));
            }))));
        }
        return new RecordValue(List.of(nameField(NAME, generic.name()),
                new RecordValue.Field(ARGUMENTS, scoped(new ArrayValue(arguments)))));
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
        if (!isReference(element)) {
            return Optional.empty();
        }
        List<RecordValue.Field> fields = new ArrayList<>();
        Map<String, TypeRef> applications = new LinkedHashMap<>();
        refSlot(ELEMENT_TYPE, element, fields, applications);
        if (optional) {
            fields.add(nameField(STATE, ElementState.OPTIONAL.name()));
        }
        size.ifPresent(spec -> fields.addAll(sizeFields(spec, "[" + shown + "; 0..]")));
        checkBounds(fields);
        return Optional.of(new Binding(ARRAY, fields, applications));
    }

    /**
     * Whether a position holds something this table can put in a type slot: a name, or a name carrying
     * arguments. Anything else is a sugar form the caller was supposed to have expanded first, and leaves the
     * enclosing container unexpanded rather than half-built.
     */
    private static boolean isReference(TypeRef ref) {
        return ref instanceof SimpleRef || ref instanceof GenericRef;
    }

    /**
     * <code>!map { key_type: K  value_type: V [min_items: N] [max_items: M] }</code> -- the map row of the
     * desugar table. Neither side carries a {@code state}: {@code map} declares no such field, absence has no
     * defined meaning for a map value, and an absent key is already a Part 1 resolver error.
     */
    private static Optional<Binding> mapBinding(TypeRef key, TypeRef value, Optional<SizeSpec> size) {
        if (!isReference(key) || !isReference(value)) {
            return Optional.empty();
        }
        List<RecordValue.Field> fields = new ArrayList<>();
        Map<String, TypeRef> applications = new LinkedHashMap<>();
        refSlot(KEY_TYPE, key, fields, applications);
        refSlot(VALUE_TYPE, value, fields, applications);
        size.ifPresent(spec -> fields.addAll(
                sizeFields(spec, "{" + shownRef(key) + " => " + shownRef(value) + "; 0..}")));
        checkBounds(fields);
        return Optional.of(new Binding(MAP, fields, applications));
    }

    /** How a map side is quoted back in the one diagnostic that shows the form. */
    private static String shownRef(TypeRef ref) {
        return ref instanceof SimpleRef simple ? simple.name() : ((GenericRef) ref).name() + "<...>";
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
     * {@code !record { fields: [ { name: x  type: T } ... ] }} -- §5.2's own rewrite of a bare record body,
     * applied where the body is written so that a record template holds an application like every other open
     * form.
     *
     * <p><b>Why here rather than in the resolver.</b> The alternative is to resolve the body and write the
     * result back out, and it does not work: the wire form is what {@link #internalName} hashes and what
     * substitution walks, so a second producer of it is a second spelling of the same thing --
     * {@code TsonObjectWriter} states a no-argument {@code type_ref} in the explicit record form where this
     * phase states it positionally, which makes a {@code type_argument} indistinguishable from a
     * {@code type_ref} application to a walk that reads neither against a vocabulary. §5.2's rewrite is
     * syntactic, as fixed and as closed as the sugar table above, so it belongs beside it.
     *
     * <p><b>Only what the author wrote is written.</b> {@code access_pattern} and {@code size_type} are
     * {@code REQUIRED_FIXED} on the {@code record} constructor and a field's unmarked {@code REQUIRED} is
     * that constructor's own default, so neither is stated -- the same economy {@link #arrayBinding} makes
     * with an unmarked element's {@code state}, and what keeps the held form the one the author would
     * recognise.
     *
     * <p><b>A parameter rides the ordinary {@code value} slot</b>, with §8.1's shadowing rule to tell it from
     * a literal. That is what a held body buys and what retires {@code record_field.value_param}: the
     * separate channel existed because a body read as constructor vocabulary at its declaration cannot
     * otherwise say which of the two a token is, and a held body is read as vocabulary only once its
     * parameters are gone. §5.7's fixation then happens at materialisation, where {@code TemplateMaterialiser}
     * turns a {@code REQUIRED} field that has acquired a value into {@code REQUIRED_FIXED}.
     */
    private Binding recordBinding(RecordDef record) {
        List<ScopedValue> fields = new ArrayList<>();
        List<ScopedValue> groups = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RecordEntry entry : record.entries()) {
            switch (entry) {
                case FieldDef field -> {
                    requireFieldNameUnseen(field.name(), seen, "this body declares it twice");
                    fields.add(recordField(field));
                }
                case GroupDef group -> {
                    List<ScopedValue> members = new ArrayList<>();
                    for (GroupDef.Member member : group.members()) {
                        requireFieldNameUnseen(member.name(), seen, "a group member repeats it -- member "
                                + "labels share the enclosing record's field namespace");
                        // A group's members are ordinary OPTIONAL fields of the record, and the group records
                        // only their names and its own state (§5.11) -- the same shape the resolver builds.
                        fields.add(scoped(new RecordValue(List.of(
                                nameField(FIELD_NAME, member.name()),
                                new RecordValue.Field(TYPE, scoped(refValue(member.typeRef()))),
                                nameField(STATE, FieldState.OPTIONAL.name()))), member.annotations()));
                        members.add(scoped(new TokenValue(member.name(), TokenForm.UNQUOTED)));
                    }
                    List<RecordValue.Field> groupFields = new ArrayList<>();
                    groupFields.add(new RecordValue.Field(MEMBERS, scoped(new ArrayValue(members))));
                    if (group.optional()) {
                        groupFields.add(nameField(STATE, ElementState.OPTIONAL.name()));
                    }
                    groups.add(scoped(new RecordValue(groupFields), group.annotations()));
                }
            }
        }
        List<RecordValue.Field> binding = new ArrayList<>();
        binding.add(new RecordValue.Field(FIELDS, scoped(new ArrayValue(fields))));
        if (!groups.isEmpty()) {
            binding.add(new RecordValue.Field(GROUPS, scoped(new ArrayValue(groups))));
        }
        return new Binding(RECORD, binding);
    }

    /**
     * The same {@code !record { … }} held body, built from a body that is <b>already resolved</b> -- the form
     * a composition or refinement template arrives in, since both absorb fields from a source and so cannot
     * be rewritten before there is a namespace to absorb from.
     *
     * <p><b>It lives here, next to {@link #recordBinding}, because the spelling is what must not fork.</b>
     * Two producers of the held wire form are fine; two <em>spellings</em> of it are not, and that is the
     * whole lesson of the record case. So both go through {@link #refValue} and {@link #nameField} and write
     * the same shape: an unquoted token where the writer would quote, a bare name where the writer would
     * state {@code { name: X  arguments: [] }}, and nothing at all where the constructor's own default says
     * it. {@code TsonObjectWriter} cannot serve: its output is canonical-explicit and fully quoted, which is
     * a different language from the one a held body is written in -- {@code TemplateBody.names()} and
     * substitution both key on a token being unquoted, so a quoted body references no parameters at all.
     *
     * <p><b>{@code annotationValue} is the one thing this cannot do itself.</b> A resolved annotation carries
     * its value as a <em>bound object</em> ({@code Annotation.value} is {@code Optional<Object>}), and
     * unbinding one is exactly what an object writer is for -- so the caller passes that single leaf in and
     * everything structural stays here.
     *
     * <p><b>{@code value_param} does not survive the trip, deliberately.</b> A routed parameter is written
     * into the ordinary {@code value} slot like every other token, which is what retires the channel for this
     * shape; §5.7's fixation then happens at materialisation, where the value is concrete.
     */
    /**
     * The held body an <b>error placeholder</b> carries -- {@code !record { fields: [] }}, the zero-field
     * record both absorbing stand-ins already stood for, now held like every other open body.
     *
     * <p><b>It exists so that "an open entry's body is held or a {@code Reference}" has no exceptions.</b>
     * A placeholder keeps its declaration's type parameters on purpose (answering "how many?" with zero
     * sends a downstream {@code bl<text>} to fix the wrong declaration), which used to make it the last
     * producer of a parameterised {@code RecordBody} -- and so kept a whole second substitution path alive
     * to serve a body that has no fields to substitute into.
     *
     * <p>Built structurally rather than through {@link #heldRecord}: a placeholder is what a <em>reported</em>
     * declaration leaves behind, so the one thing it must not do is fail again, and an empty record needs
     * neither a namespace nor a writer to state.
     */
    static DataValue heldEmptyRecord() {
        return new DataValue(List.of(), Optional.of(RECORD), new RecordValue(List.of(
                new RecordValue.Field(FIELDS, scoped(new ArrayValue(List.of()))))));
    }

    static DataValue heldRecord(RecordBody body, Function<Object, DataValue> annotationValue) {
        List<ScopedValue> fields = new ArrayList<>();
        for (RecordField field : body.fields()) {
            List<RecordValue.Field> members = new ArrayList<>();
            members.add(nameField(FIELD_NAME, field.name()));
            members.add(new RecordValue.Field(TYPE, scoped(refValue(field.type()))));
            if (field.state() != FieldState.REQUIRED) {
                members.add(nameField(STATE, field.state().name()));
            }
            // The two channels collapse into one: a literal keeps its own token form, and a routed parameter
            // is a bare name standing where the literal would.
            field.value().ifPresent(token -> members.add(new RecordValue.Field(VALUE,
                    scoped(new TokenValue(token.text(), tokenForm(token.form()))))));
            field.valueParam().ifPresent(parameter -> members.add(nameField(VALUE, parameter)));
            fields.add(scoped(new RecordValue(members), annotations(field.annotations(), annotationValue)));
        }
        List<ScopedValue> groups = new ArrayList<>();
        for (FieldGroup group : body.groups()) {
            List<RecordValue.Field> members = new ArrayList<>();
            members.add(new RecordValue.Field(MEMBERS, scoped(new ArrayValue(group.members().stream()
                    .map(member -> scoped(new TokenValue(member, TokenForm.UNQUOTED))).toList()))));
            if (group.state() != ElementState.REQUIRED) {
                members.add(nameField(STATE, group.state().name()));
            }
            groups.add(scoped(new RecordValue(members)));
        }
        List<RecordValue.Field> binding = new ArrayList<>();
        if (!body.supertypes().isEmpty()) {
            binding.add(new RecordValue.Field(SUPERTYPES, scoped(new ArrayValue(body.supertypes().stream()
                    .map(supertype -> scoped(new TokenValue(supertype, TokenForm.UNQUOTED))).toList()))));
        }
        binding.add(new RecordValue.Field(FIELDS, scoped(new ArrayValue(fields))));
        if (!groups.isEmpty()) {
            binding.add(new RecordValue.Field(GROUPS, scoped(new ArrayValue(groups))));
        }
        return new DataValue(List.of(), Optional.of(RECORD), new RecordValue(binding));
    }

    /** A resolved annotation carrier back in wire form, its bound value unbound by the caller's writer. */
    private static List<Annotation> annotations(Annotations resolved, Function<Object, DataValue> annotationValue) {
        if (resolved.values().isEmpty()) {
            return List.of();
        }
        List<Annotation> written = new ArrayList<>();
        for (io.ltr8.annotation.Annotation annotation : resolved.values()) {
            written.add(new Annotation(annotation.name(), annotation.value().map(annotationValue)));
        }
        return written;
    }

    /** A resolved type reference in the held spelling: a bare name, or {@code type_ref}'s record form. */
    private static CoreValue refValue(io.ltr8.tson.schema.meta.TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return new TokenValue(ref.name(), TokenForm.UNQUOTED);
        }
        List<ScopedValue> arguments = new ArrayList<>();
        for (io.ltr8.tson.schema.meta.TypeArgument argument : ref.arguments()) {
            arguments.add(scoped(new RecordValue(List.of(switch (argument) {
                case io.ltr8.tson.schema.meta.TypeArgument.Ref reference ->
                        new RecordValue.Field(NAME, scoped(refValue(reference.ref())));
                case io.ltr8.tson.schema.meta.TypeArgument.Value literal ->
                        new RecordValue.Field(VALUE, scoped(new TokenValue(literal.value().text(),
                                tokenForm(literal.value().form()))));
            }))));
        }
        return new RecordValue(List.of(nameField(NAME, ref.name()),
                new RecordValue.Field(ARGUMENTS, scoped(new ArrayValue(arguments)))));
    }

    private static TokenForm tokenForm(io.ltr8.tson.schema.meta.Token.Form form) {
        return switch (form) {
            case UNQUOTED -> TokenForm.UNQUOTED;
            case SINGLE_LINE_QUOTED -> TokenForm.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> TokenForm.MULTI_LINE_QUOTED;
        };
    }

    /**
     * §5.11's uniqueness rule, over the body this phase is rewriting: a field name is unique across a
     * record's plain fields and all its groups' members.
     *
     * <p><b>It has to be asked here as well as in the resolver</b>, and asking it twice is not duplication:
     * the resolver's copy sees a closed record body, this one sees a template's, and after normalisation
     * those are two different phases. The rule is syntactic -- two entries, one name -- so it needs nothing
     * the resolver has and this phase does not. Left to the constructor's own reader instead, the wire form
     * would carry two {@code record_field} records in an array, where repetition is not an error at all.
     */
    private static void requireFieldNameUnseen(String name, Set<String> seen, String explanation) {
        if (!seen.add(name)) {
            throw new TsonSchemaValidationException("field '" + name + "' is declared more than once -- "
                    + explanation + " (§5.11: a field name is unique across a record's plain fields and all "
                    + "its groups' members)");
        }
    }

    /**
     * One {@code record_field}, with {@code state} and {@code value} written only where the author's marks
     * say something the constructor's own defaults do not.
     *
     * <p>A field with no type-ref is a §5.7 tightening entry, which needs a source to elide toward and so
     * cannot appear in the fresh record body this builds -- {@link FieldModifiers} has no view of an
     * inherited field, and neither does this phase.
     */
    private ScopedValue recordField(FieldDef field) {
        if (field.type().isEmpty()) {
            throw new TsonSchemaValidationException("field '" + field.name() + "' states only a modifier and no "
                    + "type-ref, but names no inherited field to take a type from -- a modifier-only entry is "
                    + "always a tightening, so it is only meaningful in a refinement or composition body, "
                    + "against a field the source declares (§5.7)");
        }
        FieldDef.FieldType type = field.type().orElseThrow();
        FieldModifiers.Resolved resolved =
                FieldModifiers.of(field.name(), type.optional(), field.modifier(), currentParameters);
        List<RecordValue.Field> members = new ArrayList<>();
        members.add(nameField(FIELD_NAME, field.name()));
        members.add(new RecordValue.Field(TYPE, scoped(refValue(type.typeRef()))));
        if (resolved.state() != FieldState.REQUIRED) {
            members.add(nameField(STATE, resolved.state().name()));
        }
        resolved.value().ifPresent(token -> members.add(new RecordValue.Field(VALUE, scoped(token))));
        return scoped(new RecordValue(members), field.annotations());
    }

    /**
     * §5.3's size specifier as the {@code min_items}/{@code max_items} pair it binds -- one rule for arrays
     * and maps alike, since both constructors declare the same two fields. An exact {@code N} pins both, so
     * {@code [T; 3]} and {@code [T; 3..3]} land on the very same entry.
     *
     * <p><b>A zero floor is rejected</b> rather than desugared, which is §5.3's own rule: "a lower bound of
     * {@code 0} with no upper bound ({@code 0..}) is a resolver error". More than a style nit --
     * identity is structural (§8.2), so the form
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

    /**
     * {@code !head { field: value ... }} -- the construction a binding record denotes.
     *
     * <p><b>A slot holding an application is written in {@code type_ref}'s record form</b>, since an
     * application has no bare-token spelling: the entry it denotes does not exist until materialisation, one
     * phase later. The construction therefore names something that is not yet an entry, and that is fine --
     * materialisation rewrites every closed entry's references afterwards, so {@code [box<text>]} resolves to
     * an array whose {@code element_type} is {@code box<text>} and then, one pass on, to one whose element is
     * the instantiation entry. The entry dangles for exactly the window an ordinary forward reference does.
     */
    private static TypeDef instance(Binding binding) {
        return instance(binding, List.of());
    }

    /**
     * The same construction with a parameter list, for a form naming a parameter of the declaration it sits
     * in -- {@code <p0> !array { element_type: p0 }}. {@code typeParams} is that form's own list, already
     * renamed positionally.
     *
     * <p><b>The open form is the closed form.</b> One binding record serves both, because an open entry's
     * body is held rather than read against its constructor's vocabulary until materialisation has
     * substituted: a parameter in a slot is simply the token that stands there, and the phase needs no
     * per-slot analysis to decide how to quote it. That is what removes the collection boundary -- a
     * parameter in {@code variants} or {@code elements} is a token inside an array like any other, where a
     * typed quotation had no case for it.
     */
    private static TypeDef instance(Binding binding, List<String> typeParams) {
        return new Instance(typeParams, new DataValue(List.of(), Optional.of(binding.head()),
                new RecordValue(binding.fields())));
    }

    /**
     * Whether a binding's token is unambiguously a literal -- §12.1's own rule for {@code type-arg}, applied
     * to a record this phase built rather than to one it parsed. A quoted token or one shaped like a number
     * is a value and nothing else; every other token is carried on the reference channel, and what it turns
     * out to be -- a type, an enum member, a parameter of the enclosing declaration -- is settled at
     * resolution, where the parameter list and the slot's declared type are both in hand. Deciding it here
     * instead would mean a size bound naming a parameter ({@code [text; N]}) arrived as the literal "N".
     */
    private static boolean isLiteral(TokenValue token) {
        if (token.form() != TokenForm.UNQUOTED) {
            return true;
        }
        try {
            new BigInteger(token.text());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * A binding hoisted into its own declaration and replaced by a reference to it, or {@code unexpanded} when
     * the form did not reduce.
     */
    private TypeRef hoistOrKeep(Optional<Binding> binding, TypeRef unexpanded) {
        return binding.<TypeRef>map(this::hoist).orElse(unexpanded);
    }

    /**
     * Records an injected declaration under its derived name and yields the reference that replaces the sugar.
     *
     * <p><b>Which entry it lifts to is D5's one rule.</b> A form naming none of the enclosing declaration's
     * parameters lifts <em>closed</em> -- an ordinary construction referenced by a bare name -- whether or not
     * the declaration around it is a template. A form naming one lifts <em>open</em>: a template over just the
     * parameters it uses, referenced by an application binding them straight back through. {@code <T> { a: [T]
     *  b: [order] }} therefore injects one of each, and only the first has to wait for materialisation.
     */
    private TypeRef hoist(Binding binding) {
        List<String> parameters = parametersIn(binding);
        if (parameters.isEmpty()) {
            String name = bindingName(binding);
            if (!imported.contains(name)) {
                injected.computeIfAbsent(name, n -> new SchemaMap.Declaration(List.of(), n, List.of(),
                        instance(binding)));
            }
            return new SimpleRef(name);
        }
        List<String> renamed = positionalNames(binding, parameters);
        Binding normalised = rename(binding, parameters, renamed);
        String name = bindingName(normalised);
        if (!imported.contains(name)) {
            injected.computeIfAbsent(name, n -> new SchemaMap.Declaration(List.of(), n, List.of(),
                    instance(normalised, renamed)));
        }
        return new GenericRef(name, parameters.stream()
                .<TypeArg>map(parameter -> new TypeArg.Ref(new SimpleRef(parameter))).toList());
    }

    /**
     * Every parameter of the enclosing declaration this binding record names, in the order the declaration
     * lists them -- the form's own parameter list, and the argument list of the reference that replaces it.
     *
     * <p><b>Asked of the resolved record, not of the source form</b>, so a parameter reaches it the same way
     * whichever channel carried it: {@code [T]} names one in a type slot and {@code [order; N]} in a value
     * slot, and §5.3's size specifier keeps its bound as raw token text precisely because it may be either.
     */
    private List<String> parametersIn(Binding binding) {
        List<String> found = new ArrayList<>();
        for (String parameter : currentParameters) {
            if (binding.fields().stream().anyMatch(field -> namesToken(field.value().value().coreValue(),
                    parameter))) {
                found.add(parameter);
            }
        }
        return found;
    }

    /** Whether {@code value}, or anything nested inside it, is the bare token {@code text}. */
    private static boolean namesToken(CoreValue value, String text) {
        return switch (value) {
            case TokenValue token -> token.text().equals(text);
            case ArrayValue array -> array.elements().stream()
                    .anyMatch(element -> namesToken(element.value().coreValue(), text));
            case RecordValue record -> record.fields().stream()
                    .anyMatch(field -> namesToken(field.value().value().coreValue(), text));
            default -> false;
        };
    }

    /**
     * The names an open form's own parameters take: {@code p0}, {@code p1}, ... positionally.
     *
     * <p><b>Renaming is what makes an open entry identify with its equals.</b> Two forms alike up to a
     * consistent renaming of parameters are one template (§8.2), so {@code <T> [T]} and {@code <A> [A]} have
     * to land on one entry -- and the name is derived from the record, so normalising the record is what
     * normalises the name.
     *
     * <p>The prefix grows until it collides with nothing the record already names. A binding may hold a
     * concrete reference to a type genuinely called {@code p0}, and renaming a parameter on top of it would
     * make the two indistinguishable in the body that results.
     */
    private static List<String> positionalNames(Binding binding, List<String> parameters) {
        String prefix = "p";
        while (true) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < parameters.size(); i++) {
                names.add(prefix + i);
            }
            boolean clash = names.stream().anyMatch(name -> !parameters.contains(name)
                    && binding.fields().stream().anyMatch(field ->
                            namesToken(field.value().value().coreValue(), name)));
            if (!clash) {
                return names;
            }
            prefix += "p";
        }
    }

    /** The same binding record with each parameter token replaced by its positional name. */
    private static Binding rename(Binding binding, List<String> parameters, List<String> renamed) {
        Map<String, String> substitution = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            substitution.put(parameters.get(i), renamed.get(i));
        }
        Map<String, TypeRef> applications = new LinkedHashMap<>();
        binding.applicationSlots().forEach((slot, ref) -> applications.put(slot, renameRef(ref, substitution)));
        return new Binding(binding.head(), binding.fields().stream()
                .map(field -> new RecordValue.Field(field.name(), renameScoped(field.value(), substitution)))
                .toList(), applications);
    }

    /** An application's own arguments renamed alongside the wire record beside it, so the two stay in step. */
    private static TypeRef renameRef(TypeRef ref, Map<String, String> substitution) {
        if (ref instanceof SimpleRef simple) {
            return substitution.containsKey(simple.name()) ? new SimpleRef(substitution.get(simple.name()))
                    : simple;
        }
        GenericRef generic = (GenericRef) ref;
        return new GenericRef(generic.name(), generic.args().stream().map(argument ->
                argument instanceof TypeArg.Ref reference
                        ? (TypeArg) new TypeArg.Ref(renameRef(reference.ref(), substitution))
                        : argument).toList());
    }

    private static ScopedValue renameScoped(ScopedValue scoped, Map<String, String> substitution) {
        DataValue value = scoped.value();
        return new ScopedValue(scoped.schemaRef(), new DataValue(value.annotations(), value.typeRef(),
                renameValue(value.coreValue(), substitution)));
    }

    private static CoreValue renameValue(CoreValue value, Map<String, String> substitution) {
        return switch (value) {
            case TokenValue token -> substitution.containsKey(token.text())
                    ? new TokenValue(substitution.get(token.text()), token.form())
                    : token;
            case ArrayValue array -> new ArrayValue(array.elements().stream()
                    .map(element -> renameScoped(element, substitution)).toList());
            case RecordValue record -> new RecordValue(record.fields().stream()
                    .map(field -> new RecordValue.Field(field.name(), renameScoped(field.value(), substitution)))
                    .toList());
            default -> value;
        };
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
        return scoped(value, List.of());
    }

    /**
     * The same, carrying the annotations written on the construct it stands for. §6 puts a field's own
     * annotations on the {@code record_field} in resolver output, and a held body reaches that through the
     * wire value, so they travel here rather than being re-attached after the fact.
     */
    private static ScopedValue scoped(CoreValue value, List<Annotation> annotations) {
        return new ScopedValue(Optional.empty(), new DataValue(annotations, Optional.empty(), value));
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
        return internalName(binding.head(), binding.fields());
    }

    /**
     * The same derivation, over a binding record built elsewhere -- {@code TemplateMaterialiser}, closing an
     * open form into the concrete one it always described.
     *
     * <p><b>Sharing the function is what makes the two channels dedupe against each other</b> (§8.2). A form
     * written directly and the same form arriving through a materialised template are one type, so they must
     * be one entry, and the only way that holds is for one function of one record to name both.
     */
    static String internalName(String head, List<RecordValue.Field> fields) {
        Binding binding = new Binding(head, fields);
        StringBuilder readable = new StringBuilder(head);
        for (RecordValue.Field field : fields) {
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
