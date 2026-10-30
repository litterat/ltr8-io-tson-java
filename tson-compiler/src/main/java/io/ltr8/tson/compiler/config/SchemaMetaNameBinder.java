package io.ltr8.tson.compiler.config;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.compiler.TsonSchemaParser;
import io.ltr8.tson.compiler.reader.ValueReaderFactoryRegistry;
import io.ltr8.tson.schema.TsonBundledSchemas;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@link DataNameBinder} for the schema model ({@code io.ltr8.tson.schema.meta}): the names meta-kernel.tn and
 * meta.tn declare, bound by a snake_case-to-PascalCase mangling of the name into that package ({@code
 * "integer_size"} -> {@code IntegerSize}, {@code "record_field"} -> {@code RecordField}), with {@link #ALIASES} where
 * the mangling does not reach the class.
 *
 * <p><b>It answers only for that vocabulary.</b> The two schemas are the ones whose values the schema model
 * describes; core.tn, net.tn and policy.tn declare ordinary types over them, whose values are data. The package
 * also holds classes no schema declares -- {@code Token}, {@code EntryKind}, {@code OpenEntry}, {@code ValueEntry},
 * {@code AtomCoherence} and the like, the resolver's own model -- and a name that merely mangles to one of them
 * ({@code token}) is not this binder's: it refuses it, so a consumer's type of that name binds through the
 * consumer's binder. The vocabulary is read from the bundled sources themselves, once ({@link Vocabulary}), so it
 * cannot drift from them.
 *
 * <p>A constructor's description in meta-kernel ({@code record => ~product & { fields: ... groups: ... }}) has the
 * shape of the {@code *Body} class that represents a bound instance of it ({@link
 * io.ltr8.tson.schema.meta.RecordBody}, {@link io.ltr8.tson.schema.meta.ArrayBody}, ...), and each such class's own
 * {@code @Typename} is the bare constructor name, so the schema-name-to-class direction needs the {@code "_body"}
 * suffix added explicitly ({@link #ALIASES}). A constructor that tightens another -- {@code set_type} over {@code
 * array}, {@code enum} over {@code enum_type} -- needs no alias: it restates fields and adds none, and the bind
 * lookup falls back along its chain to the constructor it tightens ({@code ValueReaderContext.bindingNamesFor}).
 *
 * <p>Some entries resolve to a class that is not a record: {@code atom}/{@code product}/{@code sum}/{@code top}
 * mangle to the sealed marker interfaces {@link io.ltr8.tson.schema.meta.Atom}/{@link
 * io.ltr8.tson.schema.meta.Product}/{@link io.ltr8.tson.schema.meta.Sum}/{@link io.ltr8.tson.schema.meta.Top}. This
 * binder resolves the class either way; whether it is usable as a record is the reader's decision, surfacing as a
 * compile-time failure for that one entry.
 *
 * <p><b>The vocabulary is fixed; the binder is not the last word.</b> A schema may hold values of types the kernel
 * never heard of ({@code create_order => !operation { ... }}), and a data document values of its own types; both
 * bind to the consumer's classes through one context. {@link #over} is that context: a name in the vocabulary binds
 * here and nowhere else, so a consumer cannot rebind it, and every other name goes to the consumer's binder.
 */
public final class SchemaMetaNameBinder {

    private SchemaMetaNameBinder() {
    }

    private static final String NAMESPACE = "io.ltr8.tson.schema.meta";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("record", "record_body"),
            Map.entry("array", "array_body"),
            Map.entry("map", "map_body"),
            Map.entry("namespace", "namespace_body"),
            Map.entry("tuple", "tuple_body"),
            Map.entry("choice", "choice_body"),
            Map.entry("reference", "reference_body"),
            Map.entry("enum_type", "enum_body"),
            Map.entry("template", "template_body"),
            Map.entry("datetime_type", "date_time_type"),
            Map.entry("field_name", "identifier"),
            Map.entry("type_name", "identifier"),
            Map.entry("param_name", "identifier"));

    private static final DataNameBinder MANGLED = new DataNameBinder.DefaultDataNameBinder(Set.of(NAMESPACE), ALIASES);

    /**
     * The schema model's binder: a name meta-kernel.tn or meta.tn declares, mangled into {@code
     * io.ltr8.tson.schema.meta}; any other name is refused, whatever class it would mangle to.
     */
    public static final DataNameBinder INSTANCE = name -> {
        if (!Vocabulary.NAMES.contains(name)) {
            throw new DataBindException("'" + name + "' is not declared by meta-kernel.tn or meta.tn, so the schema "
                    + "model does not bind it");
        }
        return MANGLED.resolve(name);
    };

    /**
     * The {@code io.ltr8.tson.schema.meta} default -- {@link ResolverBindContext}'s own built-in atom
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
     * ResolverBindContext#registerAbsent}). The kernel's and meta.tn's names are fixed; every other name is the
     * consumer's binder's to answer.
     */
    public static DataBindContext over(DataBindContext consumer) {
        Objects.requireNonNull(consumer, "consumer");
        DataBindContext.Builder builder = consumer.toBuilder().nameBinder(extendedWith(consumer.nameBinder()));
        return ResolverBindContext.registerAbsent(builder).build();
    }

    /**
     * {@link #INSTANCE} for a name meta-kernel.tn or meta.tn declares, {@code additional} for every other -- the
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
     * The names {@link #INSTANCE} answers for: every declaration of the bundled meta-kernel.tn and meta.tn, read
     * from their source by the schema parser on first use. An {@link #ALIASES} key outside it would be an alias
     * nothing can ask for, so one is a broken invariant.
     */
    private static final class Vocabulary {

        static final Set<String> NAMES = read();

        private static Set<String> read() {
            Set<String> names = new LinkedHashSet<>();
            for (String id : new String[] {TsonBundledSchemas.META_KERNEL_ID, TsonBundledSchemas.META_ID}) {
                names.addAll(new TsonSchemaParser(TsonBundledSchemas.fetch(id)).parseSchemaDocument()
                        .body().declarations().keySet());
            }
            for (String alias : ALIASES.keySet()) {
                if (!names.contains(alias)) {
                    throw new IllegalStateException("alias '" + alias + "' names no declaration of meta-kernel.tn "
                            + "or meta.tn");
                }
            }
            return Set.copyOf(names);
        }
    }
}
