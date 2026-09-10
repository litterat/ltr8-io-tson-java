package io.ltr8.tson.json;

import java.util.List;
import java.util.ArrayList;
import io.ltr8.tson.schema.TsonSchemaLoader;
import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.Diagnostic;
import io.ltr8.tson.base.ReadException;
import io.ltr8.tson.base.policy.LimitsPolicy;
import io.ltr8.tson.base.policy.ProcessorPolicy;
import io.ltr8.tson.json.stream.JsonEventSource;
import io.ltr8.tson.json.tree.JsonValue;

import java.io.InputStream;
import java.util.Objects;

/**
 * The front door of the JSON stack: the configuration a read is judged under, and the two readers that
 * apply it.
 *
 * <p>The shape {@code Tson} takes over {@code TsonTreeReader}/{@code TsonObjectReader} -- one place a
 * deployment states its {@link ProcessorPolicy}, its binding and where problems go, and two readers that
 * carry it. {@link #treeReader()} produces a {@link JsonValue}; {@link #objectReader()} produces a bound
 * Java object. It holds no schema registry because there is nothing yet to register: the schema-directed
 * decode of [TSON-JSON] §5-§8 is where one arrives, and this is where it will live.
 *
 * <p><b>JEP 540's own entry points are the statics below</b>, over a default configuration. They are the
 * zero-ceremony path this API is named for -- {@code Json.parse(text)} and nothing else to know -- and they
 * stay static because that is what a consumer moving from {@code jdk.incubator.json} will type. A caller
 * who needs a policy, a receiver or a binding builds an instance instead.
 *
 * <p><b>What is borrowed there is the spelling, not the contract.</b> {@code parse} fails the way every
 * read in this library fails -- {@link ReadException} carrying a {@link Diagnostic}, thrown by the default
 * receiver -- rather than the way {@code jdk.incubator.json} fails. The JEP 540 alignment is the {@code
 * tree} package's value model; reading, writing and exceptions follow the TSON side of this library, so a
 * consumer reading both encodings routes on one rule.
 *
 * <p><b>What this class no longer is.</b> It used to reduce events into a tree itself, which put an engine
 * in a front door's name and left the JSON stack with no tree <em>facade</em> at all -- so a tree read
 * could not be given a receiver, a policy or a path, where a bound read could. The reduction is
 * {@code SchemalessTreeReader}'s now, under {@link JsonTreeReader}, and the two readers are peers.
 */
public final class Json {

    private final ProcessorConfig config;

    /** Where a named schema comes from, or null for an instance that can name none. */
    private final JsonCompiledSchemaRegistry schemas;

    private Json(ProcessorConfig config, JsonCompiledSchemaRegistry schemas) {
        this.config = config;
        this.schemas = schemas;
    }

    // ── The front door ───────────────────────────────────────────────────

    /**
     * The unconfigured environment: the default bind vocabulary and the default policy.
     * {@code Tson.standard()} is the TSON text front door's counterpart, and the two answer alike.
     *
     * <p>The bind context is {@link AtomContext#defaultContext()} -- the same vocabulary the other front
     * door starts from, so a class binds the same under both encodings. [TSON-JSON] §5.1 is why that is
     * right rather than merely convenient: a string's content is handed to the atom's own parser exactly as
     * a TSON quoted token's text would be, which makes the vocabulary the type system's rather than either
     * encoding's.
     */
    public static Json standard() {
        return of(ProcessorConfig.defaults());
    }

    /**
     * This encoding under {@code config} -- <b>the same configuration value the TSON text front door takes</b>.
     *
     * <p>That is the point of it living in {@code tson-base}: what a deployment states about reading TSON --
     * what it will admit and spend, where it may obtain a schema, which Java classes its types bind to -- is
     * one statement, and stating it twice is two places for it to differ. A deployment reading both
     * encodings builds one {@code ProcessorConfig} and hands it to both.
     *
     * <p>What the config does <em>not</em> carry is where a schema comes from. Its {@code schemaAccess}
     * states how schema <em>text</em> is fetched, and turning that text into a resolved schema is the TSON
     * engine's -- so this encoding names an already-resolved one through {@link #withSchemas} instead. That
     * is a division of labour rather than a gap: [TSON-JSON] §3.4 binds a document out of band, and a schema
     * document is TSON text whichever encoding the data arrives in.
     */
    public static Json of(ProcessorConfig config) {
        return new Json(Objects.requireNonNull(config, "config"), null);
    }

    /**
     * This instance able to <b>name</b> a schema -- never to author one, which is the split this encoding
     * has by specification: [TSON-JSON] §3.4 binds a document out of band, and there is no such thing as a
     * JSON schema document.
     *
     * <p><b>Obtaining a schema is the TSON engine's job, and that is why this costs no dependency.</b> A
     * schema document is TSON text, so parsing, resolving and linking one belongs where that engine lives;
     * what crosses here is a {@code TsonLinkedSchema}, a value model in {@code tson-schema}. An application
     * reading both encodings resolves its schemas once through {@code Tson} and hands
     * {@code tson.schemaRegistry()} -- or its own loader over one -- to this.
     *
     * <p>The compiled readers are cached per identity on the returned instance, so an application that names
     * its schemas at startup pays the compile once and every read after is a lookup.
     */
    public Json withSchemas(TsonSchemaLoader loader) {
        return new Json(config, JsonCompiledSchemaRegistry.tree(Objects.requireNonNull(loader, "loader")));
    }

    /**
     * The bind context every {@link #objectReader()} from this instance binds through.
     * {@code Tson.dataBindContext()} is the TSON front door's counterpart, and the two answer with the same
     * kind of value on purpose.
     */
    public DataBindContext dataBindContext() {
        return config.dataBindContext();
    }

    /** Everything a read off this instance will admit and spend. */
    public ProcessorPolicy processorPolicy() {
        return config.processorPolicy();
    }

    /**
     * What a read off this instance will spend -- [TSON-JSON] §10.1's bounds, which are §9.1's "in JSON
     * clothing, and the same policy applies with the same defaults", so this is one component of the policy
     * above rather than a second statement. {@code Tson.limitsPolicy()} is the text front door's, and the
     * two answer with the same record from the same configuration.
     */
    public LimitsPolicy limitsPolicy() {
        return config.processorPolicy().limits();
    }

    /**
     * A reader producing a {@link JsonValue} tree, carrying this instance's policy.
     *
     * <p>Schema-aware when this instance was given a loader ({@link #withSchemas}): {@code
     * treeReader().withSchema(uri).readAs(source, rootType)} is §3.4's out-of-band binding. Schemaless
     * otherwise, and {@code read(...)} is the same either way.
     */
    public JsonTreeReader treeReader() {
        JsonTreeReader reader = schemas == null ? JsonTreeReader.standard() : JsonTreeReader.over(schemas);
        return reader.withProcessorPolicy(config.processorPolicy());
    }

    /**
     * Validates {@code source} against {@code schemaUri}'s {@code rootType}, collecting every problem rather
     * than ending at the first -- the peer of {@code Tson.validate}, and what {@code tson validate} runs a
     * {@code .json} input through.
     *
     * <p>An empty list is a conforming document. Nothing is thrown for a document that does not conform, or
     * for one that will not even parse: a collecting read returns nothing and the diagnostics say why.
     *
     * <p>The two arguments are the binding, and a JSON document cannot supply them itself (§3.4). Which is
     * the whole difference from {@code Tson.validate}, whose documents name their own.
     */
    public List<Diagnostic> validate(InputStream source, String schemaUri, String rootType) {
        List<Diagnostic> problems = new ArrayList<>();
        treeReader().withDiagnostics(problems::add).withSchema(schemaUri).readAs(source, rootType);
        return List.copyOf(problems);
    }

    /** {@link #validate(InputStream, String, String)} over a string. */
    public List<Diagnostic> validate(String source, String schemaUri, String rootType) {
        List<Diagnostic> problems = new ArrayList<>();
        treeReader().withDiagnostics(problems::add).withSchema(schemaUri).readAs(source, rootType);
        return List.copyOf(problems);
    }

    /** A reader producing a bound Java object, carrying this instance's binding, policy and receiver. */
    public JsonObjectReader objectReader() {
        return JsonObjectReader.using(config.dataBindContext()).withProcessorPolicy(config.processorPolicy());
    }

    /** A {@link JsonTreeWriter} -- the inverse of {@link #treeReader()}, and total over a tree this reads. */
    public JsonTreeWriter treeWriter() {
        return new JsonTreeWriter();
    }

    /** A {@link JsonObjectWriter} over this instance's bindings -- the inverse of {@link #objectReader()}. */
    public JsonObjectWriter objectWriter() {
        return JsonObjectWriter.using(config.dataBindContext());
    }

    // ── JEP 540's entry points ───────────────────────────────────────────

    /**
     * @throws ReadException if the document is not JSON within [TSON-JSON] §3.1's profile -- the reader's
     *         default receiver is the throwing one, and a syntax failure reaches it like every other
     *         problem, so the fail-fast answer is one type across both encodings and the position and
     *         {@link Diagnostic.Code} travel on {@link ReadException#diagnostic()}. A caller wanting every
     *         problem in one pass takes {@link #treeReader()} with a collecting receiver instead.
     */
    public static JsonValue parse(String source) {
        return JsonTreeReader.standard().read(source);
    }

    /** {@link #parse(String)} under {@code policy} rather than the processor's defaults. */
    public static JsonValue parse(String source, ProcessorPolicy policy) {
        return JsonTreeReader.standard().withProcessorPolicy(policy).read(source);
    }

    /**
     * Off UTF-8 bytes, which is where §3.1's rules actually bite: invalid sequences are refused rather
     * than replaced, and every position carries a real byte offset. {@code source} is not closed here.
     */
    public static JsonValue parse(InputStream source) {
        return JsonTreeReader.standard().read(source);
    }

    /** {@link #parse(InputStream)} under {@code policy} rather than the processor's defaults. */
    public static JsonValue parse(InputStream source, ProcessorPolicy policy) {
        return JsonTreeReader.standard().withProcessorPolicy(policy).read(source);
    }

    /** Off an event source, for a caller with events already in hand. */
    public static JsonValue parse(JsonEventSource events) {
        return JsonTreeReader.standard().read(events);
    }

    // ── Rendering ────────────────────────────────────────────────────────

    /**
     * A pretty-printed rendering, one member or element per line, indented by {@code indent} per level
     * -- JEP 540's spelling of {@link JsonValue#toDisplayString(String)}, which is where the work lives
     * because that is where the string quoting does.
     */
    public static String toDisplayString(JsonValue value, String indent) {
        return value.toDisplayString(indent);
    }

    /** {@link #toDisplayString(JsonValue, String)} at two spaces, the shape most JSON is read in. */
    public static String toDisplayString(JsonValue value) {
        return value.toDisplayString();
    }
}
