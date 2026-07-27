package io.ltr8.tson.parser.compiler;

import io.ltr8.tson.parser.TsonDataParser;
import io.ltr8.tson.parser.ast.Document;

/**
 * The Class 2 (schema-validating) data parser (§1.5) this project's own groundwork has been
 * building toward -- combines the Class 1 structural parser ({@link TsonDataParser}, Part 1's own grammar/
 * base-type layer, unchanged) with a {@link TsonCompiledSchema} (a compiled schema) into one call:
 * parse TSON source text, then read the result against a named, schema-known type.
 *
 * <p><b>Doesn't (yet) consult a document's own {@code !!schema} header directive</b> (§2.2, {@link
 * Document#schema()} -- preserved, uninterpreted, by {@link TsonDataParser} itself, same as Class 1 always
 * has). A caller here always names the root type explicitly, the same way {@code
 * TsonMapperReader.toObject(String, Class)} always takes an explicit target class rather than
 * inferring one from the data. This is a deliberate scope decision, not an oversight: auto-selecting
 * a compiled schema from a document's own declared {@code !!schema} URI needs a schema-identity {@code ->}
 * {@link TsonCompiledSchema} registry that doesn't exist yet (a distinct, separate piece -- {@code
 * TsonSchemaRegistry} maps identity to {@code TsonSchema}, not to a *compiled* parser); and even a
 * narrower "does the document's own claim match this parser's own schema" consistency check would
 * need canonical-identity comparison ({@code CanonicalIdentity}, in {@code tson-schema.registry})
 * that package deliberately keeps internal to {@code TsonSchemaRegistry} itself (see that package's own
 * Javadoc) -- reaching into it directly from here, rather than through a proper public entry point
 * {@code tson-schema} doesn't offer yet, would be exactly the kind of cross-module layering
 * violation this project has otherwise been careful to avoid. Left as an explicit, tracked gap
 * (task list), not implemented halfway.
 */
public final class SchemaValidatingParser {

    private final TsonCompiledSchema schema;

    public SchemaValidatingParser(TsonCompiledSchema schema) {
        this.schema = schema;
    }

    /** The compiled schema this parser reads against. */
    public TsonCompiledSchema schema() {
        return schema;
    }

    /**
     * Parses {@code source} and reads its root value against {@code rootTypeName}. {@code T} is
     * never checked -- the same unchecked cast a caller would otherwise write themselves against
     * {@link TsonCompiledSchema#get}'s own wildcarded {@link TsonSchemaTypeParser}, just done once here.
     */
    @SuppressWarnings("unchecked")
    public <T> T read(String source, String rootTypeName) {
        return (T) read(new TsonDataParser(source).parseDocument(), rootTypeName);
    }

    /** Reads an already-parsed {@code document}'s root value against {@code rootTypeName} -- for a caller that already has one, e.g. to inspect {@link Document#id()}/{@link Document#schema()} itself first. */
    @SuppressWarnings("unchecked")
    public <T> T read(Document document, String rootTypeName) {
        return (T) schema.get(rootTypeName).read(document.root());
    }
}
