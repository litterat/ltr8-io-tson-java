package io.ltr8.tson;

import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.meta.TypeDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
     * {@code supertypes}/{@code subtypes} sorted. §8.2 calls both <b>name-level indexes,
     * resolver-managed</b>: they are sets the representation happens to write as lists, so one side's
     * alphabetical order and another's resolution order say the same thing. Nothing else is normalised; a
     * difference anywhere else is a real one.
     */
    static TypeDefinition canonical(TypeDefinition definition) {
        return new TypeDefinition(definition.source(), definition.kind(), definition.parameters(),
                definition.constructor(), definition.supertypes().stream().sorted().toList(),
                definition.subtypes().stream().sorted().toList(), definition.disjoint(), definition.body(),
                definition.position(), definition.annotations());
    }

    /**
     * A definition reduced to what a comparison is about: canonicalised, then with every synthetic's
     * content hash replaced. Compared as text rather than through {@code equals} because a synthetic's name
     * appears at any depth of a body -- as the type of a field, inside an argument list -- and normalising
     * it in place would mean rebuilding every body shape here.
     */
    static String rendered(TypeDefinition definition) {
        String text = SYNTHETIC_HASH_ANYWHERE.matcher(String.valueOf(canonical(definition)))
                .replaceAll(HASH_PLACEHOLDER);
        // `position` is this model's own: an @Unbound component recording where a declaration was written,
        // which §8's resolved form has no field for and nothing external carries. Normalised away rather
        // than compared, exactly as TypeDefinition's own equals leaves it out.
        return SOURCE_POSITION.matcher(text).replaceAll("position=Optional.empty");
    }

    /**
     * The entries a registered schema declares <em>itself</em>, hashes normalised -- what a resolver
     * produced for that document, before an import merged anything else into its namespace.
     */
    static Map<String, TypeDefinition> ownEntries(Tson tson, String id) {
        var linked = tson.bindRegistry().core().resolveLinked(id);
        String canonical = TsonCanonicalIdentity.canonicalize(id);
        Map<String, TypeDefinition> own = new LinkedHashMap<>();
        linked.schema().entries().forEach((name, definition) -> {
            if (linked.originOf(name).equals(canonical)) {
                own.put(withoutHash(name), definition);
            }
        });
        return own;
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
        String canonical = TsonCanonicalIdentity.canonicalize(id);
        Set<String> marked = new TreeSet<>();
        entries.forEach((name, definition) -> {
            if (linked.originOf(name).equals(canonical) && entries.getAnnotations(name).has("synthetic")) {
                marked.add(withoutHash(name));
            }
        });
        return marked;
    }

    /**
     * A §8 resolved-schema document, bound back into the value model. The text names no governing schema of
     * its own -- meta.tn is what governs every one of these, and the reader says so.
     */
    @SuppressWarnings("unchecked")
    static Map<String, TypeDefinition> readResolved(Tson tson, String resolvedText) {
        return (Map<String, TypeDefinition>) tson.objectReader()
                .withSchema(TsonBundledSchemas.META_ID)
                .readAs(resolvedText, "schema", Object.class);
    }
}
