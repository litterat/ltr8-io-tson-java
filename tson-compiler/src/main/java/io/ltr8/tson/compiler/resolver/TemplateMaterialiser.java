package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.compiler.TsonReadException;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.ScopedValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.ChoiceBody;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.FieldState;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Token;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TupleElement;
import io.ltr8.tson.schema.meta.TypeArgument;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.TypeRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/**
 * §5.10 materialisation: closes a template application by substituting its arguments into the template's
 * recorded open form, and replaces the application with a reference to the entry that results.
 *
 * <p><b>It runs over the resolved form, not the AST.</b> An application reaches here as a
 * {@link TypeRef} carrying {@code arguments} -- the one thing that shape means (a closed form is always an
 * entry named by a bare reference) -- so substitution is a walk over {@code schema.meta} values, and the
 * entry it produces can record its own {@code source}, which §8.2 keys identity on. Doing it over the AST
 * instead would reuse {@code SchemaDesugarer}'s injection machinery but has no channel for that {@code
 * source}, and would put type-level work back into a phase Tranche A made purely syntactic.
 *
 * <p><b>A held body closes by one process, whatever wrote it.</b> {@code <T> [T]} and {@code <T> { x: T }}
 * are both an application with a parameter standing in a slot -- {@code !array { element_type: T }} and
 * {@code !record { fields: [ { name: x  type: T } ] }} -- so both substitute by the same walk and are read
 * back through their own constructor's reader. See {@link #closeHeld}. What differs is only what the result
 * <em>is</em>: a <b>record</b> template's closure is the instantiation entry itself ({@link
 * #closeHeldRecord}), because a substituted record is the type the author named by writing the application;
 * every other held form closes to a <em>synthetic</em> named for the form, which the instantiation then
 * references ({@link #closeHeldTemplate}), because a form has no author-written name to be keyed on.
 *
 * <p><b>An alias closes by a third path and mints nothing.</b> §5.10's partial application,
 * {@code uuid_pair => <B> pair<uuid, B>}, holds {@code !reference { target: pair<uuid, B> }} like any other
 * open entry, but it <em>is</em> the application it names with some arguments still open -- so closing it
 * composes the two argument lists and hands back what that denotes, minting no entry of its own (§5.10: "no
 * intermediate entry per alias hop"). See {@link #closeHeldAlias}.
 *
 * <p><b>So the three cases are told apart by the constructor head, not by the body's shape.</b> Every open
 * entry's body is a {@link HeldBody} -- a record, composition or refinement template, a sugar form's lift,
 * an alias, and an error placeholder alike ({@code SchemaDesugarer.heldEmptyRecord}). {@code record} closes
 * to the instantiation, {@code reference} to a name, everything else to a synthetic.
 *
 * <p><b>Identity (§8.2).</b> An instantiation entry is keyed on the flattened application recorded in
 * {@code source}, so two {@code box<text>} anywhere in the schema land on one entry. The derived name is
 * built by {@link #internalName} from the application itself, which is what makes that dedup fall out of
 * naming rather than needing a second table.
 *
 * <p><b>Knot-tying.</b> The memo entry is registered <em>before</em> the body is substituted, so a
 * recursive application reached during substitution ({@code tree<T>} inside {@code tree}, which becomes
 * {@code tree<text>} once {@code T} is bound) finds the entry under construction and references it by name
 * rather than recursing forever.
 */
final class TemplateMaterialiser {

    /**
     * Every entry visible to this schema -- local declarations and merged {@code !!import}s alike. A
     * <em>getter</em> rather than a fixed map, because an application closed on demand during resolution
     * (at a supertype or refinement source) may name a head that has not been resolved yet; the getter is
     * {@code SchemaResolver}'s own memo, so asking for it resolves it, with the circular-composition guard
     * still in front.
     */
    private final DefinitionGetter namespace;

    /** {@code type_ref}'s own member names -- the one place a held token's channel still matters. */
    private static final String NAME = "name";
    private static final String VALUE = "value";
    private static final String ARGUMENTS = "arguments";

    /** The constructor a held record template carries -- its closure is the instantiation itself. */
    private static final String RECORD = "record";

    /** The constructor a held alias carries -- §5.10's partial application, which mints no entry. */
    private static final String REFERENCE = "reference";

    /** {@code reference}'s own member, the one an alias closes through. */
    private static final String TARGET = "target";

    /** The entries produced, keyed by their derived internal name, in creation order. */
    private final Map<String, TypeDefinition> materialised = new LinkedHashMap<>();

    /**
     * Which of {@link #materialised} are <b>synthetic</b> entries rather than instantiation entries -- the
     * closed forms {@link #closeHeldTemplate} mints, which are indistinguishable from the entry a
     * directly-written {@code [pixel; 1920]} lifts to and are the same entry when both appear (§8.2).
     *
     * <p>§8.2 marks only these: an instantiation entry carries no {@code @synthetic}, its {@code source}
     * being an application where a synthetic's is a bare constructor. The caller reads this set to put the
     * marker on the right keys.
     */
    private final Set<String> synthetics = new LinkedHashSet<>();

    /** Applications currently being closed, for the knot-tying memo and the termination guard's chain. */
    private final Set<String> closing = new LinkedHashSet<>();

    /**
     * Applications of <em>reference</em> templates currently composing. They mint no entry of their own
     * (§5.10: "no intermediate entry per alias hop"), so {@link #closing}'s knot-tying answer -- name the
     * entry under construction -- has nothing to name for them, and an alias that applies itself would hand
     * back a name nothing ever defines. Tracked separately so that case is a diagnosis instead.
     */
    private final Set<String> aliasClosing = new LinkedHashSet<>();

    /** The author-written head each link of {@link #closing} came from, outermost first. */
    private final List<String> heads = new ArrayList<>();

    /**
     * How deep the closing chain may go before materialisation is abandoned -- a <b>backstop</b>, not the
     * rule.
     *
     * <p>{@code TemplateRegularity} rejects a template that grows its argument on a recursive step where it
     * is <em>declared</em>, so nothing that reaches here should be able to run away: <em>regular</em>
     * recursion ties the knot on its first repeat and never nests, since {@code chain<text>} reaches
     * {@code chain<text>} again and finds it in {@link #closing}. This stays because the alternative
     * failure, if that check ever has a hole, is a {@link StackOverflowError} -- not a diagnosis, and not
     * something the exception policy can classify. One comparison for that is worth paying.
     *
     * <p>Distinct from §5.10.1's productivity rule, which is about a type with no finite <em>data</em>
     * model; the regularity rule is about one with no finite <em>type</em> model.
     */
    private static final int MAX_CLOSING_DEPTH = 64;

    /**
     * Where each entry is published as it is built, so the namespace can see it immediately. Load-bearing
     * for the on-demand half: a composition supertype closes an application and then looks the resulting
     * name up through {@code namespaceDefinitions} to absorb its fields, which is the very next thing that
     * happens -- an entry only in this pass's own map would be invisible to it.
     */
    private final BiConsumer<String, TypeDefinition> publish;

    /**
     * How a closed held body becomes an ordinary constructor body -- the constructor's own
     * compiled reader, the same one a written {@code !array { ... }} binds through. Using it is what makes
     * {@code min_items: "two"} an ordinary read error rather than a check this class would have to grow: the
     * bindings a template defers are exactly the ones a closed instance has always had checked for it.
     *
     * <p>{@code null} for a caller with no compiled meta reader to offer -- every hand-built test fixture,
     * and the bootstrap. Closing an open <em>instance</em> template then fails loudly instead of silently
     * producing an entry with an unread body; a record template needs none of this and is unaffected.
     */
    private final DefinitionMetaReader metaReader;

    /**
     * The entry names desugaring generated rather than the author writing them.
     *
     * <p><b>An application of one is machinery, not a use site.</b> Closing an authored template records the
     * application in an instantiation entry, because {@code grid<pixel, 3>} is something someone wrote and
     * §8.2 keys identity on it. Closing a generated open synthetic records nothing: nobody wrote
     * {@code array_p0_p1_p1_06c4e11f<pixel, 3>}, and an entry named for it would key identity on an internal
     * name D6 is explicit must not be relied on.
     */
    private final Set<String> generated;

    TemplateMaterialiser(DefinitionGetter namespace, BiConsumer<String, TypeDefinition> publish) {
        this(namespace, publish, null, Set.of());
    }

    TemplateMaterialiser(DefinitionGetter namespace, BiConsumer<String, TypeDefinition> publish,
            DefinitionMetaReader metaReader, Set<String> generated) {
        this.namespace = namespace;
        this.publish = publish;
        this.metaReader = metaReader;
        this.generated = generated;
    }

    /**
     * Closes every application reachable from {@code entries}, returning the instantiation entries it
     * produced; {@code entries} is rewritten in place, each application replaced by a bare reference.
     *
     * <p>The returned map is separate from {@code entries} because the caller decides where the new entries
     * land -- they are local to this schema and carry no source position, being named by derivation rather
     * than declared.
     */
    Map<String, TypeDefinition> materialise(Map<String, TypeDefinition> entries,
            MaterialisationFailureReporter reporter) {
        TemplateMaterialiser pass = this;
        for (Map.Entry<String, TypeDefinition> entry : entries.entrySet()) {
            if (!entry.getValue().parameters().isEmpty()) {
                // A template's own body is open: `chain<T>` inside `chain` awaits substitution and is not an
                // application to close. Closing it here would mint an entry per level, keyed on the literal
                // parameter name.
                continue;
            }
            try {
                entry.setValue(pass.rewrite(entry.getValue()));
            } catch (TsonSchemaValidationException e) {
                if (reporter == null) {
                    throw e;
                }
                // Reported against the entry that wrote the application, and left as it was: an entry still
                // naming an open template is one the linker reports again, but that second complaint is
                // about the same line and does not invent a new problem.
                reporter.reportFailedApplication(entry.getKey(), e);
            }
        }
        return pass.materialised;
    }

    /**
     * The subset of what {@link #materialise} returned that is a synthetic entry, whose key carries the
     * derived {@code @synthetic} marker (§8.2). Everything else it returned is an instantiation entry, which
     * deliberately carries none.
     */
    Set<String> syntheticNames() {
        return Set.copyOf(synthetics);
    }

    /** Where an application this pass cannot close is reported, entry by entry. */
    @FunctionalInterface
    interface MaterialisationFailureReporter {
        void reportFailedApplication(String entryName, TsonSchemaValidationException error);
    }

    /**
     * The entry a fully-bound application denotes, closing it if this is the first sight of it -- the
     * on-demand half, reached from a supertype or refinement-source position during resolution rather than
     * from the batch pass afterwards. Both share this instance, so an application closed here and the same
     * one met later in a field land on one entry.
     */
    String closeApplication(TypeRef application) {
        return close(application).name();
    }

    /** One definition with every application inside it closed. */
    private TypeDefinition rewrite(TypeDefinition definition) {
        return mapRefs(definition, this::close);
    }

    /**
     * One type-ref with its application closed, or itself when it carries no arguments. Arguments close
     * first, so {@code box<box<text>>} produces the inner entry before the outer one names it.
     *
     * <p>An application this pass cannot close <b>keeps its argument list</b>, rather than collapsing to its
     * bare head. The list is the evidence the author supplied arguments, and the linker reports on what it is
     * handed: a head stripped of its arguments in a {@code source} position -- the one slot whose lookup falls
     * back to the governing meta's structure namespace -- is found there and then faulted for supplying no
     * arguments, which is the opposite of what the author wrote. Keeping them lets the honest verdict through:
     * a name the type-name namespace does not hold is an unresolved reference, applied or not.
     */
    private TypeRef close(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeArgument> arguments = new ArrayList<>();
        for (TypeArgument argument : ref.arguments()) {
            arguments.add(argument instanceof TypeArgument.Ref nested
                    ? new TypeArgument.Ref(close(nested.ref()))
                    : argument);
        }
        String entry = instantiate(ref.name(), arguments);
        return entry == null ? new TypeRef(ref.name(), arguments, ref.annotations()) : TypeRef.of(entry);
    }

    /**
     * The entry name a fully-bound application denotes, creating the entry on first sight, or {@code null}
     * when the head names nothing in scope -- that is {@code TsonSchemaLinker}'s verdict to give as an
     * unresolved reference, not this pass's to guess at.
     */
    private String instantiate(String head, List<TypeArgument> arguments) {
        TypeDefinition template = namespace.getTypeDefinition(head);
        if (template == null) {
            return null; // unresolved head -- the linker's verdict, not this pass's
        }
        List<String> parameters = template.parameters();
        if (parameters.isEmpty()) {
            throw new TsonSchemaValidationException("'" + head + "' declares no type parameters, so '"
                    + head + "<...>' applies arguments to something that takes none (§5.10); drop the "
                    + "argument list");
        }
        if (parameters.size() != arguments.size()) {
            throw new TsonSchemaValidationException("'" + head + "' takes " + parameters.size()
                    + " type argument" + (parameters.size() == 1 ? "" : "s") + " " + parameters + ", but "
                    + arguments.size() + " " + (arguments.size() == 1 ? "was" : "were") + " applied (§5.10)");
        }
        String name = internalName(head, arguments);
        if (aliasClosing.contains(name)) {
            throw new TsonSchemaValidationException("'" + head + "<...>' is a reference template whose own "
                    + "body applies it again, so composing it never reaches a type with a body (§5.10). The "
                    + "chain begins " + chain() + ". A reference template must eventually name a declared "
                    + "type; recursion belongs in a record, tuple or choice body, where a field can carry it");
        }
        if (materialised.containsKey(name) || !closing.add(name)) {
            return name; // already built, or under construction -- the knot-tying case
        }
        if (closing.size() > MAX_CLOSING_DEPTH) {
            closing.remove(name);
            // Named for the *outermost* head, which is the one the author wrote; the head in hand here is
            // whichever link happened to tip the depth over.
            throw new TsonSchemaValidationException("'" + heads.get(0) + "<...>' does not close: "
                    + "materialising it needs more than " + MAX_CLOSING_DEPTH + " nested instantiations and "
                    + "each one differs from the last, so the arguments are growing rather than repeating and "
                    + "there is no finite set of types to build (§5.10). The chain begins " + chain()
                    + ". A recursive template must reach an argument it has already been applied to");
        }
        heads.add(head);
        try {
            // Every shape reaches here, so every shape gets the memo, the depth backstop and one publish
            // path. An open *instance* used to short-circuit ahead of all three, which left a template
            // applying itself (`weird => <T> [weird<T>]`) recursing to a StackOverflowError instead of
            // tying the knot.
            // §5.10's partial application mints nothing at all: the alias *is* the application it names
            // with some arguments still open, so closing it composes the two argument lists and hands back
            // whatever that denotes -- `uuid_pair<int32>` is the entry `pair<text, int32>` already produced.
            // "No intermediate entry per alias hop" is the rule, which is why this returns a name rather
            // than making one, and why it is the head that tells the three cases apart rather than the body.
            if (template.body() instanceof HeldBody open
                    && REFERENCE.equals(open.application().typeRef().orElseThrow())) {
                aliasClosing.add(name);
                return closeHeldAlias(head, template, open, bind(parameters, arguments));
            }
            // A record template's closure is the instantiation itself, where every other held form closes to
            // a synthetic the instantiation then references -- see closeHeldRecord.
            if (template.body() instanceof HeldBody open
                    && RECORD.equals(open.application().typeRef().orElseThrow())) {
                TypeDefinition instantiation =
                        closeHeldRecord(head, template, open, arguments, bind(parameters, arguments));
                materialised.put(name, instantiation);
                publish.accept(name, instantiation);
                return name;
            }
            if (template.body() instanceof HeldBody open) {
                String formName = closeHeldTemplate(head, template, open, bind(parameters, arguments));
                if (generated.contains(head)) {
                    // A generated head closing its own intermediate form: the form entry *is* the answer, and
                    // an instantiation naming this head would carry an internal name into identity.
                    return formName;
                }
                TypeDefinition alias = instantiationOf(head, arguments, formName);
                materialised.put(name, alias);
                publish.accept(name, alias);
                return name;
            }
            // Every open entry's body is held -- a record, composition or refinement template, a sugar
            // form's lift, an alias, and an error placeholder alike (SchemaDesugarer.heldEmptyRecord). The
            // three branches above are the whole of §12.1's open form, told apart by the head they carry
            // rather than by what shape the body arrived in. So this is a broken invariant, not an author
            // error and not a gap.
            throw new IllegalStateException("'" + head + "' declares type parameters but its body is a "
                    + template.body().getClass().getSimpleName() + " -- every open entry's body is held, and "
                    + "nothing else can be substituted into");
        } finally {
            closing.remove(name);
            aliasClosing.remove(name);
            heads.remove(heads.size() - 1);
        }
    }

    /** The first few links of the closing chain, for the termination guard's message. */
    private String chain() {
        List<String> shown = closing.stream().limit(4).toList();
        return String.join(" -> ", shown) + (closing.size() > shown.size() ? " -> ..." : "");
    }

    /** Each parameter of the applied signature against the argument applied for it, in order. */
    private static Map<String, TypeArgument> bind(List<String> parameters, List<TypeArgument> arguments) {
        Map<String, TypeArgument> bindings = new LinkedHashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            bindings.put(parameters.get(i), arguments.get(i));
        }
        return bindings;
    }

    // ── Closing an open instance (§5.10, D7) ─────────────────────────────────────────────────────

    /**
     * The entry an application of an <b>open instance</b> denotes -- a template whose held body is a
     * constructor application rather than a record. Substituting turns its bindings concrete, and the result
     * is no longer a template at all: it is the constructor body those bindings always described, so it is
     * bound through that constructor's own reader and the entry carries an ordinary body.
     *
     * <p><b>Two entries come out of it, because one cannot carry two identities.</b> The body itself is a
     * closed <em>synthetic</em>, named for the form and sourced to the constructor it builds (§8.2) -- an
     * open synthetic's own name is internal, so keying it on the application would make identity depend on an
     * unstable name, and would leave {@code [text]} written directly and {@code [T]} closed to {@code text}
     * on two entries for one type. But the same closure is also an <em>instantiation</em> of the template,
     * and §8.2 keys that on the flattened application. So this publishes the synthetic and returns a
     * reference entry pointing at it, whose {@code source} is the application.
     *
     * <p>Without the second entry nothing in resolver output records that {@code grid<pixel, 3>} was ever
     * written: the field would name the array directly, and the template's name would vanish. The record
     * template shape gets this for free, since substituting a record yields a record -- structurally distinct
     * from any synthetic, so it can be the instantiation itself.
     */
    private String closeHeldTemplate(String head, TypeDefinition template, HeldBody open,
            Map<String, TypeArgument> bindings) {
        String target = open.application().typeRef().orElseThrow();
        Closed closed = closeHeld(head, template, open, bindings);
        // Named before the entry is built and from the wire slots as written, which is what keeps one type on
        // one entry: the desugar phase lifts innermost-first, so a form it writes already names the entry its
        // inner form became, and a form closed here has to agree with it or `[[pixel; 3]; 3]` written out and
        // `grid<pixel, 3>` closed would be two entries for one type.
        List<RecordValue.Field> fields =
                closed.wire() instanceof RecordValue record ? record.fields() : List.of();
        String formName = SchemaDesugarer.internalName(target, fields);
        if (namespace.getTypeDefinition(formName) != null) {
            return formName; // already built, here or by the desugar phase -- one entry per form, schema-wide
        }
        TypeDefinition definition = new TypeDefinition(Optional.of(TypeRef.of(target)), template.kind(),
                List.of(), false, List.of(), List.of(), Optional.empty(), closed.body());
        materialised.put(formName, definition);
        synthetics.add(formName);
        publish.accept(formName, definition);
        return formName;
    }

    /**
     * A held <b>alias</b> closed: §5.10's partial application, which mints no entry of its own.
     *
     * <p>The first two steps are every held body's -- substitute the parameters, then close the application
     * standing in a slot. What differs is what is left afterwards: nothing to build. {@code
     * uuid_pair<int32>} composed to {@code pair<text, int32>}, and closing that already produced the entry,
     * so its name is the answer. §5.10 is explicit that an alias hop mints no intermediate entry; the origin
     * survives in the composed entry's own {@code source}.
     *
     * <p><b>The knot-tying memo cannot serve this path</b>, which is why {@link #aliasClosing} exists. That
     * memo answers a recursive application with the name of the entry under construction, and this
     * constructs none -- so a self-applying alias ({@code loop => <B> loop<B>}) would be handed a name
     * nothing ever defines. {@link #close} checks {@code aliasClosing} before reaching here and reports the
     * cycle instead.
     */
    private String closeHeldAlias(String head, TypeDefinition template, HeldBody open,
            Map<String, TypeArgument> bindings) {
        CoreValue substituted = substitute(open.application().coreValue(), head, template.parameters(),
                bindings);
        CoreValue closed = closeApplications(substituted);
        CoreValue target = closed instanceof RecordValue record ? field(record, TARGET).orElse(null) : null;
        if (!(target instanceof TokenValue token)) {
            throw new IllegalStateException("'" + head + "<...>' is an alias whose target did not close to a "
                    + "name: " + target + " -- SchemaDesugarer writes `!reference { target: <type_ref> }` and "
                    + "closeApplications reduces an application there to the entry it denotes");
        }
        return token.text();
    }

    /**
     * A <b>record</b> template's closure, which is the instantiation entry itself rather than a synthetic
     * with a reference to it. Substituting a record yields a record -- what the author declared, not a form
     * derived from a sugar spelling -- so there is nothing for the extra hop to record, and the entry carries
     * the application in its own {@code source} the way §8.2 says every instantiation does.
     *
     * <p><b>Which is why the two shapes part company here and nowhere earlier.</b> Everything up to this
     * point is common: one held body, one substitution, one set of closed inner applications. What differs is
     * only what the result <em>is</em> -- a form that needs a name of its own, or the type the author named
     * by writing the application.
     */
    private TypeDefinition closeHeldRecord(String head, TypeDefinition template, HeldBody open,
            List<TypeArgument> arguments, Map<String, TypeArgument> bindings) {
        Closed closed = closeHeld(head, template, open, bindings);
        return new TypeDefinition(Optional.of(new TypeRef(head, arguments)), template.kind(), List.of(),
                template.constructor(), template.supertypes(), template.subtypes(), Optional.empty(),
                fixRoutedValues(closed.body()));
    }

    /** A held body substituted, its inner applications closed, and read back through its constructor. */
    private Closed closeHeld(String head, TypeDefinition template, HeldBody open,
            Map<String, TypeArgument> bindings) {
        String target = open.application().typeRef().orElseThrow();
        // One walk does what three steps used to: a parameter in a slot, a parameter inside an application a
        // slot holds (`tree<p0>` becoming `tree<text>`), and a parameter inside a collection are all the same
        // thing here -- a token in a tree -- because the body was never read against the constructor's
        // vocabulary in the first place.
        CoreValue substituted = substitute(open.application().coreValue(), head, template.parameters(), bindings);
        CoreValue wire = closeApplications(substituted);
        if (metaReader == null) {
            throw new IllegalStateException("'" + head + "<...>' closes to a '" + target + "' body, and this "
                    + "materialiser was built without a compiled meta reader to bind it through");
        }
        try {
            return new Closed(wire, metaReader.read(target, new DataValue(List.of(), Optional.of(target), wire)));
        } catch (TsonReadException e) {
            // The bindings a template defers are checked here and nowhere else (§8.2): `<T, N> [T; N]` is a
            // fine declaration, and `vector<text, "two">` is where it stops being one.
            throw new TsonSchemaValidationException("'" + head + "<...>' substitutes into a body that is not "
                    + "valid data for '" + target + "', the constructor's own constraint vocabulary -- "
                    + e.getMessage(), e);
        }
    }

    /** A closed held body, and the wire form it was read from -- the one an entry name derives from. */
    private record Closed(CoreValue wire, Top body) {
    }

    /**
     * §5.7's fixation, applied where the section says it happens: "fixation happens downstream, where values
     * are concrete". A field routed by {@code = P} is held as {@code state: REQUIRED} with the parameter
     * standing in {@code value}, and a REQUIRED field carrying a value is that and nothing else -- a closed
     * REQUIRED field has none, which is what {@code REQUIRED_FIXED} means. Once substitution has made the
     * value concrete the field takes the state its literal spelling would have had. A {@code ~ P} default
     * arrives as {@code REQUIRED_DEFAULT} and stays one: data may still override it.
     */
    private static Top fixRoutedValues(Top body) {
        if (!(body instanceof RecordBody record)) {
            return body;
        }
        return new RecordBody(record.supertypes(), record.fields().stream()
                .map(field -> field.state() == FieldState.REQUIRED && field.value().isPresent()
                        ? field.withState(FieldState.REQUIRED_FIXED)
                        : field)
                .toList(), record.groups());
    }

    /**
     * Every application still written in {@code type_ref}'s record form, closed to a bare reference to the
     * entry it denotes -- the inverse of the shape {@code SchemaDesugarer} writes when a slot holds one.
     *
     * <p>It runs on the wire value rather than on the body read from it because the <em>name</em> depends on
     * it: {@code SchemaDesugarer.internalName} reads the slots as written, and the desugar phase expands
     * innermost-first, so by the time it names an outer form the inner one is already a bare name. Closing
     * here in the same order is what makes the two phases agree on what a form is called.
     */
    private CoreValue closeApplications(CoreValue value) {
        return switch (value) {
            case RecordValue record when isApplication(record) ->
                    new TokenValue(close(typeRefOf(record)).name(), TokenForm.UNQUOTED);
            case RecordValue record -> new RecordValue(record.fields().stream()
                    .map(field -> new RecordValue.Field(field.name(),
                            rescope(field.value(), closeApplications(field.value().value().coreValue()))))
                    .toList());
            case ArrayValue array -> new ArrayValue(array.elements().stream()
                    .map(element -> rescope(element, closeApplications(element.value().coreValue()))).toList());
            default -> value;
        };
    }

    /**
     * {@code type_ref}'s record form is the one shape carrying both members; a bare name is a token, and a
     * {@code type_argument} carries {@code name} or {@code value} but never {@code arguments}. Package-visible
     * so {@link HeldBody} recognises an application by the same test that closes one -- a held body is written
     * by one phase and read by two, and a second opinion about what an application looks like is what makes
     * one of them wrong.
     */
    static boolean isApplication(RecordValue record) {
        return field(record, NAME).isPresent() && field(record, ARGUMENTS).isPresent();
    }

    /** {@code { name: head  arguments: [ ... ] }} back as the reference it spells. */
    static TypeRef typeRefOf(RecordValue record) {
        CoreValue name = field(record, NAME).orElseThrow();
        String head = name instanceof TokenValue token ? token.text()
                : typeRefOf((RecordValue) name).name();
        List<TypeArgument> arguments = new ArrayList<>();
        if (field(record, ARGUMENTS).orElseThrow() instanceof ArrayValue array) {
            for (ScopedValue element : array.elements()) {
                arguments.add(argumentOf((RecordValue) element.value().coreValue()));
            }
        }
        return new TypeRef(head, arguments);
    }

    /** One argument record: {@code value} carries a literal, {@code name} a reference, simple or compound. */
    private static TypeArgument argumentOf(RecordValue argument) {
        Optional<CoreValue> literal = field(argument, VALUE);
        if (literal.isPresent()) {
            TokenValue token = (TokenValue) literal.get();
            return new TypeArgument.Value(new Token(token.text(), switch (token.form()) {
                case UNQUOTED -> Token.Form.UNQUOTED;
                case SINGLE_LINE_QUOTED -> Token.Form.SINGLE_LINE_QUOTED;
                case MULTI_LINE_QUOTED -> Token.Form.MULTI_LINE_QUOTED;
            }));
        }
        CoreValue name = field(argument, NAME).orElseThrow();
        return new TypeArgument.Ref(name instanceof TokenValue token ? TypeRef.of(token.text())
                : typeRefOf((RecordValue) name));
    }

    private static Optional<CoreValue> field(RecordValue record, String name) {
        return record.fields().stream().filter(f -> f.name().equals(name))
                .map(f -> f.value().value().coreValue()).findFirst();
    }

    /**
     * The held body with every token naming one of the template's parameters replaced by the argument applied
     * for it -- at any depth, in a value slot, a type slot, an argument list, or inside a collection alike.
     *
     * <p><b>A held token needs no channel label, and that is the whole economy of holding.</b> A typed open
     * vocabulary has to record which kind of thing each slot was bound to, because a bare token in a value
     * slot is a literal and nothing else; here the body is uninterpreted until this substitution finishes, so
     * §8.1's shadowing rule decides it -- a token that resolves into {@code parameters} is a parameter, and
     * anything else is what it looks like. The one place the channel still shows is inside a {@code type_ref}
     * record, whose {@code name} member takes a reference: a parameter there bound to a literal moves to the
     * {@code value} member, since an argument list distinguishes the two by which member holds it.
     *
     * <p><b>An argument may itself be open, and that is a supported use rather than an accident.</b>
     * {@code DefinitionResolver} substitutes an operand's held body with the arguments an enclosing
     * declaration wrote, which may be that declaration's own parameters -- the result is a held body still
     * carrying them, absorbed as fields and closed when the enclosing declaration is. Nothing here has to
     * know: a binding is a {@link TypeArgument} either way, and this walk never asks whether the token it
     * writes is concrete.
     */
    static CoreValue substitute(CoreValue value, String head, List<String> parameters,
            Map<String, TypeArgument> bindings) {
        return switch (value) {
            case TokenValue token when token.form() == TokenForm.UNQUOTED
                    && parameters.contains(token.text()) ->
                    argumentValue(argumentFor(token.text(), head, bindings));
            case ArrayValue array -> new ArrayValue(array.elements().stream()
                    .map(element -> rescope(element, substitute(element.value().coreValue(), head, parameters,
                            bindings))).toList());
            case RecordValue record -> new RecordValue(record.fields().stream()
                    .map(field -> substituteField(field, head, parameters, bindings)).toList());
            default -> value;
        };
    }

    /**
     * One field of a held record, with {@code type_ref}'s own {@code name}/{@code value} split honoured: a
     * {@code name} member bound to a value argument is that argument's literal on the {@code value} member,
     * because §8.1 tells a reference argument from a literal one by which member carries it.
     */
    private static RecordValue.Field substituteField(RecordValue.Field field, String head,
            List<String> parameters, Map<String, TypeArgument> bindings) {
        CoreValue held = field.value().value().coreValue();
        if (NAME.equals(field.name()) && held instanceof TokenValue token
                && token.form() == TokenForm.UNQUOTED && parameters.contains(token.text())
                && argumentFor(token.text(), head, bindings) instanceof TypeArgument.Value literal) {
            return new RecordValue.Field(VALUE, rescope(field.value(), argumentValue(literal)));
        }
        return new RecordValue.Field(field.name(),
                rescope(field.value(), substitute(held, head, parameters, bindings)));
    }

    private static TypeArgument argumentFor(String parameter, String head, Map<String, TypeArgument> bindings) {
        TypeArgument argument = bindings.get(parameter);
        if (argument == null) {
            // A parameter of an enclosing template, still open: this application is not the one that closes
            // it. Nothing today reaches here -- an application is closed only once every argument is concrete
            // -- and saying so is what keeps that true rather than assuming it.
            throw new UnsupportedOperationException("'" + head + "<...>' holds the parameter '" + parameter
                    + "', which this application does not supply, and closing an open form onto another open "
                    + "form is not implemented (§5.10)");
        }
        return argument;
    }

    /**
     * One argument in the held spelling, standing where the parameter it binds stood.
     *
     * <p>A <b>literal</b> is its own token. A <b>reference</b> goes through {@link SchemaDesugarer#refValue},
     * which spells a no-argument one positionally (§5.6, where a reference and a literal look alike) and one
     * carrying arguments in {@code type_ref}'s own record form. Writing the head name alone would be the
     * shorter code and is wrong: {@code box<inner<T>>} would put {@code inner} where {@code inner<T>} belongs
     * and drop the argument list with no diagnostic, because a token has nowhere to keep it.
     *
     * <p><b>Reusing the desugarer's producer is the requirement, not the convenience.</b> Two spellings of
     * one form are two entries for one type, an entry name deriving from what is written -- so the phase that
     * substitutes and the phase that lifts have to agree down to whether a no-argument reference is a token
     * or a record. Sharing the function is what makes that true by construction.
     *
     * <p>Every slot a parameter can occupy takes either spelling: a field's {@code type}, an {@code
     * element_type}, a {@code variants}/{@code elements} entry, and {@code type_argument}'s own {@code name}
     * member are all typed {@code type_ref}. The one position that would not is a {@code type_ref}'s
     * <em>head</em> -- {@code type_ref.name} is a {@code type_name} -- and a parameter cannot stand there:
     * {@code T<text>} applies a parameter, which is no form §12.1 has.
     */
    private static CoreValue argumentValue(TypeArgument argument) {
        return switch (argument) {
            case TypeArgument.Ref reference -> SchemaDesugarer.refValue(reference.ref());
            case TypeArgument.Value value -> new TokenValue(value.value().text(),
                    switch (value.value().form()) {
                        case UNQUOTED -> TokenForm.UNQUOTED;
                        case SINGLE_LINE_QUOTED -> TokenForm.SINGLE_LINE_QUOTED;
                        case MULTI_LINE_QUOTED -> TokenForm.MULTI_LINE_QUOTED;
                    });
        };
    }

    /** The same scoped value carrying a rewritten core value -- annotations and type-ref kept as written. */
    private static ScopedValue rescope(ScopedValue original, CoreValue rewritten) {
        DataValue value = original.value();
        return new ScopedValue(original.schemaRef(),
                new DataValue(value.annotations(), value.typeRef(), rewritten));
    }

    /**
     * The instantiation entry for an application whose closure is a synthetic: a reference to that synthetic,
     * sourced to the application itself. {@code Reference} bodies are collapsed by the compiler, so the hop
     * costs nothing at read time -- what it buys is that §8.2's "instantiation entries are keyed on the
     * flattened application recorded in {@code source}" is true of this template shape too, not only of the
     * record one.
     */
    private static TypeDefinition instantiationOf(String head, List<TypeArgument> arguments, String formName) {
        return new TypeDefinition(Optional.of(new TypeRef(head, arguments)), TypeKind.REFERENCE, List.of(),
                false, List.of(), List.of(), Optional.empty(), new Reference(TypeRef.of(formName)));
    }

    /**
     * A type-ref with each parameter reference replaced by the argument bound to it.
     *
     * <p><b>It recurses into arguments</b>, which is what makes recursion work: the head of {@code chain<T>}
     * is {@code chain}, not a parameter, so binding only the outer name would leave {@code T} in place and
     * mint an entry per level. §5.10 admits no head abstraction ({@code v: T<...>} is not spellable), so a
     * parameter is always a whole ref -- but it may be a whole ref sitting in an argument list.
     */
    private static TypeRef bindRef(TypeRef ref, Map<String, TypeArgument> bindings) {
        TypeArgument bound = bindings.get(ref.name());
        if (bound != null) {
            if (bound instanceof TypeArgument.Ref reference) {
                return reference.ref();
            }
            // A value argument reaching a type position: the applied signature disagrees with the body's use
            // of the parameter. §5.10 infers a parameter's kind from its use, so this is the author's error.
            throw new TsonSchemaValidationException("'" + ref.name() + "' is used as a type but a value was "
                    + "applied for it (§5.10)");
        }
        if (ref.arguments().isEmpty()) {
            return ref;
        }
        List<TypeArgument> arguments = new ArrayList<>();
        for (TypeArgument argument : ref.arguments()) {
            arguments.add(bindArgument(argument, bindings));
        }
        return new TypeRef(ref.name(), arguments);
    }

    /**
     * One argument of an application inside a template body, with a parameter replaced by whatever was bound
     * to it -- <b>on the channel it was bound on</b>, which is what separates this from {@link #bindRef}.
     * An argument list is the one position where a type and a value are equally at home, so a value parameter
     * passed straight through ({@code array_p0<N>} inside {@code <N> { a: [text; N] } }) stays a value; a
     * parameter in any other position is a type by construction, and a value arriving there is the error
     * {@link #bindRef} reports.
     */
    private static TypeArgument bindArgument(TypeArgument argument, Map<String, TypeArgument> bindings) {
        if (!(argument instanceof TypeArgument.Ref nested)) {
            return argument;
        }
        TypeArgument bound = bindings.get(nested.ref().name());
        return bound != null && nested.ref().arguments().isEmpty() ? bound
                : new TypeArgument.Ref(bindRef(nested.ref(), bindings));
    }

    // ── Structural walks ─────────────────────────────────────────────────────────────────────────

    /** Every {@link TypeRef} a definition holds, mapped -- {@code source}, and whatever its body carries. */
    private static TypeDefinition mapRefs(TypeDefinition definition, UnaryOperator<TypeRef> map) {
        Optional<TypeRef> source = definition.source().map(map);
        return new TypeDefinition(source, definition.kind(), definition.parameters(),
                definition.constructor(), definition.supertypes(), definition.subtypes(),
                definition.disjoint(), mapBodyRefs(definition.body(), map), definition.position(),
                definition.annotations());
    }

    /**
     * Package-visible so {@code TemplateRegularity} can walk a body by the same code that rewrites one --
     * a body shape added here must not need remembering in a second place.
     */
    static Top mapBodyRefs(Top body, UnaryOperator<TypeRef> map) {
        return switch (body) {
            case RecordBody record -> new RecordBody(record.supertypes(),
                    record.fields().stream().map(field -> field.withType(map.apply(field.type()))).toList(),
                    record.groups());
            case ArrayBody array -> new ArrayBody(map.apply(array.elementType()), array.state(),
                    array.unordered(), array.uniqueItems(), array.minItems(), array.maxItems());
            case MapBody mapBody -> new MapBody(map.apply(mapBody.keyType()), map.apply(mapBody.valueType()),
                    mapBody.state(), mapBody.minItems(), mapBody.maxItems());
            case TupleBody tuple -> new TupleBody(tuple.elements().stream()
                    .map(element -> new TupleElement(map.apply(element.elementType()), element.state())).toList());
            case ChoiceBody choice -> new ChoiceBody(choice.variants().stream().map(map).toList());
            // An alias's target maps like any other reference, arguments and all -- which is what lets a
            // closed alias follow its own `source` onto the entry materialisation minted for it, and a
            // partial application keep the arguments it binds.
            case Reference reference -> new Reference(map.apply(reference.target()));
            // A held body maps nothing: its references are tokens that have not been resolved against
            // anything yet, and rewriting one would be rewriting a name whose meaning is not settled until
            // substitution supplies the arguments.
            default -> body; // an atom body holds no type references
        };
    }

    /**
     * {@code head_arg_arg_hash} -- §8.2's own recommendation for an internal name, "a readable head plus a
     * structural hash", and the same construction {@code SchemaDesugarer} uses for an injected sugar form.
     * The hash runs over a rendering built here, never over a record's {@code toString} (documented as
     * subject to change) or its {@code hashCode} (free to differ between runs): {@code String.hashCode} is
     * specified exactly, so hashing a string built here is deterministic by contract.
     */
    private static String internalName(String head, List<TypeArgument> arguments) {
        StringBuilder readable = new StringBuilder(InternalName.part(head));
        StringBuilder canonical = new StringBuilder();
        appendText(canonical.append('A'), head);
        canonical.append('(');
        for (TypeArgument argument : arguments) {
            switch (argument) {
                case TypeArgument.Ref ref -> {
                    readable.append('_').append(InternalName.part(ref.ref().name()));
                    appendRef(canonical.append('r'), ref.ref());
                }
                case TypeArgument.Value value -> {
                    readable.append('_').append(InternalName.part(canonicalText(value.value())));
                    // The form by name, not ordinal: inserting a constant would renumber every ordinal.
                    appendText(canonical.append('v'), value.value().form().name());
                    appendNumberAware(canonical, value.value());
                }
            }
        }
        canonical.append(')');
        return readable.append('_').append(String.format("%08x", canonical.toString().hashCode())).toString();
    }

    private static void appendRef(StringBuilder out, TypeRef ref) {
        appendText(out.append('n'), ref.name());
        out.append('(');
        for (TypeArgument argument : ref.arguments()) {
            switch (argument) {
                case TypeArgument.Ref nested -> appendRef(out.append('r'), nested.ref());
                case TypeArgument.Value value -> {
                    appendText(out.append('v'), value.value().form().name());
                    appendNumberAware(out, value.value());
                }
            }
        }
        out.append(')');
    }

    /** Length-first, so concatenation stays unambiguous whatever the text contains. */
    private static void appendText(StringBuilder out, String text) {
        out.append(text.length()).append(':').append(text);
    }

    /** A value argument's readable segment, with §4.3's numeric equivalence applied ({@link NumericIdentity}). */
    private static String canonicalText(Token token) {
        return NumericIdentity.textOf(token.text(), token.form() == Token.Form.UNQUOTED);
    }

    /**
     * A value argument's contribution to the hashed rendering. A number writes its base-type kind and its
     * canonical magnitude as two fields where anything else writes its text as one; every field being
     * length-prefixed, no token's own text can be mistaken for a tagged number.
     */
    private static void appendNumberAware(StringBuilder out, Token token) {
        NumericIdentity.Canonical canonical =
                NumericIdentity.of(token.text(), token.form() == Token.Form.UNQUOTED);
        if (canonical != null) {
            appendText(out, canonical.kind());
        }
        appendText(out, canonical == null ? token.text() : canonical.text());
    }
}
