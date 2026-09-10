package io.ltr8.tson.json;

import io.ltr8.bind.DataBindContext;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.DiagnosticsReceiver;
import io.ltr8.tson.base.ParseException;
import io.ltr8.tson.base.TsonConfig;
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
 * <p><b>What this class no longer is.</b> It used to reduce events into a tree itself, which put an engine
 * in a front door's name and left the JSON stack with no tree <em>facade</em> at all -- so a tree read
 * could not be given a receiver, a policy or a path, where a bound read could. The reduction is
 * {@code SchemalessTreeReader}'s now, under {@link JsonTreeReader}, and the two readers are peers.
 */
public final class Json {

    private final TsonConfig config;

    private Json(TsonConfig config) {
        this.config = config;
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
        return of(TsonConfig.defaults());
    }

    /**
     * This encoding under {@code config} -- <b>the same configuration value the TSON text front door takes</b>.
     *
     * <p>That is the point of it living in {@code tson-base}: what a deployment states about reading TSON --
     * what it will admit and spend, where it may obtain a schema, which Java classes its types bind to -- is
     * one statement, and stating it twice is two places for it to differ. A deployment reading both
     * encodings builds one {@code TsonConfig} and hands it to both.
     *
     * <p>Not every setting reaches this encoding yet: the schema access waits on [TSON-JSON] §5-§8's
     * schema-directed decode, which is the point at which a JSON document has a schema to obtain at all.
     * Holding the whole value now is what stops that arriving as another setter.
     */
    public static Json of(TsonConfig config) {
        return new Json(Objects.requireNonNull(config, "config"));
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

    /** A reader producing a {@link JsonValue} tree, carrying this instance's policy and receiver. */
    public JsonTreeReader treeReader() {
        return JsonTreeReader.standard().withProcessorPolicy(config.processorPolicy());
    }

    /** A reader producing a bound Java object, carrying this instance's binding, policy and receiver. */
    public JsonObjectReader objectReader() {
        return JsonObjectReader.using(config.dataBindContext()).withProcessorPolicy(config.processorPolicy());
    }

    // ── JEP 540's entry points ───────────────────────────────────────────

    /** @throws ParseException if the document is not JSON within [TSON-JSON] §3.1's profile */
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
