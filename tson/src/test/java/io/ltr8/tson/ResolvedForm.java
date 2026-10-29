package io.ltr8.tson;

import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.base.CanonicalIdentity;
import io.ltr8.tson.compiler.TsonDataParser;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Type;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comparing one resolved schema against another: what [TSON-SCHEMA] §8's output form does and does not
 * pin, stated once for everything that compares two of them.
 *
 * <p>Two callers, asking the same question of different sources: {@link ResolvedFixtureTest} compares this
 * resolver against the spec's own published {@code spec/m/*-resolved.tn}, and the Class 2 conformance
 * runner compares it against a corpus vector's expected side. The normalisations below are the corpus's
 * own rule 6 and the fixtures' own {@code @doc} instruction, which are the same rule; a second copy of it
 * would be a second answer to "what is normative about a resolved schema".
 */
final class ResolvedForm {

    /**
     * A synthetic entry's name ends in a content hash of its resolved binding record, and <b>that hash is
     * not normative</b>: §8.2 keys a closed synthetic on structural equality -- one entry per distinct
     * concrete form, schema-wide -- leaving the spelling to the implementation. Both sides reduce it to a
     * fixed placeholder before comparing, so this stays a test of structure rather than of a hash function.
     */
    private static final Pattern SYNTHETIC_HASH = Pattern.compile("_[0-9a-f]{8}$");

    /** The same, unanchored -- a synthetic's name appears inside a body as well as being an entry's own. */
    private static final Pattern SYNTHETIC_HASH_ANYWHERE = Pattern.compile("_[0-9a-f]{8}\\b");

    /**
     * A field's source position, which is this model's own ({@code RecordField.position} is {@code @Unbound}):
     * a field read back from a fixture carries none, so it is normalised away rather than compared.
     */
    private static final Pattern SOURCE_POSITION = Pattern.compile("position=Optional\\[Position\\[[^\\]]*\\]\\]");

    /** The placeholder both sides reduce a synthetic's content hash to. */
    static final String HASH_PLACEHOLDER = "_xxhash";


    private ResolvedForm() {
    }

    /** An entry name with its synthetic content hash, if any, reduced to {@link #HASH_PLACEHOLDER}. */
    static String withoutHash(String name) {
        return SYNTHETIC_HASH.matcher(name).replaceFirst(HASH_PLACEHOLDER);
    }

    /**
     * A chain's {@code supertypes}/{@code subtypes} sorted. Both are sets the representation happens to write as
     * lists, so one side's alphabetical order and another's resolution order say the same thing. Nothing else is
     * normalised; a difference anywhere else is a real one.
     *
     * <p><b>A set-typed field is compared as the ordered list it is written as</b>: [TSON-SCHEMA] §7.5 makes
     * source declaration order canonical in resolved output, for {@code enum.members},
     * {@code integer_type.members}, {@code decimal_type.members} and {@code text_type.members} alike, so two
     * resolved documents are compared as §8 writes them.
     */
    static Top canonical(Top value) {
        return value instanceof Type type
                ? TypeChain.withChain(value, type.chain().withSupertypes(type.supertypes().stream().sorted()
                        .toList()).withSubtypes(type.subtypes().stream().sorted().toList()))
                : value;
    }

    /**
     * A value reduced to what a comparison is about: canonicalised, then with every synthetic's content hash
     * replaced. Compared as text rather than through {@code equals} because a synthetic's name appears at any
     * depth of a body -- as the type of a field, inside an argument list -- and normalising it in place would
     * mean rebuilding every body shape here.
     */
    static String rendered(Top value) {
        // An open entry's body is text, and [TSON-SCHEMA] §5.10 compares the *parsed* form: one spelling is
        // required of the application, not of the whitespace around it. Both sides are reduced to their
        // parsed application here, so a held body compares like every other body and needs no side channel.
        Top compared = value instanceof TemplateBody held ? parsedForComparison(held) : value;
        String text = SYNTHETIC_HASH_ANYWHERE.matcher(String.valueOf(canonical(compared))).replaceAll(HASH_PLACEHOLDER);
        return SOURCE_POSITION.matcher(text).replaceAll("position=Optional.empty");
    }

    /**
     * The entries a registered schema declares <em>itself</em>, hashes normalised -- what a resolver
     * produced for that document, before an import merged anything else into its namespace.
     */
    static Map<String, Top> ownEntries(Tson tson, String id) {
        var linked = tson.bindRegistry().core().resolveLinked(id);
        String canonical = CanonicalIdentity.canonicalize(id);
        Map<String, Top> own = new LinkedHashMap<>();
        linked.schema().entries().forEach((name, definition) -> {
            if (linked.originOf(name).equals(canonical)) {
                own.put(withoutHash(name), definition);
            }
        });
        return own;
    }

    /**
     * A held body with its text replaced by the structure that text denotes.
     *
     * <p>[TSON-SCHEMA] §5.10's one-spelling rule is about the application, not about the whitespace around
     * it: an open synthetic's identity is derived from its held binding record, so what must agree between
     * two resolvers is the parsed form. Reducing both sides here is that rule applied once, and it is what
     * lets an open entry be compared by {@link #rendered} like every other entry rather than through a
     * second channel of its own.
     */
    private static Top parsedForComparison(TemplateBody held) {
        return new TemplateBody(held.parameters(),
                String.valueOf(new TsonDataParser(held.template()).parseDocument().root()));
    }

    /**
     * The names a §8 resolved-schema document marks {@code @synthetic} at its schema-map keys, hashes
     * normalised -- read from the document's own <em>text</em>.
     *
     * <p>Text, and not the bound document every other comparison here uses, because a key-position
     * annotation is dropped when a resolved-form document is read back. Both sides of a bound comparison
     * would therefore render no annotations at all and agree for the wrong reason. Scanning the source is
     * what makes §8.2's marker checkable at all before that channel exists.
     */
    static Set<String> markedSynthetics(String resolvedText) {
        Set<String> marked = new TreeSet<>();
        Matcher matcher = Pattern.compile("@synthetic\\s+([A-Za-z0-9_]+)\\s*=>").matcher(resolvedText);
        while (matcher.find()) {
            marked.add(withoutHash(matcher.group(1)));
        }
        return marked;
    }

    /** The same, from this resolver: the schema's own entries whose keys carry the marker. */
    static Set<String> ourSynthetics(Tson tson, String id) {
        var linked = tson.bindRegistry().core().resolveLinked(id);
        var entries = linked.schema().entries();
        String canonical = CanonicalIdentity.canonicalize(id);
        Set<String> marked = new TreeSet<>();
        entries.forEach((name, definition) -> {
            if (linked.originOf(name).equals(canonical) && entries.getAnnotations(name).has("synthetic")) {
                marked.add(withoutHash(name));
            }
        });
        return marked;
    }

    /**
     * Annotation names a key comparison leaves out: {@code doc}, which the resolver carries from the source and
     * a fixture leaves out of its entries, being compared rather than read, and {@code synthetic}, which
     * {@link #markedSynthetics} compares on its own terms.
     */
    private static final Set<String> NOT_COMPARED_AT_KEYS = Set.of("doc", "synthetic", "application");

    /**
     * Each schema-map key's {@code @application} in a §8 resolved-schema document, rendered {@code head<arg, ...>},
     * hashes normalised -- the instantiation each entry is (§8.2), read from the text parsed as data, a key
     * annotation being dropped when the document is bound. A record value, so compared on its own terms rather
     * than among {@link #fixtureKeyAnnotations}' tokens.
     */
    static Map<String, String> markedApplications(String resolvedText) {
        MapValue map = (MapValue) new TsonDataParser(resolvedText).parseDocument().root().coreValue();
        Map<String, String> marked = new TreeMap<>();
        for (MapValue.MapEntry entry : map.entries()) {
            String name = ((TokenValue) entry.key().coreValue()).text();
            entry.key().annotations().stream()
                    .filter(annotation -> annotation.name().equals("application"))
                    .forEach(annotation -> marked.put(withoutHash(name),
                            withoutHash(writtenApplication(annotation.value().orElseThrow().coreValue()))));
        }
        return marked;
    }

    /** The same, from this resolver: the {@code @application} on the keys of the schema's own entries. */
    static Map<String, String> ourApplications(Tson tson, String id) {
        var linked = tson.bindRegistry().core().resolveLinked(id);
        String canonical = CanonicalIdentity.canonicalize(id);
        Map<String, String> marked = new TreeMap<>();
        linked.schema().entries().keySet().forEach(name -> {
            if (linked.originOf(name).equals(canonical)) {
                linked.schema().applicationOf(name).ifPresent(application ->
                        marked.put(withoutHash(name), withoutHash(application(application))));
            }
        });
        return marked;
    }

    /** A written {@code type_ref}, positional or in record form, as {@code head<arg, ...>}. */
    private static String writtenApplication(CoreValue value) {
        if (value instanceof TokenValue token) {
            return token.text();
        }
        RecordValue record = (RecordValue) value;
        String head = null;
        List<String> arguments = new java.util.ArrayList<>();
        for (RecordValue.Field field : record.fields()) {
            CoreValue held = field.value().coreValue();
            if (field.name().equals("name")) {
                head = writtenApplication(held);
            } else if (field.name().equals("arguments")) {
                for (var element : ((ArrayValue) held).elements()) {
                    RecordValue argument = (RecordValue) element.coreValue();
                    RecordValue.Field only = argument.fields().getFirst();
                    arguments.add(writtenApplication(only.value().coreValue()));
                }
            }
        }
        return arguments.isEmpty() ? head : head + "<" + String.join(", ", arguments) + ">";
    }

    /** A bound application as {@code head<arg, ...>}, on the same terms. */
    private static String application(TypeRef ref) {
        if (ref.arguments().isEmpty()) {
            return ref.name();
        }
        return ref.name() + ref.arguments().stream().map(argument -> switch (argument) {
            case io.ltr8.tson.schema.meta.TypeArgument.Ref nested -> application(nested.ref());
            case io.ltr8.tson.schema.meta.TypeArgument.Value literal -> literal.value().text();
        }).collect(java.util.stream.Collectors.joining(", ", "<", ">"));
    }

    /**
     * Each schema-map key's annotations in a §8 resolved-schema document, as {@code @name} or {@code
     * @name:value}, hashes normalised -- read from the document's <em>text</em>, parsed as data.
     *
     * <p>Parsed rather than bound, for {@link #markedSynthetics}' reason: a key-position annotation is dropped
     * when the document is bound, so a comparison through the value model would find none on either side.
     * Parsed rather than scanned, because an annotation value is a token and a regex over the text cannot
     * tell one from the prose inside a {@code @doc}.
     */
    static Map<String, List<String>> fixtureKeyAnnotations(String resolvedText) {
        MapValue map = (MapValue) new TsonDataParser(resolvedText).parseDocument().root().coreValue();
        Map<String, List<String>> keys = new TreeMap<>();
        for (MapValue.MapEntry entry : map.entries()) {
            String name = ((TokenValue) entry.key().coreValue()).text();
            keys.put(withoutHash(name), entry.key().annotations().stream()
                    .filter(annotation -> !NOT_COMPARED_AT_KEYS.contains(annotation.name()))
                    .map(annotation -> "@" + annotation.name() + annotation.value()
                            .map(value -> ":" + ((TokenValue) value.coreValue()).text()).orElse(""))
                    .sorted().toList());
        }
        return keys;
    }

    /** The same, from this resolver: the annotations on the keys of the schema's own entries. */
    static Map<String, List<String>> ourKeyAnnotations(Tson tson, String id) {
        var linked = tson.bindRegistry().core().resolveLinked(id);
        var entries = linked.schema().entries();
        String canonical = CanonicalIdentity.canonicalize(id);
        Map<String, List<String>> keys = new TreeMap<>();
        entries.forEach((name, definition) -> {
            if (linked.originOf(name).equals(canonical)) {
                keys.put(withoutHash(name), entries.getAnnotations(name).values().stream()
                        .filter(annotation -> !NOT_COMPARED_AT_KEYS.contains(annotation.name()))
                        .map(annotation -> "@" + annotation.name()
                                + annotation.value().map(value -> ":" + value).orElse(""))
                        .sorted().toList());
            }
        });
        return keys;
    }

    /**
     * Each entry's <em>value</em> annotations in a §8 resolved-schema document -- those written after {@code =>},
     * {@code doc => @annotation !reference { ... }} -- as {@code @name} or {@code @name:value}, hashes normalised,
     * read from the document's text parsed as data. An entry's value is its body, whose Java shape has no slot for
     * them, so binding the document drops them as it drops a key's; the resolver keeps them among the entry's
     * facts.
     */
    static Map<String, List<String>> fixtureValueAnnotations(String resolvedText) {
        MapValue map = (MapValue) new TsonDataParser(resolvedText).parseDocument().root().coreValue();
        Map<String, List<String>> values = new TreeMap<>();
        for (MapValue.MapEntry entry : map.entries()) {
            String name = ((TokenValue) entry.key().coreValue()).text();
            List<String> written = entry.value().annotations().stream()
                    .filter(annotation -> !NOT_COMPARED_AT_KEYS.contains(annotation.name()))
                    .map(annotation -> "@" + annotation.name() + annotation.value()
                            .map(value -> ":" + ((TokenValue) value.coreValue()).text()).orElse(""))
                    .sorted().toList();
            if (!written.isEmpty()) {
                values.put(withoutHash(name), written);
            }
        }
        return values;
    }

    /** The same, from this resolver: the annotations each of the schema's own entries records on its value. */
    static Map<String, List<String>> ourValueAnnotations(Tson tson, String id) {
        var linked = tson.bindRegistry().core().resolveLinked(id);
        String canonical = CanonicalIdentity.canonicalize(id);
        Map<String, List<String>> values = new TreeMap<>();
        linked.schema().entries().keySet().forEach(name -> {
            if (!linked.originOf(name).equals(canonical)) {
                return;
            }
            List<String> recorded = linked.schema().factsOf(name).annotations().values().stream()
                    .filter(annotation -> !NOT_COMPARED_AT_KEYS.contains(annotation.name()))
                    .map(annotation -> "@" + annotation.name()
                            + annotation.value().map(value -> ":" + value).orElse(""))
                    .sorted().toList();
            if (!recorded.isEmpty()) {
                values.put(withoutHash(name), recorded);
            }
        });
        return values;
    }

    /**
     * A §8 resolved-schema document, bound back into the value model. The text names no governing schema of
     * its own -- meta.tn is what governs every one of these, and the reader says so.
     */
    static Map<String, Top> readResolved(Tson tson, String resolvedText) {
        return readResolved(tson, resolvedText, TsonBundledSchemas.META_ID);
    }

    /**
     * {@link #readResolved(Tson, String)} under the meta layer {@code governingMeta} instead of meta.tn: the schema
     * the subject's own {@code !!meta} names, which imports meta.tn and adds the constructors only it declares. An
     * instance of its namespace type is typed by that layer alone, so a resolved document holding one reads
     * back only under it; for a subject governed by meta.tn the two are the same read.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Top> readResolved(Tson tson, String resolvedText, String governingMeta) {
        return (Map<String, Top>) tson.objectReader()
                .withSchema(governingMeta)
                .readAs(resolvedText, "schema", Object.class);
    }
}
