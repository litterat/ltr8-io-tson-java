package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SchemaMapDef;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.schema.SchemaPaths;
import io.ltr8.tson.schema.TsonBundledSchemas;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The {@link DataNameBinder} for the schema model: the names the kernel, meta.tn, declares -- its root vocabulary and
 * its members of {@code meta}, {@code atom}, {@code numeric}, {@code unicode}, {@code net} and {@code binary} -- and
 * the members numeric.tn adds to {@code numeric}, temporal.tn declares under {@code temporal} and net.tn adds to
 * {@code net}.
 *
 * <p><b>One rule, the one a consumer's dotted name follows</b> ({@link DataNameBinder.DefaultDataNameBinder}): every
 * segment before the last is a sub-package of {@code io.ltr8.tson.schema}, and the last is mangled from snake_case to
 * PascalCase, so {@code numeric.integer_type} is {@code io.ltr8.tson.schema.numeric.IntegerType}, {@code
 * unicode.text_type} is {@code unicode.TextType} and {@code net.ipv4_type} is {@code net.Ipv4Type}. The model has one
 * package per namespace for exactly this reason: a class lives where its entry is declared. {@link #ALIASES}, keyed
 * by the full path, covers a name the mangling does not reach its class from ({@code temporal.datetime_type} is
 * {@code temporal.DateTimeType}). A root name binds into {@code io.ltr8.tson.schema} itself, where no class is any
 * entry's, so none binds a class and the bind lookup moves along its chain.
 *
 * <p><b>It answers only for that vocabulary, by declaring document.</b> Those entries are the ones whose values the
 * schema model describes; the libraries' roots, core.tn and policy.tn declare ordinary types over them, whose values are
 * data, and a member a consumer's schema contributes to an open map ({@code meta}, {@code numeric}, …) is the
 * consumer's own. The packages also hold classes no schema declares -- {@code Token}, {@code EntryKind}, {@code
 * OpenEntry}, {@code ValueEntry} and the like, the resolver's own model -- and a name that merely mangles to one of
 * them ({@code meta.token}) is not this binder's: it refuses it, so a consumer's type of that name binds through the
 * consumer's binder. The vocabulary is read from the bundled sources themselves, once ({@link Vocabulary}), so it
 * cannot drift from them.
 *
 * <p>A constructor's description in the kernel ({@code record => meta.product & { fields: ... }}) has the
 * shape of the {@code *Body} class that represents a bound instance of it ({@link
 * io.ltr8.tson.schema.meta.RecordBody}, {@link io.ltr8.tson.schema.meta.ArrayBody}, ...), and each such class's own
 * {@code @Typename} is the constructor's path, so the schema-name-to-class direction needs the {@code "_body"}
 * suffix added explicitly ({@link #ALIASES}: {@code meta.record} is {@code meta.RecordBody}). A constructor that
 * tightens another -- {@code meta.set_type} over {@code meta.array}, {@code enum} over {@code meta.enum_type} -- needs
 * no alias: it restates fields and adds none, and the bind lookup falls back along its chain to the constructor it
 * tightens ({@code ValueReaderContext.bindingNamesFor}). {@code meta.ref} over {@code meta.reference_type} is the
 * exception, its name mangling to {@link io.ltr8.tson.schema.meta.Ref}, the value a host receives by name, so it is
 * aliased to the body its instances are.
 *
 * <p>Some entries resolve to a class that is not a record: {@code meta.atom}/{@code meta.product}/{@code
 * meta.sum}/{@code meta.top} mangle to the sealed marker interfaces {@link io.ltr8.tson.schema.meta.Atom}/{@link
 * io.ltr8.tson.schema.meta.Product}/{@link io.ltr8.tson.schema.meta.Sum}/{@link io.ltr8.tson.schema.meta.Top}. This
 * binder resolves the class either way; whether it is usable as a record is the reader's decision, surfacing as a
 * compile-time failure for that one entry.
 *
 * <p><b>The vocabulary is fixed; the binder is not the last word.</b> A schema may hold values of types the kernel
 * never heard of ({@code create_order => !operation { ... }}), and a data document values of its own types; both
 * bind to the consumer's classes through one context. {@link #over} is that context: a name in the vocabulary binds
 * here and nowhere else, so a consumer cannot rebind it, and every other name goes to the consumer's binder.
 */
public final class SchemaModelNameBinder {

    private SchemaModelNameBinder() {
    }

    /** The package a vocabulary name's segments before the last are sub-packages of. */
    private static final String PACKAGE = "io.ltr8.tson.schema";

    /** Full path to the path its class is mangled from, where the entry's own name does not reach it. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("meta.record", "meta.record_body"),

            Map.entry("meta.array", "meta.array_body"),
            Map.entry("meta.map", "meta.map_body"),
            Map.entry("meta.namespace", "meta.namespace_body"),
            Map.entry("meta.tuple", "meta.tuple_body"),
            Map.entry("meta.choice", "meta.choice_body"),
            Map.entry("meta.reference_type", "meta.reference_body"),
            Map.entry("meta.ref", "meta.reference_body"),
            Map.entry("meta.enum_type", "meta.enum_body"),

            Map.entry("temporal.datetime_type", "temporal.date_time_type"));

    private static final DataNameBinder MANGLED = new DataNameBinder.DefaultDataNameBinder(Set.of(PACKAGE), ALIASES);

    /**
     * The schema model's binder: a name the kernel declares, or a library declares under its own map, bound by its
     * path, each segment before the last a sub-package of {@code io.ltr8.tson.schema}; any other name is refused,
     * whatever class it would mangle to.
     */
    public static final DataNameBinder INSTANCE = name -> {
        if (!Vocabulary.NAMES.contains(name)) {
            throw new DataBindException("'" + name + "' is declared by neither the kernel nor under a bundled "
                    + "library's 'numeric', 'temporal' or 'net' map, so the schema model does not bind it");
        }
        return MANGLED.resolve(name);
    };

    /**
     * The schema model's default context -- {@link ResolverBindContext}'s own built-in atom
     * registrations, plus {@link #INSTANCE}. A {@link DataNameBinder} is fixed at a {@link DataBindContext}'s
     * own construction, so it can't be attached after the fact -- a caller binding their own schema to their own
     * Java library builds the context with {@link #contextExtendedWith} or {@link #over} instead and passes it to
     * {@link ValueReaderFactoryRegistry#bind}.
     */
    public static DataBindContext defaultContext() {
        return context(INSTANCE);
    }

    /**
     * {@link #defaultContext()} extended with the names {@code additional} binds -- the records a schema's value
     * entries are values of ({@code create_order => !operation { ... }}) and a data document's own types -- whose
     * values bind to that consumer's Java classes.
     *
     * <p>Composition, never replacement: a name in {@link #INSTANCE}'s vocabulary binds there and {@code
     * additional} is asked for every other name ({@link #extendedWith}). So a consumer cannot shadow {@code
     * record}/{@code enum}, and cannot lose {@link ResolverBindContext}'s registrations by building a context
     * that forgets them -- what the extension adds is names, and nothing else.
     */
    public static DataBindContext contextExtendedWith(DataNameBinder additional) {
        return context(extendedWith(additional));
    }

    /**
     * The one context a processor binds schemas and data through, from the context a consumer configured: that
     * context's binder, profile and atom registrations, with {@link #INSTANCE} composed with its binder ({@link
     * #extendedWith}), and the schema model's own registrations added where it lacks them ({@link
     * ResolverBindContext#registerAbsent}). The names of the kernel and under the libraries' maps are fixed; every
     * other name is the consumer's binder's to answer.
     */
    public static DataBindContext over(DataBindContext consumer) {
        Objects.requireNonNull(consumer, "consumer");
        DataBindContext.Builder builder = consumer.toBuilder().nameBinder(extendedWith(consumer.nameBinder()));
        return ResolverBindContext.registerAbsent(builder).build();
    }

    /**
     * {@link #INSTANCE} for a name of its vocabulary, {@code additional} for every other -- the
     * binder half of {@link #contextExtendedWith}, for a caller assembling a {@link DataBindContext} themselves.
     *
     * <p>Decided by the name, never by a failed lookup: a vocabulary name {@link #INSTANCE} binds no class for
     * ({@code enum}, {@code set}) is still not the consumer's, so the bind lookup moves on to the constructor it
     * tightens rather than to a consumer class of that name; and a name outside the vocabulary never reaches the
     * schema model, whatever class it would mangle to. A {@link DataBindException} from {@code additional}
     * propagates as it stands: it names what that consumer's own binder searched, which is where the name was
     * expected to be.
     */
    public static DataNameBinder extendedWith(DataNameBinder additional) {
        Objects.requireNonNull(additional, "additional");
        return name -> Vocabulary.NAMES.contains(name) ? INSTANCE.resolve(name) : additional.resolve(name);
    }

    private static DataBindContext context(DataNameBinder binder) {
        return ResolverBindContext.registerDefaults(DataBindContext.builder().nameBinder(binder)).build();
    }

    /**
     * The names {@link #INSTANCE} answers for, read from the bundled sources by the schema parser on first use: every
     * entry the kernel, meta.tn, declares -- its root vocabulary and its members of its six maps, by path -- and
     * the members numeric.tn, temporal.tn and net.tn declare under {@code numeric}, {@code temporal} and {@code net}.
     * The vocabulary is fixed by declaring document, so a member a consumer's schema contributes to an open map is the
     * consumer's binder's to answer. An {@link #ALIASES} key naming no entry would be an alias nothing can ask for, so
     * one is a broken invariant.
     */
    private static final class Vocabulary {

        static final Set<String> NAMES = read();

        private static Set<String> read() {
            Set<String> names = new LinkedHashSet<>();
            for (String id : Stream.concat(Stream.of(TsonBundledSchemas.META_ID),
                    TsonBundledSchemas.LIBRARY_IDS.stream()).toList()) {
                // The kernel's every entry; each library's every entry under its own map.
                boolean kernel = id.equals(TsonBundledSchemas.META_ID);
                Map<String, SchemaMap.Declaration> declared = new TsonSchemaParser(TsonBundledSchemas.fetch(id))
                        .parseSchemaDocument().body().declarations();
                declared.forEach((name, declaration) -> {
                    boolean map = declaration.typeDef() instanceof SchemaMapDef;
                    if (!map && (SchemaPaths.isPath(name) || kernel)) {
                        names.add(name);
                    }
                });
            }
            for (String alias : ALIASES.keySet()) {
                if (!names.contains(alias)) {
                    throw new IllegalStateException("alias '" + alias + "' names no entry of meta.tn or of a "
                            + "bundled library's map");
                }
            }
            return Set.copyOf(names);
        }
    }
}
