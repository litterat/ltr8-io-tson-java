package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.KernelNames;
import io.ltr8.tson.base.*;
import io.ltr8.tson.base.WriteException;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.ast.*;
import io.ltr8.tson.compiler.ast.VoidValue;
import io.ltr8.tson.compiler.ast.schema.ApplicationRef;
import io.ltr8.tson.compiler.ast.schema.ArrayExpr;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.ChoiceExpr;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.DependentType;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.FieldTypeExpr;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.InlineConstruction;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.MapExpr;
import io.ltr8.tson.compiler.ast.schema.NameRef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RecordEntry;

import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.RemovalSet;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SchemaMapDef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TupleExpr;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeExpr;
import io.ltr8.tson.compiler.SchemaPositions;
import io.ltr8.tson.compiler.writer.DataClassObjectWriter;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.EntryKind;
import io.ltr8.tson.schema.meta.FieldGroup;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.OpenEntry;
import io.ltr8.tson.schema.meta.Product;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordExtensionType;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Sum;
import io.ltr8.tson.schema.meta.Param;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.TypeChain;
import io.ltr8.annotation.Annotation;
import io.ltr8.annotation.Annotations;
import io.ltr8.tson.schema.TsonSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Resolves one declaration from a {@link SchemaMap} (the grammar-layer AST) into the value its entry maps to
 * ([TSON-SCHEMA] §4.1, §8.1) -- a type body carrying its own IS-A chain (an open record's included), an {@code
 * Alias} or a constructor's instance -- and records the entry's facts that no value holds
 * ({@link TsonSchema.EntryFacts}). Per-declaration work only: ordering, imports, materialisation and everything else
 * that needs the whole schema is {@link SchemaResolver}'s. {@link #resolveTypeDef} dispatches on the shape of
 * the declaration, every sugar form having been rewritten by {@link SchemaDesugarer} first:
 *
 * <ul>
 *   <li><b>A record</b> (§5.2) -- fields in any of the six field states, a {@code ~}/{@code =} value read by
 *   {@link #resolveField}, and field groups (§5.11).</li>
 *   <li><b>Composition</b> ({@code A & B & { ... }}, §5.8, {@link #resolveComposition}) -- each supertype's
 *   fields and groups absorbed left to right, a body entry naming an inherited field being a
 *   <em>tightening</em> entry (§5.7, {@link #resolveTighteningField}) that replaces it in place, checked
 *   against §5.7's transition table ({@link #refines}), with an elided type-ref inheriting
 *   the source field's type. A supertype may be an application of an open record, absorbed by value whether its
 *   arguments are concrete or the declaration's own parameters ({@link #openOperand}).</li>
 *   <li><b>Refinement</b> ({@code source ^ { ... }}, §5.7, {@link #resolveRefinement}) -- copies the source's
 *   whole field set and admits no new fields: every body entry tightens an inherited field or restates an
 *   inherited group ({@link #restatesInheritedGroup}: same member labels in the same order, state tightening
 *   OPTIONAL&#8594;REQUIRED only). A closed source heads the result's chain. An application of an open record
 *   as the source is absorbed on composition's terms.</li>
 *   <li><b>An alias</b> ({@code name => other_name}, §8.3) -- an {@code ALIAS}-kind entry whatever the
 *   name resolves to, carried unverified; the linker validates it. <b>An application</b>
 *   ({@code bx => box<text>}, {@link #resolveTemplateApplication}) is the same declaration carrying
 *   arguments, each a reference, nested applications included: over an open record it closes <em>into</em> the
 *   declared name, and over a constructor it is the construction it spells.</li>
 *   <li><b>Constructor application</b> ({@code !C value}, §5.5/§5.6, {@link #resolveInstance}) -- bound
 *   through the compiled reader of the schema declaring {@code C} ({@link #bindAtomInstance}), with no
 *   name-to-class table --
 *   and <b>atom refinement</b> ({@code !I ^ { ... }}, §5.5, {@link #resolveAtomRefinement}), which merges
 *   its bindings with its source on the wire and is checked to narrow ({@link #checkNarrows}).</li>
 *   <li><b>A schema map nested as an entry</b> ({@code geo => !schema { ... }}) -- a value of {@code schema}
 *   whose members are declarations of the document under their paths, each resolved on its own; its namespace is
 *   built from them wherever the schema is ({@code SchemaPaths.synced}).</li>
 * </ul>
 *
 * <p><b>A declaration carrying type parameters (§5.10) is an open record</b>: a {@code meta.record} carrying
 * {@code parameters}, read at its declaration against {@code record}'s own vocabulary as any record is -- a fresh
 * record, a composition or a refinement built over the builder's own fields ({@link Built}), or the explicit {@code
 * <T> !meta.record { … }} bound through {@code record}'s reader ({@link #openInstance}). Inside it a name its
 * parameters declare is a parameter, which {@link RecordBody} holds as a {@code ParamRef}. Any other head under a
 * parameter list is refused ({@link #openInstance}). Nothing is substituted here -- that is {@code
 * TemplateMaterialiser}'s, over the resolved form.
 *
 * <p><b>The exception types are the classification.</b> A {@link io.ltr8.tson.base.SchemaValidationException}
 * says the author's schema is wrong and the spec says so; an {@link UnsupportedOperationException} says this
 * library could not do something, and travels as {@code NOT_IMPLEMENTED}; an {@link IllegalStateException}
 * says an internal invariant broke -- a container sugar form reaching resolution unrewritten ({@link #unlifted}),
 * which only a caller that skipped {@link SchemaDesugarer} can produce, or a resolved value no well-formed
 * pipeline builds. The test for which is which: <b>a schema error's verdict does not change
 * when this library improves; a gap's does.</b> Misfiling an author error as a gap tells the author their
 * correct understanding of the spec is this library's fault. Schema errors include: an atom refinement that
 * loosens its source ({@link #checkNarrows}); a name in a {@code !} position that resolves nowhere in the
 * namespace ({@link #resolveConstructorTarget}, {@link #resolveAtomRefinement}); a {@code !} form used
 * against the wrong kind of target -- refining a constructor, applying a non-constructor, refining a
 * non-atom -- each answered with the form the author probably meant; a supertype chain reaching two base
 * kinds ({@link #determineKind}); and a body or annotation value the declaring schema's compiled reader
 * rejects, which arrives as a {@link ReadException} and is restated as a schema error
 * ({@link #bodyIsNotValidData}).
 *
 * <p><b>No construct of the schema grammar is a gap.</b> Every {@code UnsupportedOperationException} this
 * class throws is one of:
 * <ul>
 *   <li><b>A {@code RuntimeException} from the compiled vocabulary reader that is none of the classified ones</b>
 *   -- binding a constructor's payload ({@link #bindAtomInstance}) or an annotation's value
 *   ({@link #bindAnnotationValue}). A {@code ReadException} or {@code SchemaValidationException} there is
 *   the author's and a {@code BindMismatchException} the deployment's, each rethrown as itself; what is left
 *   is unexplained, and a gap is the classification that claims least.</li>
  *   <li><b>A failed write round-trip</b> -- re-serialising an atom refinement's source
 *   ({@link #sourceSerializedFields}). The value was bound by this library, so failing to write it back is
 *   no verdict on the schema.</li>
 *   <li><b>A closed application at a refinement source with no {@link ApplicationCloser}</b> ({@link
 *   #closedApplication}) -- a resolver built standalone, without the whole-schema materialiser {@link
 *   SchemaResolver} supplies.</li>
 * </ul>
 * {@link #resolveTemplateApplication} and {@link #closedApplication} also rethrow one from below with the
 * declaration's name in front. What §5.7 leaves unchecked is not reported at all: a restated FIXED field
 * MUST NOT change its pinned value, and {@link #resolveTighteningField} does not compare the two.
 *
 * <p>Declarations resolve in one namespace per schema (§3.3, §3.3.1): the schema's own entries and its
 * import closure. It reaches this class as two getters over that one
 * namespace, required constructor parameters fixed for as long as this resolver is used:
 * <ul>
 *   <li>{@code namespaceDefinitions} -- the whole namespace, consulted by composition's supertype lookup
 *   (§5.8), refinement's source lookup (§5.7), and atom refinement's source lookup (§5.5). Never populated
 *   by this class: the caller supplies a {@link DefinitionGetter} over a map it owns. A supertype or source
 *   must be answerable when asked for, so a caller wanting forward references resolves on demand, as
 *   {@link SchemaResolver} does.</li>
 *   <li>{@code vocabularyDefinitions} -- where a constructor-application target ({@link #resolveConstructorTarget}) and
 *   an annotation's name resolve, both read through a compiled reader. For every schema but the kernel it is the
 *   whole namespace too, made ready in wait order ({@link WaitOrder}): a head or an annotation type declared in the
 *   same document is made ready before the value read against it. The kernel's ordinary resolution resolves them
 *   among its pre-loaded stand-in's entries instead, the one cycle being closed by pre-loading ([TSON-SCHEMA]
 *   §3.4).</li>
 * </ul>
 * Either getter can be an always-{@code null} lookup for a resolver that never needs it.
 *
 * <p><b>Kind determination</b> (§4.1, {@link #determineKind}) checks the transitive supertype chain for the
 * literal, kernel-fixed names {@code atom}/{@code product}/{@code sum}/{@code namespace} -- not a general
 * "inherit the nearest ancestor's own kind" rule, which would be wrong: {@code atom} the entry is itself
 * {@code kind: PRODUCT}, its own chain being {@code [top type]}. None found is {@code PRODUCT} (the
 * structural default), exactly one is that kind, and two or more is a schema error. A fresh (non-composed)
 * record has an empty chain by construction, so it is always {@code PRODUCT}. The kind is not stored: it is
 * the value's ({@code EntryKind.of}), read from the same chain.
 *
 * <p><b>A chain is the resolver's to compute, never the author's to write</b> ([TSON-SCHEMA] §4.1): a
 * construction or an atom refinement stating {@code supertypes} or {@code subtypes} is refused
 * ({@link #refuseWrittenChain}), as stating {@code choice.disjoint} would be. In a closed body {@code supertypes}
 * is the complete chain; in an open record it is the parents as written, which closing completes.
 *
 * <p><b>Field groups (§5.11) flatten</b>: each member becomes an ordinary {@link RecordField} in
 * source position, optional and voidable, whether or not the group is optional (a group that is not
 * still means each <em>member</em> is individually optional, since an option is guaranteed, not which), and
 * the group itself is recorded as a {@link FieldGroup}, {@code optional} from the group's own {@code ?}. A composed
 * supertype's groups are inherited whole, in supertype order, ahead of the body's own.
 *
 * <p><b>{@code subtypes} is never populated here</b> -- it is a reverse index over the whole namespace (who
 * lists me as a supertype, transitively), imports included, so {@code TsonSchemaLinker} computes it.
 *
 * <p>Note the two reference models in play: this class imports the grammar layer's ({@link TypeExpr}, and the
 * references {@link NameRef} and {@link ApplicationRef}) for reading the AST, and refers to the
 * resolved model's ({@code io.ltr8.tson.schema.meta.TypeRef} and its variants, the references it produces) by
 * fully-qualified name -- the two layers name their references alike, so only one can be the unqualified import.
 *
 * <p>Package-private, no {@code Tson} prefix -- internal machinery a consumer of this library never names.
 * {@link SchemaResolver} is the document-level counterpart: it validates a document's header directives,
 * merges every {@code !!import}'s entries, and holds one instance of this class to
 * do the per-declaration work. This class takes a bare declaration and never sees a {@code SchemaDocument}.
 *
 * <p>Has no dependency on {@code reader} -- binding goes through {@link ConstructionReader} (a required
 * constructor parameter), a narrow read contract rather than the full {@code TsonCompiledSchema}.
 */
final class DefinitionResolver {


    /** {@code type}'s one field ([TSON-SCHEMA] §4.1): a type's IS-A chain, which no declaration writes. */
    private static final String CHAIN = "chain";

    /** The {@code _} a bare annotation stands for (§6), read against the annotation's type like a written one. */
    private static final DataValue VOID = new DataValue(List.of(), Optional.empty(), new VoidValue());

    /**
     * Re-serializes an atom refinement's source back to wire form for {@link #mergeWithSource} -- see
     * {@link #resolveAtomRefinement}. Structural, not incidental: the merge has to happen on the wire
     * record, so this is the only way to get the source's already-bound facets back into one.
     */
    private final DataClassObjectWriter writer = new DataClassObjectWriter();

    private final ConstructionReader constructionReader;
    private final AnnotationValueReader annotationValueReader;
    private final DefinitionGetter vocabularyDefinitions;
    private final DefinitionGetter namespaceDefinitions;

    /**
     * Whether a name in this namespace denotes the kernel's own entry -- what a kind and a constructor are
     * judged by ([TSON-SCHEMA] §4.1), so a schema's own {@code namespace} or {@code top} is an ordinary type whatever
     * it is spelled.
     */
    private final Predicate<String> kernel;

    /** {@link #kernel} for {@code vocabularyDefinitions}, where every construction head resolves (§3.3.1). */
    private final Predicate<String> vocabularyKernel;

    /**
     * Whether this resolver can bind annotation values at all -- false only for a caller with no compiled
     * reader to read one through (the kernel bootstrap, which is producing the very entries such
     * a reader would need). It is what separates "this annotation's type is out of reach here" from "this
     * annotation's type does not resolve", the second of which is the author's error; see
     * {@link #bindAnnotationValue}.
     */
    private final boolean annotationsResolve;

    /**
     * The materialiser's on-demand half, for what a declaration needs of an application while it resolves, before
     * the rest of §5.10 materialisation runs as a pass afterwards: a declaration naming an application closes into
     * its own name ({@link ApplicationCloser#closeApplicationInto}), and a refinement source whose head is no open
     * record is handed over ({@link #closedApplication}). Everywhere else an application is carried as a reference
     * with arguments and closed by that pass.
     *
     * <p>Defaults to refusing, for the callers that resolve a declaration outside a whole-schema pass (the
     * kernel bootstrap, and unit tests) -- there is no materialiser in those.
     */
    private final ApplicationCloser applicationCloser;

    /**
     * Where each field of this document was written, for the position a resolved {@link RecordField} carries.
     * Empty for a resolver with no source text behind it (the bootstrap, a hand-built document), which is
     * why a field's position is {@code Optional} rather than assumed.
     */
    private final SchemaPositions positions;

    /**
     * Each entry's facts as resolution learns them ({@link TsonSchema.EntryFacts}) -- where it was declared, the
     * annotations on its value, the constructor its value applies. {@code SchemaResolver}'s own map, holding the
     * imported entries' facts as well, so an atom refinement finds the constructor of a source another schema
     * declared.
     */
    private final Map<String, TsonSchema.EntryFacts> facts;

    /** The judgements left for after every entry is ready ({@link #judgeDeferred}). */
    private final List<Deferred> deferred = new ArrayList<>();

    /**
     * No annotation reader: an annotation's name is kept, its value is out of reach, and no name is checked
     * against the imported entries. See {@link AnnotationValueReader}.
     */
    DefinitionResolver(ConstructionReader constructionReader, DefinitionGetter vocabularyDefinitions,
                       DefinitionGetter namespaceDefinitions) {
        this(constructionReader, null, vocabularyDefinitions, namespaceDefinitions, null,
                SchemaPositions.none(), new LinkedHashMap<>(), name -> true, name -> true);
    }

    DefinitionResolver(ConstructionReader constructionReader, AnnotationValueReader annotationValueReader,
                       DefinitionGetter vocabularyDefinitions, DefinitionGetter namespaceDefinitions,
                       ApplicationCloser applicationCloser, SchemaPositions positions,
                       Map<String, TsonSchema.EntryFacts> facts, Predicate<String> kernel,
                       Predicate<String> vocabularyKernel) {
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.vocabularyKernel = Objects.requireNonNull(vocabularyKernel, "vocabularyKernel");
        this.positions = Objects.requireNonNull(positions, "positions");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.constructionReader = Objects.requireNonNull(constructionReader, "constructionReader");
        this.annotationsResolve = annotationValueReader != null;
        this.annotationValueReader = annotationValueReader == null ? (type, value) -> null : annotationValueReader;
        this.vocabularyDefinitions = Objects.requireNonNull(vocabularyDefinitions, "vocabularyDefinitions");
        this.namespaceDefinitions = Objects.requireNonNull(namespaceDefinitions, "namespaceDefinitions");
        this.applicationCloser = applicationCloser;
    }

    /**
     * Resolves a single declaration against this instance's namespace --
     * the sole entry point; every other {@code resolve*} method is a private dispatch target reached
     * from here. Delegates to {@link #resolve(SchemaMap.Declaration, Optional)} with no position,
     * for a caller with no {@code TsonSchemaParser}-produced position table to hand back -- every
     * hand-built test fixture among them.
     */
    Top resolve(SchemaMap.Declaration declaration) {
        return resolve(declaration, Optional.empty());
    }

    /**
     * Same as {@link #resolve(SchemaMap.Declaration)}, recording {@code declarationPosition} (typically looked
     * up by the caller in {@code TsonSchemaParser#declarationPositions()} for this exact {@code declaration})
     * and the annotations written on the value among the entry's facts -- "where was this declared" is a
     * property of the declaration itself, so it is recorded uniformly here at the one place every resolution
     * path already funnels through, regardless of which internal {@code resolve*} method built the value.
     */
    Top resolve(SchemaMap.Declaration declaration, Optional<SourcePosition> declarationPosition) {
        Top resolved = withBounds(declaration,
                withExtension(declaration, resolveTypeDef(declaration.name(), declaration.typeDef())));
        Annotations annotations = annotationsOf(declaration.name(), declaration.typeDefAnnotations());
        TsonSchema.EntryFacts known = factsOf(declaration.name());
        facts.put(declaration.name(), known.withPosition(declarationPosition.or(known::position))
                .withAnnotations(annotations));
        return resolved;
    }

    /**
     * An open entry with each parameter carrying the bound its declaration wrote ({@code <T: text>}, §5.10), the
     * others unbounded: a parameter is its name and its written bound, known in full where it is declared, and
     * nothing about it is inferred. Whether a bound names a type, and whether a parameter passed on is written with a
     * bound its callee admits, are judged once every declaration is ready ({@link ParameterTypes#check}).
     */
    private Top withBounds(SchemaMap.Declaration declaration, Top resolved) {
        if (declaration.parameterTypes().isEmpty() || !OpenEntry.isOpen(resolved)) {
            return resolved;
        }
        Map<String, Param> bounded = new LinkedHashMap<>();
        declaration.parameterTypes().forEach((parameter, written) -> bounded.put(parameter,
                new Param(parameter, Optional.of(parameterType(parameter, written)))));
        return OpenEntry.withParameters(resolved, bounded);
    }

    /** {@code name}'s facts as resolution has recorded them so far. */
    TsonSchema.EntryFacts factsOf(String name) {
        return facts.getOrDefault(name, TsonSchema.EntryFacts.NONE);
    }

    /** Records that {@code name}'s value applies {@code constructor} -- the tag resolved output writes. */
    private void constructedBy(String name, String constructor) {
        facts.put(name, factsOf(name).withConstructor(Optional.of(constructor)));
    }

    /**
     * A declaration's own {@code abstract}/{@code final} mark lowered into {@code record.extension} ({@link
     * DefinitionMarks}), and ABSTRACT derived where the body names selectors -- the resolved definition
     * unchanged where neither applies, every record being OPEN by default.
     *
     * <p><b>Applied here rather than where the body is built</b>, because every route to a record body reaches
     * this method and none of them owns the mark: a fresh record, a composition and a refinement each build
     * their own {@code RecordBody}, and a mark read three times is a mark two of them can disagree about. It
     * is also what makes the rule that extensibility is never inherited fall out rather than need stating --
     * a composition's body is built OPEN from its operands and the mark, if any, is this declaration's own.
     *
     * <p><b>On an open record the mark and the selectors are each application's</b> (§5.10): the template is
     * no type, and closing carries its {@code extension} and {@code discriminators} to the record each application
     * denotes, so {@code result => abstract <T> { ok: boolean =? }} makes {@code result<text>} an abstract base over
     * the declarations composing it, and a selector makes a template ABSTRACT as it makes a record so. {@code
     * final} is refused on a template: a template is applied to be composed, each member of a family composing an
     * application of it, and the mark would forbid that of every application. A final type is a declaration of the
     * application, marked as itself.
     *
     * <p>A mark on anything but a record is the author's error.
     */
    private Top withExtension(SchemaMap.Declaration declaration, Top resolved) {
        Optional<RecordExtensionType> extension = DefinitionMarks.extension(declaration.mark());
        boolean open = OpenEntry.isOpen(resolved);
        if (open && extension.orElse(null) == RecordExtensionType.FINAL) {
            throw new SchemaValidationException("'" + declaration.name() + "': 'final' is refused on a template"
                    + " -- a template is applied to be composed, each member of a family composing an application"
                    + " of it, and the mark would forbid that of every application. Declare the application and mark"
                    + " that declaration instead ([TSON-SCHEMA] §5.2, §5.10)");
        }
        boolean selectors = resolved instanceof RecordBody body && !body.discriminators().isEmpty();
        if (selectors) {
            // A selector says the members pin it, so the record is the base they are selected from and has
            // no values of its own -- ABSTRACT, derived. `abstract` beside it asserts what the body already
            // says and is admitted; `final` claims the opposite and is refused.
            if (extension.orElse(RecordExtensionType.ABSTRACT) != RecordExtensionType.ABSTRACT) {
                throw new SchemaValidationException("'" + declaration.name() + "': 'final' says nothing may"
                        + " extend this record, and a field written '=?' says its members pin that field --"
                        + " so the members the selector selects could never exist ([TSON-SCHEMA] §5.2)");
            }
            extension = Optional.of(RecordExtensionType.ABSTRACT);
        }
        if (extension.isEmpty()) {
            return resolved;
        }
        if (!(resolved instanceof RecordBody record)) {
            throw new SchemaValidationException("'" + declaration.name()
                    + "': only a record states how it may be realised -- this entry's body is "
                    + resolved.getClass().getSimpleName() + " ([TSON-SCHEMA] §5.2)");
        }
        return record.withExtension(extension.get());
    }

    /**
     * One list of written annotations, bound. A declaration has two such lists and §6 keeps them apart: those
     * written <em>after</em> {@code =>} annotate the value and ride the entry's facts; those
     * written before the name annotate the <em>name</em>, and a resolver "does not hoist annotations from key
     * to value", so {@link SchemaResolver} binds them through this same method and places them on the output
     * schema-map <em>key</em> (§8.1), beside the derived {@code @meta.application}.
     *
     * <p>A value is bound the same way §6 describes reading one: the annotation's name resolves in the schema's one
     * namespace (§3.3.3), and its value is read by that type's own compiled reader -- so {@code
     * @doc:"..."} arrives as a {@code String}. <b>A name that does not resolve there is the author's error</b>
     * ({@link #unresolvedAnnotation}), not a silently valueless annotation: an annotation whose type is unreachable
     * has no contract to validate its value against. A type this document declares itself is made ready before its
     * value is read against it, in wait order.
     *
     * <p>The check is skipped entirely by a resolver constructed with no {@link AnnotationValueReader}: there
     * is no compiled reader to resolve against, so every name would fail it. That is the kernel
     * bootstrap, which is producing the very entries such a reader would read through; there the annotation's
     * name is kept and its value dropped, {@code schema.meta} being a pure value model with no unbound form
     * to hold one in, and dropping the name too would lose more ([TSON-DATA] §1.5).
     */
    Annotations annotationsFor(String name, List<io.ltr8.tson.compiler.ast.Annotation> written) {
        return annotationsOf(name, written);
    }

    private Annotations annotationsOf(String name, List<io.ltr8.tson.compiler.ast.Annotation> written) {
        if (written.isEmpty()) {
            return Annotations.empty();
        }
        Annotations.Builder annotations = new Annotations.Builder();
        for (io.ltr8.tson.compiler.ast.Annotation annotation : written) {
            // §6's bare `@T` is shorthand for `@T:_`, so both forms name a type and both are read against it: a
            // marker whose type nothing can reach is as unresolved as a valued one, and a bare mark whose type
            // is not void is refused as `_` would be.
            if (annotationsResolve && vocabularyDefinitions.definition(annotation.name()) == null) {
                throw unresolvedAnnotation(name, annotation.name());
            }
            refuseWrittenApplication(name, annotation.name());
            if (annotation.value().isEmpty()) {
                bindAnnotationValue(name, annotation.name(), VOID);
            }
            annotations.add(new Annotation(annotation.name(), annotation.value().flatMap(
                    value -> Optional.ofNullable(bindAnnotationValue(name, annotation.name(), value)))));
        }
        return annotations.build();
    }

    /**
     * §3.3.3 missed: {@code annotationName} is no entry of this schema's namespace, its own entries and its imports.
     */
    private static SchemaValidationException unresolvedAnnotation(String declaration, String annotationName) {
        return new SchemaValidationException("'" + declaration + "': '@" + annotationName + "' does not name "
                + "a type in this schema's namespace -- its own entries and every entry its !!imports bring in "
                + "(§3.3.3)");
    }

    /** An annotation's value through the type its name refers to, or {@code null} when that type is out of reach. */
    private Object bindAnnotationValue(String declaration, String annotationName, DataValue value) {
        if (vocabularyDefinitions.definition(annotationName) == null) {
            return null;
        }
        try {
            return annotationValueReader.read(annotationName, value);
        } catch (ReadException e) {
            // Same split as bindAtomInstance, for the same reason: an annotation value that does not conform
            // to the type its name refers to (§6) is the author's error, and relabelling it a coverage gap
            // aborts the run over a typo.
            throw payloadFailure("'" + declaration + "': the value of annotation '@" + annotationName + "'",
                    "'" + declaration + "': the value of annotation '@" + annotationName + "' is not valid data "
                            + "for the type '" + annotationName + "' names -- " + e.getMessage(), e);
        } catch (BindMismatchException e) {
            // The same arm {@link #bindAtomInstance} carries, for the same reason and it is not a stylistic
            // echo: an annotation naming a type the consumer never bound is their configuration, and
            // `MissingBindingException` exists precisely so that a missing line of wiring does not read as "this
            // library cannot do that", which is what the catch-all below would make of it.
            String where = "'" + declaration + "': " + e.getMessage();
            throw e instanceof MissingBindingException ? new MissingBindingException(where)
                    : new BindMismatchException(where);
        } catch (SchemaValidationException e) {
            // A verdict reached while the value was read -- the annotation's type waiting on an entry that waits
            // back, a wait chain past the limit -- is the schema's, whatever read reached it.
            throw e;
        } catch (RuntimeException e) {
            throw new UnsupportedOperationException("'" + declaration + "': failed to bind the value of "
                    + "annotation '@" + annotationName + "' via the compiled vocabulary reader: "
                    + e.getMessage(), e);
        }
    }

    private Top resolveTypeDef(String name, TypeDef typeDef) {
        return switch (typeDef) {
            case StructuralTypeDef structural -> {
                List<String> parameters = structural.typeParams();
                yield (switch (structural.body()) {
                    case RecordDef recordDef -> resolveRecordBody(recordDef.entries(), parameters);
                    case ConstructionDef construction -> resolveComposition(name, construction, parameters);
                    case RefinedDef refined -> resolveRefinement(name, refined, parameters);
                }).finish(parameters);
            }
            case ReferenceTypeDef referenceTypeDef -> {
                // A parameter list before a reference -- an alias template -- is refused by SchemaDesugarer
                // (§5.10: a template's body is a record), so one here is that phase not having run.
                if (!referenceTypeDef.typeParams().isEmpty()) {
                    throw new IllegalStateException("'" + name + "': a parameter list before a reference reached "
                            + "resolution -- SchemaDesugarer refuses one (§5.10), so this declaration was resolved "
                            + "from raw AST without that phase");
                }
                yield switch (referenceTypeDef.ref()) {
                    case NameRef simple -> new io.ltr8.tson.schema.meta.Alias(
                            io.ltr8.tson.schema.meta.TypeRef.of(simple.name()));
                    case ApplicationRef generic -> resolveTemplateApplication(name, generic);
                    // A declaration-level container form is rewritten by SchemaDesugarer before resolution into the
                    // `!meta.array { ... }` (or map, tuple, choice) construction it denotes (§5.3), and a construction
                    // in place stands only at a field's type -- so one here is that phase not having run.
                    case ArrayExpr _, TupleExpr _, MapExpr _, ChoiceExpr _, InlineConstruction _ ->
                            throw unlifted("'" + name + "'", referenceTypeDef.ref());
                };
            }
            // A parameter list admits only an open record's explicit spelling, `<T> !meta.record { ... }` (§5.10).
            case Instance instance -> instance.typeParams().isEmpty() ? resolveInstance(name, instance)
                    : openInstance(name, instance);
            case AtomRefinement refinement -> resolveAtomRefinement(name, refinement);
            // A schema map nested as an entry: a value of `schema` whose members are declarations of this document
            // under their paths, each resolved as any entry is. Its namespace is built from them wherever the
            // schema is (`SchemaPaths.synced`), so it holds none here and waits on none.
            case SchemaMapDef ignored -> new ValueEntry(TsonSchema.SCHEMA,
                    Namespace.of(TsonSchema.SCHEMA, Map.<String, Top>of()));
        };
    }

    /**
     * A sugar form that reached resolution, or a construction in place off a field's type. {@link
     * SchemaDesugarer} rewrites every sugar form -- into the construction a declaration's body is, into a
     * construction in place at a field's type, or into the application it spells over a parameter -- and refuses
     * one as an argument, so no schema an author writes can produce one here: it is a caller resolving raw AST
     * without that phase, an invariant of the pipeline rather than a gap in it.
     */
    private static IllegalStateException unlifted(String where, FieldTypeExpr ref) {
        return new IllegalStateException(where + ": a sugar form reached resolution where it is not "
                + "rewritten: " + ref + " -- SchemaDesugarer rewrites every sugar form before DefinitionResolver runs "
                + "(§5.3), so this declaration was resolved from raw AST without that phase");
    }

    /**
     * A record body as resolution builds it -- a fresh record, a composition or a refinement, flattened -- before it
     * is the record the declaration is. Its fields are {@link BuiltField}s, the builder's own working value.
     */
    private record Built(TypeChain chain, SequencedMap<String, BuiltField> fields, List<FieldGroup> groups,
                         List<String> discriminators, Optional<String> nameType) {

        /**
         * The record, carrying {@code parameters} where the declaration declares them -- an open record, its chain
         * the parents as written and the closed entries they reach, closing completing each application's from the
         * same parents; and the two rules an open record's fields answer to checked against its parameters
         * ({@link #checked}).
         */
        Top finish(List<String> parameters) {
            SequencedMap<String, RecordField> built = new LinkedHashMap<>();
            fields.forEach((name, field) -> built.put(name, field.field()));
            if (parameters.isEmpty()) {
                return new RecordBody(chain, RecordBody.fieldMap(built), groups, RecordExtensionType.OPEN,
                        discriminators, nameType);
            }
            return checked(new RecordBody(chain.withSubtypes(List.of()), typeParameters(parameters),
                    RecordBody.fieldMap(built), groups, RecordExtensionType.OPEN, discriminators, nameType));
        }
    }

    /**
     * One field as resolution builds it: a field's four facts. A parameter in its type is a name the enclosing
     * record's parameters declare, which the record holds as its parameter when it is built ({@link RecordBody}).
     */
    private record BuiltField(io.ltr8.tson.schema.meta.TypeExpr type, boolean optional, boolean voidable,
                              FieldRole role, Optional<Token> value, Annotations annotations,
                              Optional<SourcePosition> position) {

        /** A source's field: its facts as they are. */
        static BuiltField of(RecordField field) {
            return new BuiltField(field.type(), field.optional(), field.voidable(), field.role(), field.value(),
                    field.annotations(), field.position());
        }

        /**
         * This field restated by a modifier-only entry that elides its type: the type kept, the facts and the value the
         * entry's own.
         */
        BuiltField restated(boolean optional, boolean voidable, FieldRole role, Optional<Token> value,
                            Optional<SourcePosition> position) {
            return new BuiltField(type, optional, voidable, role, value, Annotations.empty(), position);
        }

        /** The record field this is. */
        RecordField field() {
            return new RecordField(type, optional, voidable, role, value, annotations, position);
        }

        BuiltField withFacts(boolean optional, boolean voidable, FieldRole role) {
            return new BuiltField(type, optional, voidable, role, value, annotations, position);
        }

        BuiltField withAnnotations(Annotations annotations) {
            return new BuiltField(type, optional, voidable, role, value, annotations, position);
        }

        /** What a document that never writes the field yields, asked of a plain field ({@link RecordField#omitted}). */
        RecordField.Omitted omitted() {
            return RecordField.omitted(optional, value.isPresent(), false);
        }

        /** These facts in a diagnostic's words ({@link RecordField#describe}). */
        String describe() {
            return RecordField.describe(optional, role);
        }

        /**
         * The type as a message names it: the type's name, the parameter standing for it, or the form a type
         * written in place was written as.
         */
        String typeName() {
            return switch (type) {
                case io.ltr8.tson.schema.meta.TypeRef reference -> reference.name();
                case io.ltr8.tson.schema.meta.TypeInline inline -> inline.text();
            };
        }
    }

    // ── Constructor application (§5.5, §5.6) ────────────────────────────────

    /**
     * Refuses a construction whose head is abstract -- {@code top}, {@code type}, a base kind ([TSON-SCHEMA] §4.1),
     * or a meta layer's own abstract record: it has no direct instances (§5.2), so {@code !C { ... }} builds
     * nothing. One sentence, naming what would satisfy it: the instantiable entries IS-A the head among the imported
     * entries, every abstract one among them left out, since naming one would refuse in turn.
     */
    private void refuseAbstractHead(String name, String target, Top head) {
        if (!(head instanceof RecordBody record) || record.extension() != RecordExtensionType.ABSTRACT) {
            return;
        }
        List<String> instantiable = TypeChain.subtypesOf(head).stream()
                .filter(subtype -> isInstantiable(vocabularyDefinitions.definition(subtype)))
                .toList();
        String terminal = AliasChain.terminal(target, vocabularyDefinitions::definition);
        throw new SchemaValidationException("'" + name + "': '!" + target + "' names an abstract entry, which has no "
                + "direct instances (§5.2), so there is nothing for '!" + target + " { ... }' to build -- "
                + (instantiable.isEmpty()
                        ? "and no constructor in scope composes '" + terminal + "'"
                        : "construct one of its instantiable subtypes instead: (" + String.join(" | ", instantiable)
                                + ")"));
    }

    /** Whether a construction may apply {@code definition} directly: a closed entry that is not abstract. */
    private static boolean isInstantiable(Top definition) {
        return definition != null && !OpenEntry.isOpen(definition)
                && !(definition instanceof RecordBody record && record.extension() == RecordExtensionType.ABSTRACT);
    }

    /**
     * Refuses {@code parameters} written in a construction of {@code record}: an open record's parameters are declared
     * by the list before its body, {@code <T> { ... }} or {@code <T> !meta.record { ... }} (§5.10), which is what puts
     * them in scope and what every check §5.10 makes of a template's declaration reads. Written as a binding instead,
     * the record would be open with none of them made. Tested at the end of the head's alias chain (§8.3).
     */
    private static void refuseWrittenParameters(String name, ConstructorHead head, DataValue written) {
        if (head.name().equals(WireForm.RECORD) && written.coreValue() instanceof RecordValue record
                && record.fields().stream().anyMatch(field -> WireForm.PARAMETERS.equals(field.name()))) {
            throw new SchemaValidationException("'" + name + "': '!" + head.name() + "' states 'parameters', which "
                    + "a construction never writes -- an open record's parameters are the list written before its "
                    + "body, '" + name + " => <T> { ... }' or '" + name + " => <T> !" + head.name() + " { ... }' "
                    + "(§5.10)");
        }
    }

    /**
     * {@code !C value} (constructor application, no {@code ^}) -- produces a fresh instance filled
     * with {@code value}.
     *
     * <p>{@code C} resolves in the schema's one namespace, an imported entry (see {@link #resolveConstructorTarget}).
     * It MUST be <b>closed</b>: a template ({@code C} declaring type parameters) closes by application, {@code
     * C<...>}, never by construction, so naming one here is a resolver error whether or not it also carries {@code ~}.
     * Where {@code C} is no constructor -- not IS-A {@code top} (§4.1) -- the entry is a value of the type {@code C}
     * ({@link #resolveValueEntry}).
     *
     * <p>{@code value} is bound via {@link #bindAtomInstance} directly -- {@code instance.value().typeRef()}
     * already names {@code C} (per {@code Instance}'s own reshape, matching §12.1's {@code instance = "!"
     * type-name ws core-value}); positional form (§5.6) and schema-composed defaults (§5.2/§5.7) are handled
     * uniformly by the compiled {@code Record*Reader} itself (see {@code RecordAbstractReader}'s own
     * Javadoc), not by a separate normalization step here. Construction transfers only {@code C}'s {@code
     * kind} (§5.5): no supertypes, no parameters, {@code constructor: false} on the result -- except for an
     * alias, where the result is the entry {@code name => X} denotes, since {@code !meta.alias { target: X }}
     * is that alias written out (§8.3).
     *
     * <p>Binds against {@link Top}, not the narrower {@code Atom} -- some constructors (e.g. {@code
     * unknown_type => ~sum & {}}) compose with {@code sum}, not {@code atom}. {@code C}'s own body is
     * always a {@link RecordBody} by the time it is read, which the two questions above establish rather
     * than check: see the note at the binding site.
     */
    private Top resolveInstance(String name, Instance instance) {
        String target = instance.target();
        ConstructorHead head = resolveConstructorTarget(name, target);
        refuseWrittenParameters(name, head, instance.value());
        Top constructor = head.definition();
        List<String> constructorParameters = OpenEntry.parameterNames(constructor);
        if (!constructorParameters.isEmpty()) {
            int declared = constructorParameters.size();
            throw new SchemaValidationException("'" + name + "': '" + target + "' is a template taking "
                    + declared + " type argument" + (declared == 1 ? "" : "s") + " " + constructorParameters
                    + ", and a template closes by application, not by construction -- write '" + target
                    + "<...>' with its arguments (§5.10). '!" + target + " { ... }' fills a constructor's own "
                    + "vocabulary (§4.2), which is a different operation and needs a closed constructor");
        }
        if (!EntryKind.isConstructor(head.name(), constructor, vocabularyKernel)) {
            return resolveValueEntry(name, target, head, instance.value());
        }
        refuseAbstractHead(name, target, constructor);
        refuseWrittenChain(name, target, instance.value());
        // No check that the head's own body is a record: a chain reaching `top` is built only by composition and
        // refinement, both of which build a `RecordBody` -- an open one refused as a template above. Every other body
        // shape
        // comes with an empty chain, since IS-A stops at construction (§4.1), and so is read as a value entry.
        // Parameters empty and IS-A `top` therefore imply a record body.
        Top body = bindAtomInstance(name, readAs(instance.value(), head.name()));
        if (body instanceof io.ltr8.tson.schema.meta.Alias) {
            // `!meta.alias { target: X }` is the explicit spelling of the alias `name => X` (§8.3), so it
            // denotes the same entry: `kind: ALIAS` (§4.1 -- a meta.type_ref, not one the supertype chain
            // could give). Dispatched on the *body* rather than on the head's name because this path has
            // already read it.
            return body;
        }
        // The author's own spelling of the head is the tag, not the terminal, so the chain stays walkable.
        constructedBy(name, target);
        return body;
    }

    /**
     * {@code name => !T { ... }} where {@code T} is no constructor: a value entry, an instance of the ordinary type
     * {@code T} ([TSON-SCHEMA] §4.1), its payload read against {@code T} as a data value is and held as the value
     * ({@link ValueEntry}). It is no type, and its tag is {@code T}.
     *
     * <p>Refused: a head that is itself a value, since a value has no instances; and a braced payload at an atom
     * type, which reads as no value of it and is almost always an atom refinement missing its {@code ^}.
     */
    private Top resolveValueEntry(String name, String target, ConstructorHead head, DataValue payload) {
        if (head.definition() instanceof ValueEntry value) {
            throw new SchemaValidationException("'" + name + "': '!" + target + "' names a value of the type '"
                    + value.type() + "', and a value has no instances -- '!T { ... }' builds a value of the type T");
        }
        if (head.definition() instanceof Atom && payload.coreValue() instanceof RecordValue) {
            throw new SchemaValidationException("'" + name + "': '!" + target + "' is an atom, and a braced body is "
                    + "no value of it, so there is nothing for '!" + target + " { ... }' to build. Did you mean atom "
                    + "refinement ('!" + target + " ^ { ... }')?");
        }
        Object value;
        try {
            value = constructionReader.readValue(head.name(), readAs(payload, head.name()));
        } catch (ReadException e) {
            String at = e.diagnostic().path().filter(path -> !path.isEmpty()).map(path -> "at " + path + ": ")
                    .orElse("");
            if (e.diagnostic().code() == Diagnostic.Code.NOT_IMPLEMENTED) {
                // A payload this library cannot yet read is its gap, never a verdict on the payload.
                throw new UnsupportedOperationException("'" + name + "': the body cannot yet be read against '"
                        + head.name() + "' -- " + at + e.getMessage(), e);
            }
            throw payloadFailure("'" + name + "': the body", "'" + name + "': the body is no value of the type '"
                    + head.name() + "' -- " + at + e.getMessage(), e);
        } catch (BindMismatchException e) {
            String where = "'" + name + "': " + e.getMessage();
            throw e instanceof MissingBindingException ? new MissingBindingException(where)
                    : new BindMismatchException(where);
        }
        if (value == null) {
            throw new SchemaValidationException("'" + name + "': the body is no value of the type '" + head.name()
                    + "'");
        }
        constructedBy(name, target);
        return new ValueEntry(target, value);
    }

    /**
     * Refuses a construction that writes {@code type}'s {@code chain}: the IS-A chain is the resolver's,
     * computed from what composition and refinement say and from the whole schema map, so a value written for it
     * is a claim no declaration can make -- as {@code choice.disjoint} is derived and never written
     * ([TSON-SCHEMA] §4.1). Read off the binding record as written, before the constructor's reader
     * binds it, so the refusal names the field rather than a value that bound.
     */
    private static void refuseWrittenChain(String name, String target, DataValue written) {
        if (!(written.coreValue() instanceof RecordValue record)) {
            return;
        }
        for (RecordValue.Field field : record.fields()) {
            if (CHAIN.equals(field.name())) {
                throw new SchemaValidationException("'" + name + "': '!" + target + "' states 'chain', which a "
                        + "declaration never writes -- a type's chain is its IS-A order, which the resolver "
                        + "computes from composition ('&') and refinement ('^') and the linker inverts over the "
                        + "schema map (§4.1). Compose instead: '" + name + " => parent & { ... }'");
            }
        }
    }

    /**
     * Refuses a constructor declaring a field named {@code chain}: a declaration composing the kernel's {@code
     * type} -- every type constructor -- has that name already, and it is the IS-A chain
     * the resolver computes (§4.1). Refused where it is declared, since what it collides with is a value no
     * instance writes, and the collision would otherwise surface only at an instance that used the field.
     */
    private void refuseDeclaredChain(String name, List<RecordEntry> entries, List<String> chain) {
        if (!chain.contains(KernelNames.of("type")) || !kernel.test(KernelNames.of("type"))) {
            return;
        }
        for (RecordEntry entry : entries) {
            if (entry instanceof FieldDef field && CHAIN.equals(field.name())) {
                throw new SchemaValidationException("'" + name + "' declares a field 'chain', the name the kernel's "
                        + "'type' gives a type's IS-A chain (§4.1) -- '" + name + "' composes it, and a "
                        + "constructor never declares that name. Name the field something else");
            }
        }
    }

    /**
     * Refuses an author-written {@code @meta.application}: the kernel's annotation is the resolver's, stating on an
     * entry's key the application it is the instantiation of (§8.2), as {@code chain} states where it sits -- so
     * a declaration writing it makes a claim no declaration can make. Judged by where the name is defined, so a
     * meta layer's own annotation of that name is its own.
     */
    private void refuseWrittenApplication(String name, String annotation) {
        if (TsonSchema.APPLICATION.equals(annotation) && vocabularyDefinitions.definition(annotation) != null
                && vocabularyKernel.test(annotation)) {
            throw new SchemaValidationException("'" + name + "': '@meta.application' is written, which a declaration "
                    + "never writes -- it is the application an entry is the instantiation of, which the resolver "
                    + "states on the key of every entry that closes one (§8.2). Write the application itself: '"
                    + name + " => template<arguments>'");
        }
    }

    /**
     * Runs the judgements resolution left for after every entry is ready ([TSON-SCHEMA] §3.4.1's sixth rule), in the
     * order they were left, handing each failure to {@code failure} with the declaration it is against. A judgement
     * that walks a name to the entry it names belongs here: a name at a type position orders nothing, so walking it
     * while entries are still being made ready would make one wait on what it only names.
     */
    void judgeDeferred(java.util.function.BiConsumer<String, SchemaValidationException> failure) {
        List<Deferred> judging = List.copyOf(deferred);
        deferred.clear();
        for (Deferred judgement : judging) {
            try {
                judgement.check().run();
            } catch (SchemaValidationException e) {
                failure.accept(judgement.declaration(), e);
            }
        }
    }

    /** One judgement left for after every entry is ready, against the declaration that needs it. */
    private record Deferred(String declaration, Runnable check) {
    }

    /** §5.8's one name type over a subtype's supertypes: {@code a} from {@code aFrom} and {@code b} from {@code bFrom}. */
    private void requireOneNameType(String name, String aFrom, Optional<String> a, String bFrom, Optional<String> b) {
        if (!sameNameType(a, b)) {
            throw new SchemaValidationException("'" + name + "': supertypes '" + aFrom + "' and '" + bFrom
                    + "' have different name types, '" + a.orElse(RecordBody.FIELD_NAME) + "' and '"
                    + b.orElse(RecordBody.FIELD_NAME) + "' -- a record's name type is set where it is declared, "
                    + "the default included, and a subtype cannot change it; declare both over one name type");
        }
    }

    /**
     * Whether two records' name types are one type: the same entry at the end of each one's alias chain, not
     * the same spelling, so {@code jn => json_name} is {@code json_name} and the kernel's {@code meta.field_name} is
     * {@code meta.identifier}. Both resolve in the schema's one namespace, the absent default being the kernel's {@code
     * meta.field_name}, and two that resolve to nothing are compared as written, the linker refusing each on its own.
     */
    private boolean sameNameType(Optional<String> a, Optional<String> b) {
        String ka = nameTypeIdentity(a);
        String kb = nameTypeIdentity(b);
        return ka != null && kb != null ? ka.equals(kb) : a.equals(b);
    }

    /** {@code nameType}'s terminal entry, or {@code null} where the namespace holds no such name. */
    private String nameTypeIdentity(Optional<String> nameType) {
        String written = nameType.orElse(RecordBody.FIELD_NAME);
        DefinitionGetter in = vocabularyDefinitions.definition(written) != null ? vocabularyDefinitions
                : namespaceDefinitions;
        return in.definition(written) == null ? null : AliasChain.terminal(written, in::definition);
    }

    /**
     * {@code <T> !C value} -- a parameter list before a construction. A template's body is a record body ([TSON-SCHEMA]
     * §5.10), so it is admitted before {@code record} alone, past its alias hops: {@code <T> !meta.record { … }} is the
     * explicit spelling of an open record ({@link #openRecord}). Before a constructor it is refused with what to write
     * instead -- the application where it is used, or a refined constructor applied -- and before an ordinary type,
     * whose value is no record body at all, with one declaration per instantiation.
     */
    private Top openInstance(String name, Instance template) {
        String target = template.target();
        ConstructorHead head = resolveConstructorTarget(name, target);
        if (head.name().equals(WireForm.RECORD)) {
            return openRecord(name, template);
        }
        String prefix = "'" + name + "': a template's body is a record body -- braces, a composition or a refinement "
                + "(§5.10) -- and '!" + target + " { ... }' ";
        if (EntryKind.isConstructor(head.name(), head.definition(), vocabularyKernel)) {
            String refined = head.name().equals(KernelNames.of("array"))
                    ? "'nonempty_type => " + target + " ^ { min_items?: = 1 }'"
                    : "'my_" + target.substring(target.lastIndexOf('.') + 1) + " => " + target + " ^ { ... }'";
            throw new SchemaValidationException(prefix + "constructs '" + target + "'. Write the application where it "
                    + "is used ('" + target + "<...>'), or declare a refined constructor (" + refined
                    + ") and apply it");
        }
        throw new SchemaValidationException(prefix + "is a value of the type '" + target + "', no type, and a "
                + "template over a value is no record. Declare one value per instantiation ('order_api => !" + target
                + " { ... }'), or carry what varies as data of the type");
    }

    /**
     * {@code <T> !meta.record { … }} -- an open record in its explicit spelling, read against {@code record}'s own
     * vocabulary as any record is, with the declaration's parameters added ([TSON-SCHEMA] §5.10). What the author wrote
     * is answered first, in the terms they wrote it in -- a construction of {@code meta.record} -- so a binding the record
     * does not declare names the record's vocabulary, and a parameter at a slot that names no type is refused for
     * standing there ({@link ParameterTypes#checkSlots}) rather than for not reading as a boolean.
     */
    private Top openRecord(String name, Instance template) {
        DataValue payload = template.value();
        if (!(resolveConstructorTarget(name, WireForm.RECORD).definition() instanceof RecordBody vocabulary)) {
            throw new IllegalStateException("'" + name + "': constructor '" + WireForm.RECORD + "' is no record");
        }
        ConstructorHead head = resolveConstructorTarget(name, WireForm.RECORD);
        refuseWrittenParameters(name, head, payload);
        refuseWrittenChain(name, WireForm.RECORD, payload);
        if (!(payload.coreValue() instanceof RecordValue bindings)) {
            throw new SchemaValidationException("'" + name + "': an open record's explicit body is a braced record of "
                    + "'" + WireForm.RECORD + "' bindings (§5.10)");
        }
        checkTemplateBindings(name, WireForm.RECORD, vocabulary, bindings);
        ParameterTypes.checkSlots(template.typeParams(), payload, vocabularyDefinitions::definition);
        refuseLiteralOnParameter(payload, template.typeParams());
        List<RecordValue.Field> withParameters = new ArrayList<>(bindings.fields());
        withParameters.add(new RecordValue.Field(WireForm.PARAMETERS, WireForm.plain(new ArrayValue(
                template.typeParams().stream().map(parameter -> WireForm.plain(new RecordValue(List.of(
                        WireForm.nameField(WireForm.NAME, parameter))))).toList()))));
        Top built = bindAtomInstance(name, new DataValue(payload.annotations(), Optional.of(WireForm.RECORD),
                new RecordValue(withParameters)), WireForm.RECORD);
        if (!(built instanceof RecordBody record) || !record.isOpen()) {
            throw new IllegalStateException("'" + name + "': '!" + WireForm.RECORD + " { ... }' with parameters bound to "
                    + "something other than an open record");
        }
        return checked(record);
    }

    /**
     * The rules an open record answers to, checked against its parameters: a parameter is a type, so it is never a
     * field's name (a group member's included) or the record's name type -- the record's own field names are read by
     * that, at the declaration -- and a selector and a literal each need a closed type.
     */
    private static RecordBody checked(RecordBody open) {
        for (String field : open.fields().keySet()) {
            if (open.parameterNames().contains(field)) {
                throw ParameterTypes.at(field, "a key of " + WireForm.RECORD + "." + WireForm.FIELDS);
            }
        }
        open.nameType().filter(open.parameterNames()::contains).ifPresent(parameter -> {
            throw ParameterTypes.at(parameter, WireForm.RECORD + "." + WireForm.NAME_TYPE);
        });
        refuseParameterTypedSelector(open);
        refuseLiteralOnOpenType(open);
        return open;
    }

    /**
     * A literal {@code value} needs a closed type ([TSON-SCHEMA] §5.10): what a literal token means is decided by the
     * type it is read as, and a field typed by a parameter, whole ({@code w?: T ~ 5}) or as an argument ({@code w?:
     * box<T> ~ 5}), has a different type in every application. Such a field takes no value.
     */
    private static void refuseLiteralOnOpenType(RecordBody open) {
        open.fields().forEach((name, field) -> {
            Optional<String> parameter = OpenEntry.parameterIn(field.type());
            if (parameter.isPresent() && field.value().isPresent()) {
                throw literalOnOpenType(name, parameter.get(), field.value().get().text());
            }
        });
    }

    /**
     * {@link #refuseLiteralOnOpenType}'s rule over the payload not yet bound, for a field whose whole type is a
     * parameter: read against {@code record_field} its literal {@code value}, typed {@code type!}, would be read against
     * a type nothing declares. So it is refused for where it stands before the reader is asked, as {@link
     * ParameterTypes#checkSlots} refuses a parameter at a value.
     */
    private static void refuseLiteralOnParameter(DataValue payload, List<String> parameters) {
        if (!(payload.coreValue() instanceof RecordValue bindings)) {
            return;
        }
        for (RecordValue.Field binding : bindings.fields()) {
            if (WireForm.FIELDS.equals(binding.name()) && binding.value().coreValue() instanceof MapValue fields) {
                fields.entries().forEach(entry -> {
                    if (entry.value().coreValue() instanceof RecordValue field && entry.key().coreValue()
                            instanceof TokenValue name) {
                        Optional<String> parameter = tokenAt(field, WireForm.TYPE).filter(parameters::contains);
                        Optional<String> literal = tokenAt(field, WireForm.VALUE);
                        if (parameter.isPresent() && literal.isPresent()) {
                            throw literalOnOpenType(name.text(), parameter.get(), literal.get());
                        }
                    }
                });
            }
        }
    }

    /** The token {@code record} holds at {@code slot}, as written; empty where it holds none. */
    private static Optional<String> tokenAt(RecordValue record, String slot) {
        return record.fields().stream().filter(field -> slot.equals(field.name())).findFirst()
                .filter(field -> field.value().coreValue() instanceof TokenValue)
                .map(field -> ((TokenValue) field.value().coreValue()).text());
    }

    private static SchemaValidationException literalOnOpenType(String field, String parameter, String literal) {
        return new SchemaValidationException("field '" + field + "' is typed by the parameter '" + parameter
                + "' and carries the literal value '" + literal + "' -- a literal needs a closed type, and this "
                + "field's type differs in every application. Write no value here, and state it where the "
                + "application is declared: 'name => template<argument> & { " + field + "?: = value }' (§5.10)");
    }

    /**
     * An open record's selector may not be typed by one of its parameters, directly ({@code kind: K =?}) or as an
     * argument ({@code kind: bounded<M> =?}): each application is a family base carrying the selector, and its
     * members pin it in the declarations composing the application, so a selector whose type varies per application
     * is no selector a family can be written against (§5.2, §5.10).
     */
    private static void refuseParameterTypedSelector(RecordBody open) {
        for (String name : open.discriminators()) {
            RecordField field = open.fields().get(name);
            Optional<String> parameter = field == null ? Optional.empty() : OpenEntry.parameterIn(field.type());
            if (parameter.isPresent()) {
                String typed = field.type() instanceof io.ltr8.tson.schema.meta.ParamRef
                        ? "typed by the type parameter '" : "typed by an application of the parameter '";
                throw new SchemaValidationException("discriminator field '" + name + "' is " + typed
                        + parameter.get() + "', and a position typed by this template reads a discriminator "
                        + "before it knows which member it has -- so its type cannot vary per application "
                        + "(§5.10)");
            }
        }
    }

    /** Every parameter by name: a written bound is put on where the declaration resolves ({@link #withBounds}). */
    private static List<Param> typeParameters(List<String> parameters) {
        return parameters.stream().map(Param::new).toList();
    }

    /** §5.10's two declaration-time questions about an open record's explicit payload -- see {@link #openRecord}. */
    private static void checkTemplateBindings(String name, String target, RecordBody vocabulary,
            RecordValue bindings) {
        Set<String> bound = new LinkedHashSet<>();
        for (RecordValue.Field binding : bindings.fields()) {
            if (!vocabulary.fields().containsKey(binding.name())) {
                throw new SchemaValidationException("'" + name + "': '" + target + "' has no field '"
                        + binding.name() + "' to bind (§7.2) -- its fields are "
                        + List.copyOf(vocabulary.fields().keySet()));
            }
            bound.add(binding.name());
        }
        for (Map.Entry<String, RecordField> entry : vocabulary.fields().entrySet()) {
            RecordField field = entry.getValue();
            if (!field.optional() && field.value().isEmpty() && !bound.contains(entry.getKey())) {
                // No application of this template could ever produce a valid instance, so the template is
                // wrong wherever the application is -- exactly the case the declaration is the right place
                // to report.
                throw new SchemaValidationException("'" + name + "': '" + target + "' requires a '"
                        + entry.getKey() + "', and nothing binds it (§7.2), so no application of this template "
                        + "could build one");
            }
        }
    }

    // ── Atom refinement (§5.5, §5.7) ─────────────────────────────────────────

    /**
     * {@code !I ^ { values }} -- refines an atom-family instance by tightening its constructor's
     * constraint fields. {@code I} resolves in the schema's one namespace, this document's own entries
     * included (§3.3.1), and MUST be a non-constructor instance of an atom family (kind {@code ATOM},
     * {@code constructor: false}). The constructor {@code I} was built from is reached through {@code I}'s
     * own facts -- the tag its value carries -- never a further name lookup.
     *
     * <p>Unlike {@link #resolveInstance}, {@code refinement.bindings()}'s own {@code typeRef} is not
     * pre-set by the grammar -- §12.1's {@code atom-refinement} takes a bare {@code record-def}, naming no
     * constructor -- so this attaches {@code I}'s constructor name to the value's type-ref
     * itself before binding through {@link #bindAtomInstance}. No positional-form wrapping (unlike
     * {@code Instance}) -- §5.5 guarantees a refinement body is always a braced record; {@link
     * #mergeWithSource} rejects anything else.
     *
     * <p><b>Merges with {@code I}'s own already-bound value; does not replace it</b> ({@link
     * #mergeWithSource}) -- required for a *chained* refinement to behave correctly: given {@code
     * int8 => !integer ^ { size: {...} } }, {@code big => !int8 ^ { min: -500 } } MUST still carry
     * {@code int8}'s own {@code size}, since {@code big} is declared as a refinement -- a narrowing --
     * of {@code int8}, and §5.7's own "Body materialisation" rule for the structurally analogous
     * record-refinement case is explicit that inherited fields survive a refinement that doesn't
     * mention them. A field named in the new refinement's own {@code values} overrides {@code I}'s
     * own value for it; any field {@code I} itself bound that {@code values} doesn't mention keeps
     * {@code I}'s own value.
     *
     * <p>Per §5.5's own text (not the general composition/refinement induction of §5.7/§5.8): the value's
     * constructor is {@code I}'s own (e.g. {@code numeric.integer_type} for {@code I = integer}), and its chain is
     * the literal single-element {@code [I]} -- not transitively chained with {@code I}'s own supertypes (empty for
     * every fresh {@code Instance}). {@code I} written as an alias names the instance at the end of its chain.
     */
    private Top resolveAtomRefinement(String name, AtomRefinement refinement) {
        // An alias is a hop, not a supertype (§8.3): `!integer ^ { ... }` through core's `integer => integer`
        // refines the instance at the end of the chain, which is what the refinement IS-A.
        String sourceName = AliasChain.terminal(refinement.target(), namespaceDefinitions::definition);
        Top source = namespaceDefinitions.definition(sourceName);
        if (source == null) {
            throw new SchemaValidationException("'" + name + "': '!" + sourceName
                    + "' names no entry of this schema's namespace (§3.3.1)");
        }
        // §5.5's question, asked of the body: is this an atom *instance*? An instance's body IS an atom
        // (`integer` carries an `!numeric.integer_type {}`), where its constructor's body is the vocabulary record
        // describing one (`integer_type` carries a `!meta.record { ... }`). So one test separates the pair and
        // establishes atom-ness at once, where neither half does alone -- a plain record has no supertypes
        // either, and `integer_type` is ATOM-kinded exactly like its instances. It is also how the reader
        // stack decides everything of this shape (`Subsumption`), rather than trusting a stated kind.
        if (!(source instanceof io.ltr8.tson.schema.meta.Atom)) {
            // The construction hint is offered only where construction would actually work, which is the
            // same applicability question (§4.1) -- so `top`, not applicable, gets the plain answer rather
            // than advice that would fail in turn.
            throw new SchemaValidationException("'" + name + "': '!" + sourceName + " ^ { ... }' needs an "
                    + "atom-family instance to narrow (§5.5), and '" + sourceName + "' is "
                    + (EntryKind.isConstructor(sourceName, source, kernel)
                            ? "a constraint vocabulary -- '^' narrows one of its instances. Did you mean "
                                    + "constructor application ('!" + sourceName + " { ... }')?"
                            : "kind=" + EntryKind.of(source, kernel) + ", which has no atom constraints to tighten"));
        }
        // The tag the source's value carries, and failing a recorded one the constructor its class is the body
        // of -- every resolved instance records its tag, and a hand-assembled one is the class's own.
        String constructor = factsOf(sourceName).constructor()
                .orElseGet(() -> TsonSchema.constructorOf(source));
        refuseWrittenChain(name, sourceName + " ^", refinement.bindings());

        DataValue merged = mergeWithSource(name, source, refinement.bindings(), constructor);
        Top body = bindAtomInstance(name, merged);
        checkNarrows(name, sourceName, source, body);
        constructedBy(name, constructor);
        return ((Type) body).withSupertypes(List.of(sourceName));
    }

    /**
     * §5.7's tightening rule, enforced: a refinement narrows its source's constraints, so a body
     * field that <em>loosens</em> one is a resolver error rather than a silently accepted override.
     * Without this, {@code !uint8 ^ { min: -10  max: 300 } } resolves happily to something wider
     * than the {@code uint8} it claims to refine.
     *
     * <p>Runs on the two bound constraint objects -- the source's own body and the merged result --
     * and asks the family itself, via {@link Atom#constraintsCheck}, since only it knows what "more
     * constrained" means for its own fields. Comparing the merged result rather than the refinement
     * body alone is what makes the check work for a facet the body never mentioned: an inherited
     * facet compares equal to the source's and tightens vacuously, so only what the author actually
     * wrote can fail.
     *
     * <p>A non-{@link Atom} body is not reachable here -- {@link #resolveAtomRefinement} has already
     * rejected a source that isn't an atom-family instance, and the merged value binds through that same
     * source's own constructor. The guard below returns rather than throwing for that reason: there is no
     * narrowing question to ask, and a body that reached here anyway is one of the two constructors'
     * problem, not this check's.
     */
    private void checkNarrows(String name, String sourceName, Top sourceBody, Top refinedBody) {
        if (!(sourceBody instanceof Atom sourceAtom) || !(refinedBody instanceof Atom refinedAtom)) {
            return;
        }
        List<String> violations = sourceAtom.constraintsCheck(refinedAtom);
        if (!violations.isEmpty()) {
            throw new SchemaValidationException("'" + name + "': not a valid refinement of '!" + sourceName
                    + "' (§5.7): " + String.join("; ", violations));
        }
    }

    /**
     * §5.7's "Body materialisation" rule, applied to atom refinement (§5.6's chained-refinement merge):
     * {@code newBindings} merged *over* {@code sourceBody}'s own
     * already-bound fields, not replacing them. {@code sourceBody} is re-serialized back to plain
     * record wire form via {@code DataClassObjectWriter.toTson} (writing a {@code Top}-typed value by its
     * own runtime class never emits a type-ref -- exactly the plain-record shape wanted here) and
     * re-parsed, so this needs no per-atom-class merge logic -- it works generically for every
     * atom-constraint class the same way. Field merge is by name at the {@link RecordValue} level:
     * {@code newBindings}'s own fields win; anything only {@code sourceBody} had survives untouched.
     *
     * <p><b>Merging before binding, not after, is required.</b> Binding {@code newBindings} on its own
     * and merging the two constraint objects would fail for any constructor with a {@code REQUIRED}
     * field carrying no schema default -- {@code float_type.format}, {@code binary.encoding} -- since
     * the refinement body has no reason to restate a facet its source already fixed, and the reader
     * would report {@code FIELD_REQUIRED} with nothing to fall back on. Merging first means the record
     * that reaches the reader is always complete.
     *
     * <p>Widening is caught after binding, by {@link #checkNarrows}, rather than here: this merge is
     * deliberately blind, and the two bound constraint objects are what a family's own narrowing rule
     * can actually compare.
     */
    private DataValue mergeWithSource(String name, Top sourceBody, DataValue newBindings, String constructorName) {
        Map<String, RecordValue.Field> merged = new LinkedHashMap<>();
        RecordBody constructor = namespaceDefinitions.definition(constructorName) instanceof RecordBody record
                ? record : null;
        if (sourceSerializedFields(name, sourceBody) instanceof RecordValue sourceRecord) {
            for (RecordValue.Field field : sourceRecord.fields()) {
                // The source's chain is where the source sits, not a facet the refinement inherits: the
                // refinement's own chain is set once it is bound. A field the constructor fixes is the
                // constructor's, supplied by its reader: copying it would read its value again, against a
                // type that may be this very entry -- `iri => !iri_reference ^ {...}` reading `spec` as an iri.
                RecordField declared = constructor == null ? null : constructor.fields().get(field.name());
                if (!CHAIN.equals(field.name()) && (declared == null || declared.role() != FieldRole.FIXED)) {
                    merged.put(field.name(), field);
                }
            }
        }
        if (newBindings.coreValue() instanceof RecordValue newRecord) {
            for (RecordValue.Field field : newRecord.fields()) {
                merged.put(field.name(), field);
            }
        } else if (!(newBindings.coreValue() instanceof EmptyBrace)) {
            // The author's error, not a gap: §12.1's `atom-refinement` takes a `record-def`, so this verdict
            // does not change as this library improves. Unreachable from source -- `TsonSchemaParser` refuses
            // a non-braced body at the `^` -- but the resolver is also driven directly, and coding it
            // NOT_IMPLEMENTED would exit 70 over a construct the grammar itself refuses.
            throw new SchemaValidationException("'" + name + "': expected a braced record of constraint "
                    + "bindings (§5.5), found " + newBindings.coreValue());
        }
        return new DataValue(newBindings.annotations(), Optional.of(constructorName), new RecordValue(List.copyOf(merged.values())));
    }

    private CoreValue sourceSerializedFields(String name, Top sourceBody) {
        try {
            String sourceText = writer.toTson(sourceBody);
            return new TsonDataParser(sourceText).parseDocument().root().coreValue();
        } catch (WriteException e) {
            throw new UnsupportedOperationException(
                    "'" + name + "': failed to re-serialize the refinement source: " + e.getMessage(), e);
        }
    }

    /**
     * The constructor a {@code !C ...} head names, and the name to read its payload against -- <b>after
     * following {@code C}'s alias chain</b> ([TSON-SCHEMA] §8.3).
     *
     * <p>An alias is a hop, not a rewrite: resolved output states the chain the author wrote, and a
     * processor collapses it when it needs the type. So {@code alias_array => array} makes
     * {@code !alias_array { ... }} an application of {@code array}, and everything this resolver then asks of
     * the head -- is it a template, is it applicable, what kind does construction transfer, whose vocabulary
     * reads the payload -- is a question about the entry at the end. Asking the alias instead answered every
     * one of them from an empty supertype chain and an {@code ALIAS} kind, which is what a hop looks like
     * rather than what it points at.
     *
     * <p>The author's own spelling survives where it is visible: {@link #resolveInstance} records {@code C} as
     * the value's tag, not the terminal, so the chain stays walkable from resolved output.
     */
    private ConstructorHead resolveConstructorTarget(String name, String target) {
        if (vocabularyDefinitions.definition(target) == null) {
            throw new SchemaValidationException("'" + name + "': '!" + target + "' names no entry of this "
                    + "schema's namespace -- its own entries and every entry its !!imports bring in (§3.3.1)");
        }
        String terminal = AliasChain.terminal(target, vocabularyDefinitions::definition);
        Top atEnd = vocabularyDefinitions.definition(terminal);
        if (atEnd == null) {
            // The walk stopped at a name no import declares -- a broken hop, which is the alias's own problem and
            // not this declaration's, but this is where it becomes visible.
            throw new SchemaValidationException("'" + name + "': '!" + target + "' is an alias whose chain "
                    + "ends at '" + terminal + "', which this schema's namespace does not declare (§8.3)");
        }
        return new ConstructorHead(terminal, atEnd);
    }

    /**
     * {@code value} re-typed to the name its payload is read against -- the terminal of the head's chain
     * (§8.3), where the author may have written an alias. A no-op for the ordinary case, where they are the
     * same name; the meta-schema's reader table is keyed by the constructor's own name, so an alias reaches
     * one only by asking under it.
     */
    private static DataValue readAs(DataValue value, String constructorName) {
        return value.typeRef().filter(constructorName::equals).isPresent()
                ? value
                : new DataValue(value.annotations(), Optional.of(constructorName), value.coreValue());
    }

    /** A construction head resolved through §8.3's chain: the name to read against, and the entry it names. */
    private record ConstructorHead(String name, Top definition) {
    }

    /**
     * Shared by {@link #resolveInstance} and {@link #resolveAtomRefinement} -- both need to read a
     * type-ref-carrying value against its own constructor's compiled reader. {@code value} already
     * names its own constructor via {@link DataValue#typeRef()}, so {@code
     * constructionReader.read(constructorName, value)} finds and reads the right compiled reader
     * directly -- no separate name→class table, no union-member scan.
     */
    private Top bindAtomInstance(String name, DataValue value) {
        return bindAtomInstance(name, value, value.typeRef().orElse(null));
    }

    /**
     * The same, a refusal naming {@code written} -- the constructor the author's spelling applies, where it differs
     * from the head the payload is read against.
     */
    private Top bindAtomInstance(String name, DataValue value, String written) {
        String constructorName = value.typeRef().orElseThrow(() -> new IllegalStateException(
                "'" + name + "': normalized value has no type-ref naming its own constructor -- "
                        + "DefinitionResolver should never produce this"));
        Top body;
        try {
            body = constructionReader.read(constructorName, value);
        } catch (ReadException e) {
            throw bodyIsNotValidData(name, written, e);
        } catch (BindMismatchException e) {
            // The constructor is a meta layer's own and the consumer never registered a class for it, or
            // registered one that disagrees. Either way it is their configuration, and it already says so --
            // wrapping it as an UnsupportedOperationException would relabel it a library gap, which is the
            // classification a caller acts on. Rethrown whole, naming the declaration that applied it, and
            // keeping which of the two it is -- a type with no class at all reads differently from one whose
            // class disagrees, and the caller's next move differs with it.
            String where = "'" + name + "': " + e.getMessage();
            throw e instanceof MissingBindingException ? new MissingBindingException(where)
                    : new BindMismatchException(where);
        } catch (SchemaRefusalException e) {
            // The processor's policy refusing the schema -- a wait chain past its limit, reached while this body was
            // read -- keeps its code: restated as a plain schema error, it would read as a verdict.
            throw e;
        } catch (SchemaValidationException e) {
            // A constructor's own record refusing the values it was handed. `decimal_type`'s member set is the
            // case: `members` is typed `set<value>`, so the wire admits anything and the family itself is what
            // says a member must be a number the atom reads. The author's error either way -- the verdict on
            // `members: ["abc"]` does not change when this library improves -- so it keeps its classification
            // rather than being relabelled a gap by the catch-all below.
            throw new SchemaValidationException("'" + name + "': " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new UnsupportedOperationException(
                    "'" + name + "': failed to bind '" + constructorName + "' via the compiled vocabulary reader: "
                            + e.getMessage(), e);
        }
        checkCoherent(name, constructorName, body);
        return body;
    }

    /**
     * Every atom body this resolver produces is checked for self-coherence, here rather than at
     * either call site, because this is the one place both of them meet: {@link #resolveInstance}'s
     * {@code !C value} and {@link #resolveAtomRefinement}'s {@code !I ^ { ... }} bind through this
     * method and nothing else does.
     *
     * <p>Both base kinds that carry orderable facets are asked, each by its own rule: {@link
     * Atom#coherenceCheck} for a constraint vocabulary's {@code min}/{@code max} family, {@link
     * Product#coherenceCheck} for a container's {@code min_items}/{@code max_items} pair. A {@link
     * RecordBody}, a {@link TupleBody} or a sum has no such pair and passes straight through on the
     * default. The rule is the family's own in both cases, since only it knows which of its fields form a
     * range -- and asking them here rather than one per kind is what lets a body with both kinds of
     * incoherence report both in one pass.
     *
     * <p>A violation is the <b>author's</b> error and stays a {@link SchemaValidationException}:
     * the verdict on {@code { min_length: 10  max_length: 3 }} does not change when this library
     * improves. It is deliberately not left to the atom parsers, which would surface it as an {@code
     * ErrorReader} -- the library-gap marker -- and so would give exactly the wrong classification.
     *
     * <p>Running it after binding rather than on the wire record is what makes it work generically:
     * a facet arrives here already converted to the host type its family compares on, and a facet the
     * body never mentioned is already filled in from the constructor's own schema-composed default.
     */
    private static void checkCoherent(String name, String constructorName, Top body) {
        List<String> violations = switch (body) {
            case Atom atom -> atom.coherenceCheck();
            case Product product -> product.coherenceCheck();
            case Sum sum -> sum.coherenceCheck();
            default -> List.of();
        };
        if (!violations.isEmpty()) {
            throw new SchemaValidationException("'" + name + "': the body's own '" + constructorName
                    + "' constraints contradict each other: " + String.join("; ", violations));
        }
    }

    /**
     * A body the constructor's own vocabulary rejects is the <b>author's</b> error, not a coverage gap
     * (§7.2: a constructor "is a record-shaped type, so it validates a record against its constraint-field
     * vocabulary", receiving ordinary record validation) -- so it is a {@link SchemaValidationException},
     * which {@code SchemaResolver} collects into a diagnostic and carries on from, rather than an {@link
     * UnsupportedOperationException}, which aborts the run under the "this is a bug in tson" banner. Both a
     * wrong-typed member ({@code !integer ^ { min: "abc" }}) and an unknown one ({@code minimum}) arrive here.
     *
     * <p>The {@link ReadException}'s own {@link io.ltr8.tson.base.Diagnostic} is deliberately
     * discarded and only its message kept: it was produced against a {@code DataValueEvents} replay of an
     * already-parsed AST, whose positions are all the {@code (0,0,0)} placeholder and whose {@code path} is a
     * data pointer into a synthetic body. Carrying those into a schema diagnostic would furnish a schema-side
     * problem with confident-looking data-side locations that name nothing. The declaration's own position and
     * {@code schemaPointer} come from {@code SchemaResolver}'s catch instead, which is where they are real.
     */
    private static SchemaValidationException bodyIsNotValidData(String name, String constructorName,
                                                                    ReadException cause) {
        return payloadFailure("'" + name + "': the body", "'" + name + "': the body is not valid data for '"
                + constructorName + "', the constructor's own constraint vocabulary -- " + cause.getMessage(), cause);
    }

    /**
     * A payload read's failure restated against the schema. A [TSON-DATA] §8.2 refusal -- a value the payload's
     * type makes a name, declined under the identifier policy -- stays a refusal with its rule's code, located by
     * {@code subject} and the value's pointer within the payload, which a name in a map needs to say which key;
     * any other failure is {@code invalid}, the author's error.
     */
    static SchemaValidationException payloadFailure(String subject, String invalid, ReadException cause) {
        Diagnostic refused = cause.diagnostic();
        if (!refused.code().isNameRefusal()) {
            return new SchemaValidationException(invalid, cause);
        }
        return new SchemaRefusalException(refused.code(), subject + " has a name this processor refuses"
                + refused.path().filter(path -> !path.isEmpty()).map(path -> " at " + path).orElse("")
                + ": " + refused.message(), cause);
    }

    // ── Top-level constructor application (§5.6) ──────────────────────────

    /**
     * A declaration whose body is an application, {@code bx => box<text>} or {@code text_list => meta.array<text>}.
     * Over a constructor it is the construction it spells ({@link #construction}); over an open record it <b>is</b> the
     * entry it denotes (§8.2): the closed record itself, the canonical application among its facts. §8.2 makes a
     * declared entry's identity its name, and a declaration that <em>constructs</em> a type keeps it ({@code
     * text_list => [text]} is the entry), so one that <em>denotes</em> a type obeys the same rule.
     */
    private Top resolveTemplateApplication(String name, ApplicationRef generic) {
        List<io.ltr8.tson.schema.meta.TypeRef> arguments = new ArrayList<>();
        for (TypeExpr arg : generic.args()) {
            try {
                arguments.add(resolveTypeRef(arg));
            } catch (UnsupportedOperationException e) {
                throw new UnsupportedOperationException("'" + name + "': " + e.getMessage());
            }
        }
        io.ltr8.tson.schema.meta.TypeRef target =
                io.ltr8.tson.schema.meta.TypeRef.of(generic.name(), arguments);
        Optional<Top> constructed = construction(name, target);
        if (constructed.isPresent()) {
            return constructed.get();
        }
        if (applicationCloser != null) {
            // This declaration owns the entry. A null answer means it cannot -- an unresolved head or an arity
            // mismatch -- and the reference form below is the fallback, for the ordinary path to report.
            Top owned = applicationCloser.closeApplicationInto(name, target);
            if (owned != null) {
                return owned;
            }
        }
        return new io.ltr8.tson.schema.meta.Alias(target);
    }

    /**
     * A declaration whose body applies a <b>constructor</b> -- {@code text_list => meta.array<text>} -- is the
     * construction it denotes ({@link ConstructionSugar}), resolved exactly as {@code text_list => !meta.array {
     * element_type: text }} is: its value states the constructor and the arguments, its tag is the head as written,
     * and it records no application, since nothing was substituted. Empty where the head, past its alias hops, is
     * no constructor, for the template path to take.
     */
    private Optional<Top> construction(String name, io.ltr8.tson.schema.meta.TypeRef application) {
        java.util.function.Function<String, Top> types = type -> {
            Top found = vocabularyDefinitions.definition(type);
            return found != null ? found : namespaceDefinitions.definition(type);
        };
        String constructor = AliasChain.terminal(application.name(), types);
        Top definition = types.apply(constructor);
        if (!ConstructionSugar.constructs(constructor, definition)) {
            return Optional.empty();
        }
        RecordValue payload = ConstructionSugar.payload(application, (RecordBody) definition, types);
        return Optional.of(resolveInstance(name, new Instance(
                new DataValue(List.of(), Optional.of(application.name()), payload))));
    }


    // ── Composition (§5.8) and subtraction (§5.9) ─────────────────────────


    /**
     * {@code A & B & { ... }}: each supertype's fields and groups are copied into the result, left
     * to right (§5.8's field-ordering rule, §5.11's "supertypes contribute their groups whole"),
     * checked for name overlap across supertypes; the trailing body's own entries are then resolved
     * against {@code inheritedFieldIndex} (name -&gt; position in {@code fields}, populated by the
     * supertype loop above) -- a body field naming an inherited field is a *tightening* entry
     * (§5.7, via {@link #resolveTighteningField}) and replaces that field in place; a body field
     * naming nothing inherited is genuinely new and is appended, same as before (§5.8's "new fields
     * are permitted; existing fields may be tightened" and "tightening entries replace inherited
     * fields in place; new fields are appended after all inherited fields"). The chain accumulates by induction: each
     * supertype's own {@code supertypes} is already
     * its full transitive chain (by the same induction, computed when *that* entry was resolved), so
     * {@code direct + parent.supertypes} for every direct supertype, deduplicated, is the complete transitive
     * chain -- no separate graph walk needed. A template keeps the parents as written as well ({@link Built#finish}),
     * and closing it completes each application's chain the same way ({@link Ancestry}).
     * {@code parameters} (a template's own {@code <T, ...>} list, §5.10) threads straight through
     * from the declaration's {@code typeParams} into the result -- {@code array}'s own shape
     * ({@code array => <T> ~product & { ... } }) -- with no substitution or validation that a field
     * actually uses each parameter.
     */
    private Built resolveComposition(String name, ConstructionDef construction, List<String> parameters) {
        List<io.ltr8.tson.schema.meta.TypeRef> directSupertypes = new ArrayList<>();
        // [TSON-SCHEMA] §5.2: a record's name type is set where it is declared, the kernel's default included,
        // and a composition takes its supertypes' one type: supertypes that disagree compose names of two types,
        // and choosing either would re-judge the other's names by a type they were never declared under.
        Optional<String> nameType = Optional.empty();
        String nameTypeFrom = null;   // the first record supertype, whose name type the rest must share
        List<String> transitiveSupertypes = new ArrayList<>();
        Set<String> seenTransitive = new HashSet<>();
        SequencedMap<String, BuiltField> fields = new LinkedHashMap<>();
        List<FieldGroup> groups = new ArrayList<>();
        Set<String> seenFieldNames = new HashSet<>();
        Set<String> inheritedFields = new HashSet<>();

        for (TypeExpr supertypeRef : construction.supertypes()) {
            if (supertypeRef instanceof ApplicationRef generic) {
                // §5.8's "Parameterized references", open end and closed alike: an application at an operand
                // denotes no entry of its own, whether its arguments are this declaration's parameters or
                // concrete. A template is a macro here -- it contributes its fields and its own ancestors,
                // and the type the composition produces is *this* declaration.
                OpenOperand operand = openOperand(name, generic, parameters, "supertype");
                for (String ancestor : operand.ancestors()) {
                    addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
                }
                // The application itself goes into the chain, arguments and all. It names no entry, and an
                // applied parent reaches the entry its arguments name, never its head (§5.8, §5.10: a template is
                // no type, so nothing is IS-A one): an open record keeps it in
                // `parents`, substituted when each application closes, and a closed declaration's is folded by
                // SchemaResolver into the edge to the entry `result<text>` denotes.
                // A removal revokes IS-A for every parent (§5.9), so nothing is kept.
                if (construction.removal().isEmpty()) {
                    // Closed: the arguments go in as the operand read them, since this channel is validated.
                    // Open: they stay as written, the argument being this declaration's own parameter, substituted
                    // when an application closes.
                    directSupertypes.add(io.ltr8.tson.schema.meta.TypeRef.of(generic.name(),
                            namesOwnParameter(generic, parameters)
                                    ? typeArguments(name, generic)
                                    : operand.arguments()));
                }
                absorb(name, operand.fields(), operand.groups(), fields, groups, seenFieldNames, inheritedFields);
                // An applied operand states its template's name type, which every application keeps (§5.2).
                if (nameTypeFrom == null) {
                    nameType = operand.nameType();
                    nameTypeFrom = generic.name();
                } else {
                    String firstFrom = nameTypeFrom;
                    Optional<String> first = nameType;
                    deferred.add(new Deferred(name, () -> requireOneNameType(name, firstFrom, first, generic.name(),
                            operand.nameType())));
                }
                continue;
            }
            if (!(supertypeRef instanceof NameRef simple)) {
                // A choice or an inline array/tuple at a supertype position. §12.1 lets these through only
                // because `construction-def` draws its operands from `type-ref` where `refined-def` takes a
                // name -- nothing here could ever denote a record, so there is no field set to compose with
                // and no implementation to wait for; §12.1's `supertype-ref` narrows the operands to named
                // references, so this shape is the grammar's own error to refuse.
                throw new SchemaValidationException("'" + name + "': a "
                        + (supertypeRef instanceof ChoiceExpr ? "choice" : "bracketed array/tuple")
                        + " cannot be a supertype -- '&' composes record types, and this form has "
                        + (supertypeRef instanceof ChoiceExpr ? "variants" : "elements") + ", not fields (§5.8)");
            }
            String supertypeName = simple.name();
            refuseParameterOperand(name, supertypeName, parameters, "supertype", "&");
            Top supertypeDef = namespaceDefinitions.definition(supertypeName);
            if (supertypeDef == null) {
                // Not a library gap: composition copies the supertype's own fields, so the name has to
                // resolve here rather than being left to the linker the way an ordinary field type is.
                throw new SchemaValidationException("'" + name + "': supertype '" + supertypeName
                        + "' names no type this schema declares or imports");
            }
            // §4.3 judges an operand at the end of its alias chain (§8.3), never at the name written: a
            // reference is the same type under another name (§5.7's table), so an alias of a record has that
            // record's fields to contribute. The walk stops at an argument-bearing target (AliasChain), and an
            // alias is judged for the terminal's reason rather than by whichever body the written name happened
            // to have.
            String supertypeTerminal = AliasChain.terminal(supertypeName,
                    namespaceDefinitions::definition);
            boolean supertypeHops = !supertypeTerminal.equals(supertypeName);
            Top terminalSupertype = supertypeHops
                    ? namespaceDefinitions.definition(supertypeTerminal) : supertypeDef;
            // §4.3's rule is the body test and nothing beside it: an operand MUST be a definition whose body
            // is a `!meta.record`. A declared application satisfies that -- `text_box => box<text>` closes to a
            // `!meta.record` carrying fields -- so it composes exactly as the hand-written record of the same
            // shape does, and how the author spelled it is no part of the question (§8.2: what is
            // canonicalised is identity, not provenance). Any other body fails this same test on its own: an
            // array, a map or a choice has no fields to contribute (§4.3).
            refuseUnappliedTemplate(name, supertypeName, terminalSupertype, "supertype", "&");
            if (!(terminalSupertype instanceof RecordBody supertypeBody)) {
                throw new SchemaValidationException("'" + name + "': supertype '" + supertypeName + "'"
                        + (supertypeHops ? " resolves through its alias chain to '" + supertypeTerminal
                                + "', which" : "")
                        + " has no fields to contribute -- its body is a binding record, not a "
                        + "vocabulary, so there is nothing for '&' to compose with (§4.3, "
                        + "§5.8). Compose with the head it derives from");
            }

            directSupertypes.add(io.ltr8.tson.schema.meta.TypeRef.of(supertypeName));
            addIfAbsent(transitiveSupertypes, seenTransitive, supertypeName);
            // The terminal is the same type under another name, so IS-A reaches it: without this edge a field
            // typed by the terminal would refuse a value of this declaration, though it IS-A a renaming of it.
            if (supertypeHops) {
                addIfAbsent(transitiveSupertypes, seenTransitive, supertypeTerminal);
            }
            for (String ancestor : Ancestry.closedNames(supertypeBody)) {
                addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
            }

            absorb(name, closedFields(supertypeBody), supertypeBody.groups(), fields, groups, seenFieldNames,
                    inheritedFields);
            if (nameTypeFrom == null) {
                nameType = supertypeBody.nameType();
                nameTypeFrom = supertypeName;
            } else {
                // Judged once every entry is ready: a name type is a name at a type position, which orders
                // nothing, and walking it to its terminal now would wait on an entry that may wait on this one.
                String firstFrom = nameTypeFrom;
                Optional<String> first = nameType;
                Optional<String> other = supertypeBody.nameType();
                deferred.add(new Deferred(name,
                        () -> requireOneNameType(name, firstFrom, first, supertypeName, other)));
            }
        }

        if (construction.body().isPresent()) {
            refuseDeclaredChain(name, construction.body().get().entries(), transitiveSupertypes);
            for (RecordEntry entry : construction.body().get().entries()) {
                resolveEntry(name, entry, parameters, fields, groups, seenFieldNames, inheritedFields);
            }
        }

        if (construction.removal().isPresent()) {
            applyRemovals(name, construction.removal().get(), bodyNames(construction), fields, groups);
        }
        // Two base kinds in one chain describe nothing (§4.1), refused here whether or not a removal empties it.
        determineKind(name, transitiveSupertypes);
        // §5.9: subtraction breaks IS-A, so a subtracted record's chain is empty -- for EVERY supertype,
        // including one that contributed nothing to the removal: `A & B - { f }` with `f` from A loses IS-A with
        // B as well, though every field B declares survives untouched. The clause is head-level, so its effect
        // must be readable without scanning the parents' field sets. An author wanting partial IS-A subtracts
        // first and composes second. A subtracted record is a record with an empty chain.
        //
        // An open record keeps its parents as written, which closing completes (Ancestry); a closed declaration's
        // parents hold only its closed applications, for SchemaResolver's fold to turn into edges and drop.
        TypeChain chain = TypeChain.EMPTY;
        if (construction.removal().isEmpty()) {
            chain = TypeChain.of(transitiveSupertypes).withParents(parameters.isEmpty()
                    ? directSupertypes.stream().filter(parent -> !parent.arguments().isEmpty()).toList()
                    : directSupertypes);
        }
        return new Built(chain, fields, groups,
                construction.body().map(declared -> markedNames(declared.entries())).orElse(List.of()),
                nameType);
    }

    /**
     * Every field name this declaration's own body mentions, whether it introduces the field or tightens an
     * inherited one. Both are what §5.9 rule 4 forbids a removal from naming, so one set answers both halves.
     */
    private static Set<String> bodyNames(ConstructionDef construction) {
        Set<String> names = new LinkedHashSet<>();
        if (construction.body().isEmpty()) {
            return names;
        }
        for (RecordEntry entry : construction.body().get().entries()) {
            switch (entry) {
                case FieldDef fieldDef -> names.add(fieldDef.name());
                case GroupDef groupDef -> groupDef.members().forEach(member -> names.add(member.name()));
            }
        }
        return names;
    }

    /**
     * §5.9's removal clause, applied last: supertypes merged, then the body, then this (rule 1). Removal reads
     * the <em>merged</em> field set with no regard for which supertype contributed a field (rule 3) -- IS-A is
     * already broken, so there is no contract left to violate.
     *
     * <p>Two things are rejected. A name that is nowhere in the merged set (rule 2, symmetric with
     * refinement's existing-fields-only rule), and a name this declaration's own body mentions (rule 4) --
     * adding a field and then removing it, or tightening a field and then removing it, says two incompatible
     * things in one declaration, and the author meant one of them. Rule 4 is checked first: a body-introduced
     * field <em>is</em> in the merged set, so the weaker "no such field" answer would be the wrong diagnosis.
     *
     * <p>Groups (§5.11): a removed member leaves its option, and an emptied option leaves
     * the group. A group left with one option that no schema could write is dissolved into the plain fields it
     * equals ({@link #dissolveInto}), and removing every member drops the group with them.
     */
    private static void applyRemovals(String declarationName, RemovalSet removal, Set<String> bodyDeclared,
                                       SequencedMap<String, BuiltField> fields, List<FieldGroup> groups) {
        Set<String> removed = new LinkedHashSet<>();
        for (String fieldName : removal.fieldNames()) {
            if (bodyDeclared.contains(fieldName)) {
                throw new SchemaValidationException("'" + declarationName + "': removal names '" + fieldName
                        + "', which this declaration's own body also declares -- a declaration cannot both state "
                        + "a field and remove it (§5.9 rule 4)");
            }
            if (!fields.containsKey(fieldName)) {
                throw new SchemaValidationException("'" + declarationName + "': removal names '" + fieldName
                        + "', which is not a field of the composed type -- only an inherited field can be "
                        + "removed (§5.9 rule 2)");
            }
            removed.add(fieldName);
        }

        List<FieldGroup> surviving = new ArrayList<>();
        for (FieldGroup group : groups) {
            if (group.memberNames().stream().noneMatch(removed::contains)) {
                surviving.add(group);
                continue;
            }
            List<List<String>> options = group.members().stream()
                    .map(option -> option.stream().filter(member -> !removed.contains(member)).toList())
                    .filter(option -> !option.isEmpty()).toList();
            // A member left alone in its option is present exactly when the option is chosen, so its mark goes.
            List<String> optionalMembers = group.optionalMembers().stream()
                    .filter(member -> options.stream().anyMatch(option -> option.size() > 1
                            && option.contains(member))).toList();
            if (options.size() > 1 || options.size() == 1 && keepsOneOption(options.getFirst(), optionalMembers,
                    group.optional())) {
                surviving.add(new FieldGroup(options, optionalMembers, group.optional()));
            } else if (options.size() == 1) {
                dissolveInto(fields, options.getFirst(), optionalMembers, group.optional());
            }
        }
        groups.clear();
        groups.addAll(surviving);

        fields.keySet().removeAll(removed);
    }

    /**
     * Whether a group reduced to one option is still one a schema could write ([TSON-SCHEMA] §5.11): not
     * optional with at least two members, every one marked -- the {@code +} group -- or optional with at least
     * two members, one unmarked. Any other one option is plain fields.
     */
    private static boolean keepsOneOption(List<String> option, List<String> optionalMembers,
                                          boolean optionalGroup) {
        if (option.size() < 2) {
            return false;
        }
        boolean anyUnmarked = option.stream().anyMatch(member -> !optionalMembers.contains(member));
        return optionalGroup ? anyUnmarked : !anyUnmarked;
    }

    /**
     * §5.11: a group reduced to one option it may not keep becomes the plain fields it equals. In a group that
     * is not optional the option is always chosen, so its unmarked members are required and its marked ones
     * optional; in an optional group every member is optional. A sole member takes the group's own
     * {@code optional} for both its marks.
     */
    private static void dissolveInto(SequencedMap<String, BuiltField> fields, List<String> option,
                                     List<String> optional, boolean optionalGroup) {
        for (Map.Entry<String, BuiltField> entry : fields.entrySet()) {
            if (!option.contains(entry.getKey())) {
                continue;
            }
            BuiltField field = entry.getValue();
            boolean omittable = optionalGroup || optional.contains(entry.getKey());
            entry.setValue(field.withFacts(omittable, option.size() == 1 ? omittable : field.voidable(),
                    FieldRole.FREE));
        }
    }

    private static void addIfAbsent(List<String> list, Set<String> seen, String name) {
        if (seen.add(name)) {
            list.add(name);
        }
    }

    /**
     * §4.1: a type's kind is settled by which of the kernel's base kinds -- {@code atom}/{@code product}/
     * {@code sum}/{@code namespace}, {@code top} never counts -- its transitive supertype chain reaches, each
     * judged by where it is defined ({@link #kernel}), never by its spelling: a schema's own {@code namespace} is
     * an ordinary record. The base kinds themselves and not each ancestor's own kind: the {@code atom}
     * entry is itself a PRODUCT (its own chain is {@code [chained, top, type]}), so "inherit the nearest ancestor's
     * kind" would give the wrong answer even for {@code atom}'s own subtypes. A chain reaching two of them is
     * refused, {@code namespace} beside a base kind included.
     */
    EntryKind determineKind(String name, List<String> transitiveSupertypes) {
        List<String> baseKindsFound = new ArrayList<>();
        for (String supertype : transitiveSupertypes) {
            if (BASE_KINDS.contains(supertype) && kernel.test(supertype)) {
                baseKindsFound.add(supertype);
            }
        }
        if (baseKindsFound.size() > 1) {
            // §4.1's base kinds are disjoint categories, not facets a type can hold several of, so a chain
            // reaching two of them describes nothing. A verdict, not a gap: no improvement to this library
            // makes a type both an atom and a product.
            throw new SchemaValidationException("'" + name + "' reaches " + baseKindsFound.size()
                    + " base kinds through its supertypes (" + String.join(", ", baseKindsFound) + ") -- §4.1 "
                    + "gives a type exactly one, so nothing can be both. Compose or refine from sources that "
                    + "agree on their base kind");
        }
        return EntryKind.ofChain(baseKindsFound, kernel);
    }

    private static final Set<String> BASE_KINDS = Set.of(KernelNames.of("atom"), KernelNames.of("product"),
            KernelNames.of("sum"), KernelNames.of("namespace"));

    // ── Refinement (§5.7): T ^ { ... } ─────────────────────────────────────

    /**
     * {@code source ^ { ... }} (optionally {@code ~}-marked and/or parameterized, e.g. {@code
     * set => <T> ~array<T> ^ { state: = REQUIRED ... }}): copies the *entire* inherited field set
     * and any groups from the (already-resolved) source's own {@link RecordBody} -- unlike
     * composition, refinement never adds fields, so every body entry MUST tighten one of them
     * ({@link #resolveTighteningField}, the same machinery composition-body tightening uses); a
     * body field naming nothing inherited is a resolver error (§5.7: "adding fields is a resolver
     * error"), as is a group naming no inherited group. A group that <em>does</em> name one restates it
     * (§5.11, {@link #restatesInheritedGroup}). The chain accumulates by the same induction composition
     * uses: {@code [sourceName] + source.supertypes}, deduplicated -- {@code set_type}'s own {@code [array,
     * product, top, type]}. A template's body keeps the source as its one written parent instead, which closing
     * completes ({@link Ancestry}). The kind is determined the same way composition determines it (the
     * transitive chain's literal atom/product/sum name, not the source's own resolved kind).
     */
    private Built resolveRefinement(String name, RefinedDef refined, List<String> parameters) {
        if (refined.target() instanceof ApplicationRef generic && (namesOwnParameter(generic, parameters)
                || OpenEntry.isOpen(namespaceDefinitions.definition(
                        Ancestry.templateHead(generic.name(), namespaceDefinitions))))) {
            // §5.7 against an application of an open record, over this declaration's parameters or closed: the same
            // absorption composition does (§5.8). The application names no entry: its ancestors are types and its
            // whole field set arrives, which is what `^` re-emits and then tightens, and the application stays the
            // written parent -- an open record's, substituted when it closes, and a closed declaration's, which
            // SchemaResolver folds into the edge to the entry declared for it where one is, so nothing is IS-A the
            // template itself.
            OpenOperand operand = openOperand(name, generic, parameters, "refinement source");
            return refineOnto(name, refined, parameters,
                    io.ltr8.tson.schema.meta.TypeRef.of(generic.name(), namesOwnParameter(generic, parameters)
                            ? typeArguments(name, generic) : operand.arguments()),
                    new ArrayList<>(operand.ancestors()), operand.fields(), operand.groups(), operand.nameType());
        }
        if (refined.target() instanceof NameRef simple) {
            refuseParameterOperand(name, simple.name(), parameters, "refinement source", "^");
        }
        io.ltr8.tson.schema.meta.TypeRef sourceRef = resolveRefinementSource(name, refined.target(), parameters);
        String sourceName = sourceRef.name();
        Top sourceDef = namespaceDefinitions.definition(sourceName);
        if (sourceDef == null) {
            // As with a supertype: a refinement reads the source's own field set, so the source must resolve.
            throw new SchemaValidationException("'" + name + "': refinement source '" + sourceName
                    + "' names no type this schema declares or imports");
        }
        // §5.7 states the walk explicitly -- "the source of `^`, after following its alias chain (§8.3)"
        // -- and §4.3 states it for both operator families. What is *finished* is a body with no vocabulary
        // to tighten: a top-level constructor application (§5.6), a choice, or an alias resolving to either.
        // What the walk adds is the case that is not finished: an alias of a record, which has that record's
        // vocabulary to tighten -- a declaration naming an open record's application included (§4.3).
        String sourceTerminal = AliasChain.terminal(sourceName, namespaceDefinitions::definition);
        boolean sourceHops = !sourceTerminal.equals(sourceName);
        Top terminalSource = sourceHops
                ? namespaceDefinitions.definition(sourceTerminal) : sourceDef;
        // §4.3's test is the body and nothing beside it, and §5.7 states the same of its own source: a
        // definition whose body is a `!meta.record` has a vocabulary to tighten. A declared application has
        // one -- `text_box => box<text>` closes to a `!meta.record` carrying fields -- so it refines like the
        // hand-written record of the same shape, and what polices a value the substitution already fixed
        // is §5.7's own per-field rule, which refuses re-fixing a FIXED field to a different value
        // whoever wrote it. Any other body fails on its own (§4.3).
        refuseUnappliedTemplate(name, sourceName, terminalSource, "refinement source", "^");
        if (!(terminalSource instanceof RecordBody sourceBody)) {
            throw new SchemaValidationException("'" + name + "': refinement source '" + sourceName + "'"
                    + (sourceHops ? " resolves through its alias chain to '" + sourceTerminal
                            + "', which" : "")
                    + " has no vocabulary to tighten -- its body is a binding record, so it is "
                    + "finished and '^' on it is a resolver error (§4.3, §5.7). Refine the "
                    + "head it derives from, or, for an atom instance, use atom refinement "
                    + "('!" + sourceName + " ^ { ... }', §5.5)");
        }

        List<String> transitiveSupertypes = new ArrayList<>();
        Set<String> seenTransitive = new HashSet<>();
        addIfAbsent(transitiveSupertypes, seenTransitive, sourceName);
        if (sourceHops) {
            addIfAbsent(transitiveSupertypes, seenTransitive, sourceTerminal);
        }
        for (String ancestor : Ancestry.closedNames(sourceBody)) {
            addIfAbsent(transitiveSupertypes, seenTransitive, ancestor);
        }
        return refineOnto(name, refined, parameters, sourceRef, transitiveSupertypes, closedFields(sourceBody),
                sourceBody.groups(), sourceBody.nameType());
    }

    /**
     * §5.7's tightening, over a field set already obtained. The two callers differ only in where that field
     * set came from and in what the source can be named: a closed source is an entry, so it heads the chain; an
     * open one names no entry, so only its own ancestors reach the chain until a template closing it reaches the
     * rest. Everything after that -- restating groups, tightening fields, the presence check, the kind -- is
     * one rule and lives here once. {@code source} is the parent as written, what an open record keeps.
     */
    private Built refineOnto(String name, RefinedDef refined,
            List<String> parameters, io.ltr8.tson.schema.meta.TypeRef source, List<String> transitiveSupertypes,
            SequencedMap<String, BuiltField> sourceFields, List<FieldGroup> sourceGroups,
            Optional<String> nameType) {
        SequencedMap<String, BuiltField> fields = new LinkedHashMap<>(sourceFields);
        List<FieldGroup> groups = new ArrayList<>(sourceGroups);
        Set<String> inheritedFields = Set.copyOf(sourceFields.keySet());
        refuseDeclaredChain(name, refined.body().entries(), transitiveSupertypes);

        for (RecordEntry entry : refined.body().entries()) {
            if (entry instanceof GroupDef groupDef) {
                if (!restatesInheritedGroup(name, groupDef, parameters, fields, groups, inheritedFields)) {
                    throw new SchemaValidationException("'" + name + "': the group ("
                            + groupDef.fieldGroup().describe() + ") names no inherited group -- a "
                            + "refinement copies its source's whole field set and admits no new fields or "
                            + "groups; composition (`&`) is what adds one (§5.7, §5.11)");
                }
                continue;
            }
            FieldDef fieldDef = (FieldDef) entry;
            BuiltField inherited = fields.get(fieldDef.name());
            if (inherited == null) {
                throw new SchemaValidationException("'" + name + "': refinement body field '" + fieldDef.name()
                        + "' names no inherited field -- a refinement copies its source's whole field set and "
                        + "admits no new fields; composition (`&`) is what adds one (§5.7)");
            }
            fields.put(fieldDef.name(), resolveTighteningField(name, fieldDef, inherited, parameters, groups));
        }

        determineKind(name, transitiveSupertypes);
        // §5.7's one written parent: an open record keeps it in its chain, as a closed declaration keeps an applied
        // one for SchemaResolver's fold, and a closed refinement of a name records it as a fact, since its chain
        // cannot tell `^` from a composition adding nothing (TsonSchema.EntryFacts).
        TypeChain chain = TypeChain.of(transitiveSupertypes);
        if (parameters.isEmpty() && source.arguments().isEmpty()) {
            facts.put(name, factsOf(name).withRefines(Optional.of(source.name())));
        } else {
            chain = chain.withParents(List.of(source));
        }
        return new Built(chain, fields, groups, markedNames(refined.body().entries()), nameType);
    }

    /**
     * A refinement's source ({@code target}) is always a {@link NameRef} or an {@link
     * ApplicationRef} by grammar (see {@code RefinedDef}'s own Javadoc) -- a bare name resolves to a
     * reference without arguments; a generic application whose head is no open record is handed to the
     * materialiser ({@link #closedApplication}), one of an open record being absorbed by {@link
     * #resolveRefinement} before this is asked.
     */
    private io.ltr8.tson.schema.meta.TypeRef resolveRefinementSource(String name, TypeExpr target,
            List<String> typeParams) {
        if (target instanceof NameRef simple) {
            return io.ltr8.tson.schema.meta.TypeRef.of(simple.name());
        }
        if (target instanceof ApplicationRef generic) {
            // A construction is refused here, as at any slot that holds a name; any other application whose
            // head is no open record is the materialiser's to judge (ApplicationCloser#closeApplication).
            return io.ltr8.tson.schema.meta.TypeRef.of(closedApplication(name, generic, typeParams, "refinement source"));
        }
        // Not a gap and not the author's error: the two shapes above are the two §12.1's `atom-refinement`
        // can produce, so a third means this resolver was handed a tree the grammar cannot build.
        throw new IllegalStateException(
                "'" + name + "': a refinement source is always a simple or generic type-ref by grammar, got " + target);
    }

    /**
     * One source's fields and groups copied into the record being built. Shared by the two composition paths
     * -- a closed supertype's own {@code RecordBody} and an open operand's substituted one -- so an operand
     * that contributes no name still contributes its fields on exactly the terms one that does would.
     */
    private void absorb(String name, SequencedMap<String, BuiltField> sourceFields,
            List<FieldGroup> sourceGroups, SequencedMap<String, BuiltField> fields, List<FieldGroup> groups,
            Set<String> seenFieldNames, Set<String> inheritedFields) {
        for (Map.Entry<String, BuiltField> field : sourceFields.entrySet()) {
            requireFieldNameNotSeen(name, field.getKey(), seenFieldNames, FieldOrigin.SUPERTYPE);
            seenFieldNames.add(field.getKey());
            inheritedFields.add(field.getKey());
            fields.put(field.getKey(), field.getValue());
        }
        groups.addAll(sourceGroups);
    }

    /** A closed source's fields as the fields resolution builds with: as they are. */
    private static SequencedMap<String, BuiltField> closedFields(RecordBody source) {
        SequencedMap<String, BuiltField> fields = new LinkedHashMap<>();
        source.fields().forEach((name, field) -> fields.put(name, BuiltField.of(field)));
        return fields;
    }

    /**
     * A template named at an operand with no arguments: {@code &} and {@code ^} read the operand's fields, and a
     * template's are its applications', each with its parameters bound (§5.8, §5.10). A template is no type, so
     * nothing names one bare, an operand included.
     */
    private static void refuseUnappliedTemplate(String name, String operand, Top definition, String position,
                                                String operator) {
        if (OpenEntry.isOpen(definition)) {
            throw new SchemaValidationException("'" + name + "': " + position + " '" + operand + "' is a template "
                    + "and is applied to no arguments -- '" + operator + "' takes the fields of a record, and a "
                    + "template is not a type: it has fields once its parameters are bound. Write '" + operand
                    + "<...>' with its arguments, or name the record its applications compose (§5.8, §5.10)");
        }
    }

    /**
     * A parameter is never an operand of {@code &} or {@code ^} ([TSON-SCHEMA] §5.10): both read the operand's
     * field set to flatten, and a parameter has none until the template is applied, so {@code <T> T & { … }} and
     * {@code <T> T ^ { … }} have no body to hold. An operand applied <em>to</em> a parameter, {@code result<T>},
     * is a different thing and is admitted. Checked before the name is looked up, as §5.10's shadowing rule
     * reads it: a parameter hides a schema entry of the same name.
     */
    private static void refuseParameterOperand(String name, String operand, List<String> parameters, String position,
                                               String operator) {
        if (parameters.contains(operand)) {
            throw new SchemaValidationException("'" + name + "': " + position + " '" + operand + "' is a parameter of"
                    + " this declaration, and a parameter is never an operand of '" + operator + "' -- its fields are"
                    + " unknown until the template is applied, so there is nothing to flatten (§5.10)");
        }
    }

    /**
     * Whether an application is applied to a parameter of the declaration that writes it, and so still open.
     * <b>Through nesting</b>: {@code box<inner<T>>} is as open as {@code box<T>} is, and read only at the top level
     * it would be taken as closed, the author's own parameter then reported as an unresolved reference -- a wrong
     * verdict on a schema that is not wrong.
     */
    private static boolean namesOwnParameter(ApplicationRef application, List<String> typeParams) {
        return application.args().stream().anyMatch(ref -> ref instanceof NameRef simple
                && typeParams.contains(simple.name())
                || ref instanceof ApplicationRef nested && namesOwnParameter(nested, typeParams));
    }

    /**
     * What an applied operand contributes to the declaration absorbing it: a field set, its groups and name type,
     * and the operand's own supertypes. Never an edge to the operand's head -- a template is no type (§5.10), and an
     * applied parent reaches the entry its arguments name (§5.8). Its <em>ancestors</em> are types, and the fields
     * arriving here came with them, so a declaration composing {@code box&lt;T&gt;} stands where {@code box}'s own
     * {@code base} is expected: {@code dog => pet_of<dog_type> & { … }} over {@code pet_of => <T> pet & { … }} IS-A
     * {@code pet}.
     *
     * <p><b>Absorbing needs no closure, which is the whole of why this works.</b> The operand is an open record, so its
     * field set is known while the application is open: substituting its parameters with the arguments <em>as
     * written</em> on the model ({@link Substitution}) is the substitution closing performs, and an argument that is one
     * of the absorbing declaration's own parameters lands as that declaration's name for it, which its own record holds
     * as its parameter in turn -- so the result is the absorbing template's fields as they are, and a closed
     * declaration's as closed fields.
     *
     * <p><b>Inner applications are deliberately left unclosed.</b> {@code closeApplications} is
     * materialisation's step, not this one: an operand body holding {@code inner&lt;T&gt;} cannot close while
     * {@code T} is open, and one holding a concrete {@code pair&lt;text, text&gt;} must close at the same
     * moment every other application in the absorbing declaration's body does. Both are the absorbing
     * declaration's own materialisation, one pass later.
     *
     * <p><b>The IS-A edge to the operand's application is derived one pass later, not here.</b> This declaration
     * cannot state it -- the entry a closed application denotes is whichever declaration names it, known only once
     * every declaration has resolved -- so what it does instead is keep the application in the template's {@code
     * parents}, substituted when each application closes, and {@code SchemaResolver} folds the closed application
     * into the edge to the declaration naming it, where one does.
     * That is why the parent is carried as a reference and not as the head name §5.8 describes: an edge to
     * {@code box} would hold of {@code box&lt;int32&gt;} as much as of {@code box&lt;text&gt;}, and only one
     * of those admits a {@code vip&lt;text&gt;}.
     */
    private OpenOperand openOperand(String name, ApplicationRef application, List<String> typeParams, String position) {
        String head = application.name();
        // An alias of an open record is the open record under another name (§8.3), and is applied as it is.
        String templateHead = Ancestry.templateHead(head, namespaceDefinitions);
        Top template = namespaceDefinitions.definition(templateHead);
        if (template == null) {
            throw new SchemaValidationException("'" + name + "': " + position + " '" + head
                    + "' names no type this schema declares or imports");
        }
        if (ConstructionSugar.constructs(AliasChain.terminal(head, namespaceDefinitions::definition),
                namespaceDefinitions.definition(AliasChain.terminal(head, namespaceDefinitions::definition)))) {
            // A constructor applied is a construction (§5.3), a binding record with elements or slots, not fields:
            // the same verdict its closed spelling gets once it closes.
            throw new SchemaValidationException("'" + name + "': " + position + " '" + head
                    + "<...>' has no fields to contribute -- it constructs a '!" + head + "', a binding record, not a "
                    + "vocabulary, so there is nothing to compose with (§5.8)");
        }
        if (!OpenEntry.isOpen(template)) {
            // Applied to this declaration's own parameter, so the author wrote arguments; the head takes none.
            throw new SchemaValidationException("'" + name + "': " + position + " '" + head
                    + "' declares no type parameters, so it cannot be applied to '"
                    + String.join(", ", typeParams) + "' (§5.10)");
        }
        List<String> templateParameters = OpenEntry.parameterNames(template);
        if (templateParameters.size() != application.args().size()) {
            throw new SchemaValidationException("'" + name + "': " + position + " '" + head + "' declares "
                    + templateParameters.size() + " type parameter(s) and is applied to "
                    + application.args().size() + " (§5.10)");
        }
        // An argument that is itself an application needs no special case: substitution writes a bound reference
        // as a reference value when it carries arguments, so `box<inner<T>>` keeps `inner<T>` whole and the
        // absorbing declaration's own materialisation closes it.
        List<io.ltr8.tson.schema.meta.TypeRef> arguments = typeArguments(name, application);
        // §5.10's bound check, which an operand absorbed by value would otherwise never get.
        if (applicationCloser != null) {
            applicationCloser.checkArguments(templateHead, template, arguments);
        }
        Map<String, io.ltr8.tson.schema.meta.TypeRef> bindings = new LinkedHashMap<>();
        for (int i = 0; i < templateParameters.size(); i++) {
            bindings.put(templateParameters.get(i), arguments.get(i));
        }
        RecordBody record = (RecordBody) template;
        // The operand's own parameters are replaced by what this declaration writes, which may be its own parameters;
        // every other name stays.
        SequencedMap<String, BuiltField> fields = new LinkedHashMap<>();
        Substitution.fields(record, bindings).forEach((field, substituted) -> fields.put(field,
                BuiltField.of(substituted)));
        return new OpenOperand(Ancestry.ofTemplate(templateHead, namespaceDefinitions), fields, record.groups(),
                record.nameType(), arguments);
    }

    /**
     * What an operand hands its absorber: the ancestors it can still be indexed under, its fields, and its
     * arguments, which a closed operand keeps in the chain as written until {@code SchemaResolver} folds it.
     */
    private record OpenOperand(List<String> ancestors, SequencedMap<String, BuiltField> fields,
                               List<FieldGroup> groups, Optional<String> nameType,
                               List<io.ltr8.tson.schema.meta.TypeRef> arguments) {
    }

    /**
     * A fully-bound application at a refinement source whose head, past its alias hops, is no open record, handed
     * to the materialiser ({@link ApplicationCloser#closeApplication}). An application of an open record goes to
     * {@link #openOperand} instead, which absorbs its fields without closing anything.
     */
    private String closedApplication(String name, ApplicationRef application, List<String> typeParams, String position) {
        if (applicationCloser == null) {
            throw new UnsupportedOperationException("'" + name + "': closing the " + position + " '"
                    + application.name() + "<...>' needs a whole-schema materialiser, and this resolver was "
                    + "built without one");
        }
        io.ltr8.tson.schema.meta.TypeRef resolved;
        try {
            resolved = io.ltr8.tson.schema.meta.TypeRef.of(application.name(), typeArguments(name, application));
        } catch (UnsupportedOperationException e) {
            throw new UnsupportedOperationException("'" + name + "': " + e.getMessage());
        }
        return applicationCloser.closeApplication(resolved);
    }

    private List<io.ltr8.tson.schema.meta.TypeRef> typeArguments(String name, ApplicationRef application) {
        List<io.ltr8.tson.schema.meta.TypeRef> args = new ArrayList<>();
        for (TypeExpr arg : application.args()) {
            args.add(resolveTypeRef(arg));
        }
        return args;
    }

    // ── Record bodies, fields, and field groups (§5.2, §5.11) ─────────────

    private Built resolveRecordBody(List<RecordEntry> entries, List<String> parameters) {
        SequencedMap<String, BuiltField> fields = new LinkedHashMap<>();
        List<FieldGroup> groups = new ArrayList<>();
        Set<String> seenFieldNames = new HashSet<>();
        for (RecordEntry entry : entries) {
            resolveEntry(null, entry, parameters, fields, groups, seenFieldNames, Set.of());
        }
        return new Built(TypeChain.EMPTY, fields, groups, markedNames(entries), Optional.empty());
    }

    /**
     * The fields this declaration's own entries write {@code =?} -- {@code record.discriminators},
     * in the order they are written, which is the order §5.2 compares their pins as a tuple.
     *
     * <p><b>Read from the entries and never from the resolved fields.</b> Which fields a family dispatches on
     * is the declaring record's statement, so a base that writes the mark states it once and a member that
     * merely inherits the selector states none. Reading the resolved list instead would restate every
     * inherited selector at each member -- §5.8 flattens a base's fields into all of them. Composition's
     * absorbed fields are excluded for free: they are no part of this declaration's entry list.
     * {@link SchemaDesugarer} collects the same names for a
     * declaration-position body, which reaches this resolver as a {@code record} payload rather than here.
     *
     * <p>A group member cannot be written one: §5.11 makes a value modifier a parse error on a member, and
     * {@code =?} is one. A member that acquires the mark by refinement is the linker's to refuse.
     */
    private static List<String> markedNames(List<RecordEntry> entries) {
        List<String> marked = new ArrayList<>();
        for (RecordEntry entry : entries) {
            switch (entry) {
                case FieldDef field -> {
                    if (FieldModifiers.discriminates(field)) {
                        marked.add(field.name());
                    }
                }
                case GroupDef ignored -> {
                    // §5.11 makes a value modifier a parse error on a group member, so `=?` never reaches
                    // one; a member that acquires the mark by refinement is the linker's to refuse.
                }
            }
        }
        return marked;
    }

    /**
     * {@code declarationName} is only used to word error messages -- {@code null} for a fresh
     * record, where {@code inheritedFields} is always empty (no supertype means no field could
     * ever be inherited, so every entry is either genuinely new or a duplicate declaration). A {@link
     * FieldDef} whose name is in {@code inheritedFields} is a *tightening* entry (§5.7): it's resolved
     * against, and replaces in place, the already-inherited field of that name, rather than being appended.
     */
    private void resolveEntry(String declarationName, RecordEntry entry, List<String> parameters,
                               SequencedMap<String, BuiltField> fields, List<FieldGroup> groups,
                               Set<String> seenFieldNames, Set<String> inheritedFields) {
        switch (entry) {
            case FieldDef fieldDef -> {
                if (inheritedFields.contains(fieldDef.name())) {
                    fields.put(fieldDef.name(), resolveTighteningField(declarationName, fieldDef,
                            fields.get(fieldDef.name()), parameters, groups));
                } else {
                    requireFieldNameNotSeen(declarationName, fieldDef.name(), seenFieldNames, FieldOrigin.BODY_FIELD);
                    seenFieldNames.add(fieldDef.name());
                    fields.put(fieldDef.name(), resolveField(fieldDef, Optional.empty(), parameters));
                }
            }
            case GroupDef groupDef -> {
                if (restatesInheritedGroup(declarationName, groupDef, parameters, fields, groups, inheritedFields)) {
                    return;
                }
                for (GroupDef.Member member : groupDef.members()) {
                    requireFieldNameNotSeen(declarationName, member.name(), seenFieldNames,
                            FieldOrigin.GROUP_MEMBER);
                    seenFieldNames.add(member.name());
                    fields.put(member.name(), resolveGroupMember(member, parameters));
                }
                groups.add(groupDef.fieldGroup());
            }
        }
    }

    /**
     * §5.7's refinement/tightening rules, applied to one composition-body field that names an
     * already-inherited field: resolved the same way as any field ({@link #resolveField}), except
     * an elided type-ref (a modifier-only entry, {@code field: = value}) inherits {@code
     * inherited.type()} and its voidability rather than failing, and the result MUST refine the inherited
     * field ({@link #refines}) -- e.g. {@code array}'s own {@code access_pattern?: meta.product_access_type =
     * INDEX} pins {@code product}'s required field, which refines it. Whether a restated type narrows the inherited
     * one is an IS-A question over names not yet ready here, so the link asks it ({@code
     * TsonSchemaLinker.checkRestatedFieldTypes}). The identity-diagonal rule (a FIXED
     * restatement MUST NOT change the pinned value) is not checked here: the two values are never compared.
     *
     * <p><b>A restated group member stays a member</b> (§5.11). Its presence is the group's, so the name takes
     * no {@code ?} and the member stays optional whatever the restatement writes; a default is refused, being
     * a value only omission reaches; and a pin is admitted and never injected ({@link RecordField#omitted}),
     * since an injected member would be present and presence is what selects the group's alternative.
     */
    private BuiltField resolveTighteningField(String declarationName, FieldDef fieldDef,
            BuiltField inherited, List<String> parameters, List<FieldGroup> groups) {
        boolean member = groups.stream().anyMatch(group -> group.hasMember(fieldDef.name()));
        if (member) {
            restateMemberMark(declarationName, fieldDef, groups);
        }
        if (member && fieldDef.modifier().filter(m -> m.kind() == FieldDef.Modifier.Kind.DEFAULT).isPresent()) {
            throw new SchemaValidationException("'" + declarationName + "': '" + fieldDef.name() + "' is a "
                    + "member of a field group and takes no default -- a default is what omission yields, and "
                    + "a member's omission is the group's (§5.11). A pin ('= value') is checked where written "
                    + "and never supplied");
        }
        BuiltField tightened = resolveField(fieldDef, Optional.of(inherited), parameters);
        if (member) {
            tightened = tightened.withFacts(true, tightened.voidable(), tightened.role());
        }
        if (!refines(inherited, tightened)) {
            // §5.7's table is a rule about schemas, not a coverage boundary: "refinement can only restrict,
            // never expand -- FIXED states are terminal, and loosening a required field to optional is a
            // resolver error".
            throw new SchemaValidationException("'" + declarationName + "': tightening '" + fieldDef.name()
                    + "' from " + inherited.describe() + " to " + tightened.describe() + " is not a permitted "
                    + "state transition -- a refinement can only restrict, never expand (§5.7)");
        }
        return tightened;
    }

    /**
     * A restated member's name {@code ?} (§5.11) speaks for its option, not the record:
     * it keeps the member optional once its option is chosen, and leaving it off makes the member required
     * there -- the name's {@code ?} is never inherited, at a member as at any field. It may be dropped and
     * never added, since adding one loosens the option. A {@code +} group, the one group of a single REQUIRED
     * option, is the exception: its members were written without a {@code ?}, so they are restated that way
     * and keep their mark.
     */
    private static void restateMemberMark(String declarationName, FieldDef fieldDef, List<FieldGroup> groups) {
        for (int i = 0; i < groups.size(); i++) {
            FieldGroup group = groups.get(i);
            if (!group.hasMember(fieldDef.name())) {
                continue;
            }
            boolean atLeastOne = group.atLeastOne();
            boolean marked = group.optionalMembers().contains(fieldDef.name());
            if (fieldDef.omittable() && (atLeastOne || !marked)) {
                throw new SchemaValidationException("'" + declarationName + "': '" + fieldDef.name() + "' is a "
                        + "member of a field group " + (atLeastOne
                        ? "written with '+', whose members take no '?' -- restate it as written there"
                        : "without a '?' on its name, and adding one loosens its option") + " (§5.11)");
            }
            if (!fieldDef.omittable() && marked && !atLeastOne) {
                List<String> optionalMembers = new ArrayList<>(group.optionalMembers());
                optionalMembers.remove(fieldDef.name());
                groups.set(i, new FieldGroup(group.members(), optionalMembers, group.optional()));
            }
            return;
        }
    }

    /**
     * §5.7's refinement of one field, as three orders -- one per question a field answers, each running from
     * least to most determined -- and a restatement refines its source exactly when no question moves
     * backwards. What omission yields runs absent → missing-field error → injected; whether {@code _} is
     * admitted runs voidable → not; the value's role runs FREE → DEFAULT → FIXED.
     *
     * <p>That reproduces §5.7's transition table cell for cell: a plain required field may take a default or
     * a pin, an optional one may become anything, a default may be pinned, and a pin is terminal.
     */
    private static boolean refines(BuiltField from, BuiltField to) {
        return omission(to) >= omission(from) && (from.voidable() || !to.voidable())
                && to.role().ordinal() >= from.role().ordinal();
    }

    /**
     * What omitting the field yields, ranked for {@link #refines}: 0 absence, 1 the missing-field error, 2 an
     * injected value. Asked as of a plain field: a group member's presence is the group's, and a member's
     * facts refine like any field's.
     */
    private static int omission(BuiltField field) {
        return switch (field.omitted()) {
            case NOTHING -> 0;
            case MISSING -> 1;
            case VALUE -> 2;
        };
    }

    /**
     * Where a colliding name was written, which is the whole content of the diagnostic -- the rule broken is
     * the same one in each case (a field name is unique across a record's plain fields, its groups' members,
     * and everything its supertypes contribute, §5.11), but what the author has to change is not.
     */
    private enum FieldOrigin {
        SUPERTYPE("two supertypes both contribute it -- supertypes MUST contribute disjoint field sets, "
                + "including a diamond where both paths reach the same originating type (§5.8)"),
        BODY_FIELD("this body declares it twice (§5.11: a field name is unique across a record's plain fields "
                + "and all its groups' members)"),
        GROUP_MEMBER("a group member repeats it -- member labels share the enclosing record's field namespace "
                + "(§5.11)");

        private final String explanation;

        FieldOrigin(String explanation) {
            this.explanation = explanation;
        }
    }

    /**
     * Rejects a repeated field name. Every case is the author's error under §5.11's uniqueness rule (§5.8's
     * disjointness rule being the same rule reaching through supertypes), never a coverage gap -- so the
     * diagnostic's job is to say <em>which</em> of the three ways it happened, since that is what decides the
     * fix.
     *
     * <p>Tightening never reaches here: a body field naming an <em>inherited</em> field is routed by {@code
     * inheritedFields} in {@link #resolveEntry} before this is consulted for that name.
     */
    private static void requireFieldNameNotSeen(String declarationName, String fieldName,
                                                 Set<String> seenFieldNames, FieldOrigin origin) {
        if (seenFieldNames.contains(fieldName)) {
            throw new SchemaValidationException((declarationName == null ? "" : "'" + declarationName + "': ")
                    + "field '" + fieldName + "' is declared more than once -- " + origin.explanation);
        }
    }

    /**
     * A field's default (`{@code ~}`) or fixed (`{@code =}`) modifier value (§5.2) is the field's {@code value}, a
     * literal. A field's value is never a parameter ([TSON-SCHEMA] §5.10): an unquoted token naming one of this
     * declaration's own {@code parameters} -- §5.10's shadowing rule over the source spelling -- is refused here,
     * naming the slot ({@link ParameterTypes#atValue}). A parameter name in the type slot is the field's type, which
     * the open record holds as its parameter.
     *
     * <p>{@code inherited}, supplied only from {@link #resolveTighteningField}, is the field this entry
     * tightens. Two things are read off it: its <b>type</b>, when {@code field.type()} is elided ({@code
     * field: = value}, a modifier-only entry, §5.7's "Elided type-refs"), and with the type its
     * <b>voidability</b>, the type's {@code ?} living in the slot the entry elides. The name's {@code ?} is
     * always the entry's own and is never inherited. A fresh (non-tightening)
     * field always passes {@code Optional.empty()}, and an elided type with nothing to inherit from is the
     * <b>author's</b> error, not a gap -- §5.7 requires the resolver to reject a modifier-only entry both in a
     * fresh record (no source to elide toward) and in a composition body naming no inherited field, so it
     * raises {@link io.ltr8.tson.base.SchemaValidationException}.
     *
     * <p><b>A restatement's annotations merge over the inherited ones</b> ({@link #merged}), rather than
     * replacing them: a tightening entry states what it tightens, and §5.7's modifier-only spelling ({@code
     * extra: ?}) has no annotation position at all, so an entry that mentions nothing must not be able to
     * erase what it does not mention.
     */
    private BuiltField resolveField(FieldDef field, Optional<BuiltField> inherited,
                                             List<String> parameters) {
        Annotations own = annotationsOf(field.name(), field.annotations());
        // No mark to inherit: which fields a family dispatches on is the *record's* statement
        // (`record.discriminators`), and a member states none of its own.
        return resolveFieldEntry(field, inherited, parameters)
                .withAnnotations(inherited.map(source -> merged(own, source.annotations())).orElse(own));
    }

    /**
     * A restated field's annotations: the restatement's own first, in source order, then the inherited
     * field's, in source order. One rule, and every part of it is load-bearing.
     *
     * <p><b>Concatenation, not replacement by name.</b> [TSON-DATA] §3.1 makes an annotation name repeatable
     * on one value -- "an annotation name MAY appear any number of times on a single value; all occurrences
     * are preserved in source order" -- so annotations are a list and not a map. Replacing "the inherited
     * {@code @doc}" would need an identity the model does not give them, and has no defined answer where the
     * source carries two. A restated field carrying two {@code @doc}s is the same shape as a field an author
     * wrote two on directly.
     *
     * <p><b>Nearer first, because order is the precedence mechanism already.</b> {@link Annotations#get} and
     * {@link Annotations#value} take the first occurrence, so leading with the restatement is what makes the
     * nearer declaration win at every such site without one of them having to know about composition. §5.8
     * states the rule this implements: the restatement's own annotations in source order, then the inherited
     * field's, adding and never removing.
     *
     * <p>The cost is that a subtype rewriting an inherited {@code @doc} leaves the field carrying both, which
     * is what "annotations are not removable" buys and what §3.1 already accepts anywhere else.
     */
    private static Annotations merged(Annotations restatement, Annotations inherited) {
        if (inherited.isEmpty()) {
            return restatement;
        }
        if (restatement.isEmpty()) {
            return inherited;
        }
        Annotations.Builder merged = new Annotations.Builder();
        restatement.values().forEach(merged::add);
        inherited.values().forEach(merged::add);
        return merged.build();
    }

    /**
     * §6 adds one annotation position to the type-definition grammar: "in {@code field-def}, annotations
     * precede the field name and annotate the field itself, mapping to the {@code meta.record_field} in resolver
     * output". Unlike a declaration, there is no before/after ambiguity here -- a field has one annotation
     * position and it is the field's own.
     */
    private BuiltField resolveFieldEntry(FieldDef field, Optional<BuiltField> inherited,
                                                  List<String> parameters) {
        if (field.type().isEmpty() && inherited.isEmpty()) {
            // §5.7: "a modifier-only entry is always a tightening -- it names no type, so it cannot declare a
            // new field". Reaching here means there was nothing to elide toward: either a fresh record body
            // ("every field MUST have an explicit type-ref, and the resolver MUST reject modifier-only entries
            // there") or a composition body entry naming no inherited field. Both are the author's error, not
            // a gap in this resolver -- the two positions where elision *is* legal resolve above.
            throw new SchemaValidationException("field '" + field.name() + "' states only a modifier and no "
                    + "type-ref, but names no inherited field to take a type from -- a modifier-only entry is "
                    + "always a tightening, so it is only meaningful in a refinement or composition body, "
                    + "against a field the source declares (§5.7)");
        }
        // The type's `?` sits in the type slot, so an entry that elides the type -- a modifier-only
        // tightening, `min: = 0` -- inherits the voidability of the field it tightens with the type itself.
        // The name's `?` is always the entry's own: an unmarked name says the key is written.
        boolean voidable = field.type().isPresent()
                ? field.type().get().voidable()
                : inherited.map(BuiltField::voidable).orElse(false);

        FieldModifiers.Resolved resolved =
                FieldModifiers.of(field.name(), field.omittable(), voidable, field.modifier());
        Optional<Token> value = resolved.value().map(DefinitionResolver::toMetaToken);
        value.filter(token -> token.form() == Token.Form.UNQUOTED && parameters.contains(token.text()))
                .ifPresent(token -> {
                    throw ParameterTypes.atValue(token.text(), field.name());
                });
        if (field.type().isPresent()) {
            io.ltr8.tson.schema.meta.TypeExpr type = resolveFieldType(field.name(), field.type().get().typeRef());
            if (value.isPresent() && type instanceof io.ltr8.tson.schema.meta.TypeInline inline
                    && !(inline.of() instanceof io.ltr8.tson.schema.meta.ReferenceBody)) {
                // §5.2: a value is admitted at a field typed by an atom or an enum, and a type written in place is one
                // only as a reference; a container, a tuple or a choice in place carries none, as a declared one
                // carries none.
                throw new SchemaValidationException("field '" + field.name() + "' is declared '" + inline.text()
                        + "', a type written in place that is no reference, so it cannot have "
                        + (resolved.role() == FieldRole.DEFAULT ? "a default" : "a fixed value") + " (§5.2): a "
                        + "fixed or default value is available on a field typed by an atom, an enum or a reference, "
                        + "and nowhere else -- drop the modifier, or declare the field with a scalar type");
            }
            return new BuiltField(type, resolved.optional(), resolved.voidable(), resolved.role(), value,
                    Annotations.empty(), positions.of(field));
        }
        // An elided type is the inherited field's, a parameter it carries and what its names mean included.
        return inherited.get().restated(resolved.optional(), resolved.voidable(), resolved.role(), value,
                positions.of(field));
    }

    /** {@code schema.meta} has no dependency on {@code tson-compiler}, so it can't reuse {@link TokenValue} directly (see {@link Token}'s own Javadoc) -- this converts field by field instead. */
    private static Token toMetaToken(TokenValue token) {
        Token.Form form = switch (token.form()) {
            case UNQUOTED -> Token.Form.UNQUOTED;
            case SINGLE_LINE_QUOTED -> Token.Form.SINGLE_LINE_QUOTED;
            case MULTI_LINE_QUOTED -> Token.Form.MULTI_LINE_QUOTED;
        };
        return new Token(token.text(), form);
    }

    /**
     * §5.11's group restatement, shared by a refinement body and a composition body -- "a body entry may also
     * restate a group: the restated group MUST have the same member labels in the same order (member
     * type-refs restated verbatim), and may tighten state OPTIONAL→REQUIRED; REQUIRED→OPTIONAL is a resolver
     * error, and changing membership is a resolver error."
     *
     * <p>Returns {@code true} having applied the restatement, or {@code false} when this group names nothing
     * inherited and is therefore a genuinely new one -- which a composition body appends and a refinement
     * body rejects, each at its own call site. A fresh record reaches here with an empty {@code
     * inheritedFields} and so always takes the {@code false} path.
     *
     * <p>Only the <em>group's</em> state changes. Members flatten as {@code OPTIONAL} whatever the group says
     * (§5.11), so a restatement never rewrites the member fields; tightening an individual member is the
     * separate, already-supported gesture of naming it as an ordinary field.
     *
     * <p>Everything checked here is the author's error under a MUST, so each is a {@link
     * SchemaValidationException} -- and each says which of the four rules was broken, since a
     * restatement that got the order wrong and one that changed a member's type need different fixes.
     */
    private boolean restatesInheritedGroup(String declarationName, GroupDef groupDef, List<String> parameters,
                                            SequencedMap<String, BuiltField> fields,
                                            List<FieldGroup> groups, Set<String> inheritedFields) {
        FieldGroup restatement = groupDef.fieldGroup();
        List<String> restated = restatement.memberNames();
        List<String> inheritedMembers = restated.stream().filter(inheritedFields::contains).toList();
        if (inheritedMembers.isEmpty()) {
            return false;
        }
        String prefix = (declarationName == null ? "" : "'" + declarationName + "': ") + "the restated group ("
                + restatement.describe() + ") ";
        if (inheritedMembers.size() != restated.size()) {
            throw new SchemaValidationException(prefix + "adds a member the source does not declare -- "
                    + "changing membership is a resolver error (§5.11)");
        }

        int index = -1;
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).hasMember(restated.get(0))) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new SchemaValidationException(prefix + "names inherited fields that are not a group -- "
                    + "a group can only restate one the source declares as a group (§5.11)");
        }
        FieldGroup inherited = groups.get(index);
        if (!inherited.members().equals(restatement.members())) {
            throw new SchemaValidationException(prefix + "does not match the inherited group ("
                    + inherited.describe() + ") -- a restatement MUST have the same options, their members in "
                    + "the same order, and changing membership is a resolver error (§5.11)");
        }
        List<String> added = restatement.optionalMembers().stream()
                .filter(member -> !inherited.optionalMembers().contains(member)).toList();
        if (!added.isEmpty()) {
            throw new SchemaValidationException(prefix + "marks " + String.join(", ", added) + " '?' where "
                    + "the source does not -- a restatement may drop a member's '?' and never add one, which "
                    + "loosens its option (§5.11)");
        }
        for (GroupDef.Member member : groupDef.members()) {
            BuiltField restatedMember = resolveGroupMember(member, parameters);
            BuiltField inheritedMember = fields.get(member.name());
            if (!sameType(restatedMember.type(), inheritedMember.type())) {
                throw new SchemaValidationException(prefix + "gives member '" + member.name() + "' the type "
                        + "'" + restatedMember.typeName() + "' where the source declares '"
                        + inheritedMember.typeName()
                        + "' -- member type-refs are restated verbatim (§5.11); narrowing a member's type is "
                        + "done by naming it as an ordinary field");
            }
        }

        if (!inherited.optional() && restatement.optional()) {
            throw new SchemaValidationException(prefix + "makes the group optional where the source's is not -- "
                    + "a restatement may drop a group's '?' and never add one (§5.11)");
        }
        groups.set(index, restatement);
        return true;
    }


    /**
     * Whether a restated member's type is the inherited one's, verbatim: a parameter is the name it is written as, so
     * a member an open source holds as its parameter is restated by that name.
     */
    private static boolean sameType(io.ltr8.tson.schema.meta.TypeExpr restated,
                                    io.ltr8.tson.schema.meta.TypeExpr inherited) {
        return restated.equals(inherited) || restated.text().equals(inherited.text());
    }

    /**
     * §12.1 gives a group member its own annotation position ({@code group-member = *annotation field-name ws
     * ":" ws type-ref ["?"]}), and it is read here on {@link #resolveField}'s terms: the marks are consumed and
     * everything else reaches the annotation channel -- which is what keeps the member's {@code @doc}. A
     * selector cannot be written here at all: §5.11 makes a value modifier a parse error on a member, and
     * {@code =?} is one -- a member is uniformly optional, and a selector that may be absent selects
     * nothing. One reached by refinement is the linker's to refuse. The member is optional, presence being
     * governed by the group (§5.11), and voidable exactly where its type carries {@code ?}.
     */
    private BuiltField resolveGroupMember(GroupDef.Member member, List<String> parameters) {
        return new BuiltField(resolveFieldType(member.name(), member.typeRef()), true, member.voidable(),
                FieldRole.FREE,
                Optional.empty(), annotationsOf(member.name(), member.annotations()), Optional.empty());
    }

    /**
     * The type a declaration's parameter list wrote after {@code parameter} ({@code <T: text>}, §5.10), as a
     * type-ref. A written type names a type or an application of one; a container sugar form there names none,
     * and is refused here in those terms.
     */
    private io.ltr8.tson.schema.meta.TypeRef parameterType(String parameter, TypeExpr written) {
        if (!(written instanceof NameRef) && !(written instanceof ApplicationRef)) {
            throw new SchemaValidationException("parameter '" + parameter + "' is written with the type "
                    + written + ", and a parameter's written type names a declared type or an application of one "
                    + "(§5.10); declare the form under a name and write that name");
        }
        return resolveTypeRef(written);
    }

    /**
     * A type-ref at a position that holds a name -- a parameter's written type, an argument -- as one of the two
     * shapes that reach resolution there: a bare {@link NameRef}, or an {@link ApplicationRef}, a §5.10 application
     * or a constructor's own generic vocabulary such as {@code set<T>}.
     *
     * <p><b>A sugar form or a construction in place is a broken invariant here</b>: {@link SchemaDesugarer} writes a
     * construction in place only at a field's type, and refuses one as an argument ({@link #unlifted}).
     */
    private io.ltr8.tson.schema.meta.TypeRef resolveTypeRef(TypeExpr ref) {
        return switch (ref) {
            case NameRef simple -> io.ltr8.tson.schema.meta.TypeRef.of(simple.name());
            case ApplicationRef generic -> {
                List<io.ltr8.tson.schema.meta.TypeRef> args = new ArrayList<>();
                for (TypeExpr arg : generic.args()) {
                    args.add(resolveTypeRef(arg));
                }
                yield io.ltr8.tson.schema.meta.TypeRef.of(generic.name(), args);
            }
            case ArrayExpr _, TupleExpr _, MapExpr _, ChoiceExpr _, InlineConstruction _ ->
                    throw unlifted("a type-ref position", ref);
        };
    }

    /**
     * A field's or group member's type: a type-ref as {@link #resolveTypeRef} resolves one, or a construction
     * written in place, {@code !meta.dependent { on: kind }} among them, read through its constructor's reader as a
     * declaration's {@code !C { ... }} is ({@link #bindAtomInstance}) and held at the field
     * ({@link io.ltr8.tson.schema.meta.TypeInline}). Reading it waits on its constructor, and on each type the read
     * reaches, as reading any value does ([TSON-SCHEMA] §3.4.1).
     */
    private io.ltr8.tson.schema.meta.TypeExpr resolveFieldType(String field, FieldTypeExpr ref) {
        return switch (ref) {
            case InlineConstruction inline -> inlineType(field, inline);
            case DependentType _ -> throw unlifted("field '" + field + "'", ref);
            case TypeExpr type -> resolveTypeRef(type);
        };
    }

    /**
     * The type a construction written in place at field {@code field} denotes. Only a container, a tuple, a choice or
     * a reference is written in place ({@link io.ltr8.tson.schema.meta.TypeInline}): the desugarer writes nothing
     * else, so another body here is a broken invariant.
     */
    private io.ltr8.tson.schema.meta.TypeInline inlineType(String field, InlineConstruction inline) {
        // The head is made ready first, as a declaration's `!C { ... }` makes its own (§3.4.1): reading a value
        // against a type waits on the type.
        String constructor = resolveConstructorTarget("field '" + field + "'", inline.constructor()).name();
        Top body = bindAtomInstance("field '" + field + "'", inline.value());
        if (!(body instanceof io.ltr8.tson.schema.meta.Type type)
                || !io.ltr8.tson.schema.meta.TypeInline.constructible(type)
                        && !(type instanceof io.ltr8.tson.schema.meta.DependentBody)) {
            throw new IllegalStateException("field '" + field + "': the construction '!" + inline.constructor()
                    + " { ... }' written in place built " + body + ", which is no container, tuple, choice, "
                    + "reference or dependent type -- SchemaDesugarer writes only those in place");
        }
        return new io.ltr8.tson.schema.meta.TypeInline(constructor,
                type.withChain(io.ltr8.tson.schema.meta.TypeChain.EMPTY));
    }
}
