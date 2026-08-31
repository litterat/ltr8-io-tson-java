# Public API inventory

Module by module. Javadoc on the source is the authority where this and the code disagree; **`./gradlew
build` runs javadoc**, so a dangling `{@link}` fails the build.

Only the packages listed here are exported. JPMS enforcement is real, not convention — `lexer`, `atom`,
`base`, `reader` and `resolver` are genuinely unreachable from another module.

---

## `io.ltr8.tson` (module `tson`) — the front door

### `Tson`

```java
public final class Tson {
    public static TsonConfig builder();

    public TsonObjectReader objectReader();      // schema-aware, over this instance's bindRegistry
    public TsonTreeReader   treeReader();        // schema-aware, over this instance's treeRegistry
    public TsonObjectWriter objectWriter();
    public TsonTreeWriter   treeWriter();
    public DataBindContext  dataBindContext();

    public TsonLinkedSchema resolve(String schemaText);          // parse→resolve→link→REGISTER; fail-fast
    public List<Diagnostic> validateSchema(String schemaText);   // the same, collecting — and registers when sound
    public List<Diagnostic> validate(String data);
    public List<Diagnostic> validate(InputStream data);

    public TsonCompiledSchemaRegistry treeRegistry();
    public TsonCompiledSchemaRegistry bindRegistry();
    public TsonSchemaRegistry schemaRegistry();
    public TsonCompiledSchemaLoader loader();
}
```

`Tson.builder().build()` bootstraps meta-kernel / meta.tn / core.tn and returns an immutable instance.
Resolution is **always bind-anchored** (meta instances bind to `schema.meta.Top`), so `resolve` takes no
mode; only the final compile picks one, which is why **the read mode is which registry you hold**.

`validate` *is* `treeReader()` with a collecting receiver — there is no second implementation to drift
from it. `validateSchema` stops at the first phase that reports anything (parse, then resolve, then
link), javac-style, so consequences of an earlier error are not reported as independent problems; and a
schema that reported anything is never registered.

### `TsonConfig`

```java
public final class TsonConfig {
    public TsonConfig schemaSource(TsonSchemaSource schemaSource);
    public TsonConfig httpSchemas(String... hosts);              // TsonHttpSchemaSource, allow-listed
    public TsonConfig fileSchemas(String host, Path directory);  // TsonFileSchemaSource

    public TsonConfig dataBindContext(DataBindContext context);
    public TsonConfig bindings(Map<String, Class<?>> bindings);  // exclusive with dataBindContext
    public TsonConfig profile(String profile);                   // exclusive with dataBindContext
    public TsonConfig metaNameBinder(DataNameBinder binder);     // a consumer's own meta vocabulary

    public TsonConfig identifierPolicy(TsonUnicodePolicy policy);  // declared names
    public TsonConfig tokenPolicy(TsonUnicodePolicy policy);       // every token a read pulls
    public TsonConfig lenientBinding();

    public Tson build();
}
```

`httpSchemas` / `fileSchemas` are repeatable and mutually exclusive with each other and with
`schemaSource(…)`, the general seam.

### Schema sources

```java
public interface TsonSchemaSource {                 // io.ltr8.tson.compiler
    String fetch(String uri);                       // throws TsonSchemaFetchException and nothing else
    static TsonSchemaSource registeredOnly();       // the default: refuses everything, NOT_PERMITTED
    static TsonSchemaSource ofMap(Map<String, String> schemas);   // matched by canonical identity
}

public final class TsonHttpSchemaSource implements TsonSchemaSource, AutoCloseable {
    public static Builder builder();                // allowHost, mapHost, maxDocumentBytes, timeout,
                                                    // maxCachedSchemas, requireContentHashPin, httpClient
    public void preload(String... references);
    public boolean isCached(String reference);
    public static final int      DEFAULT_MAX_DOCUMENT_BYTES  = 1 << 20;
    public static final Duration DEFAULT_TIMEOUT             = Duration.ofSeconds(5);
    public static final int      DEFAULT_MAX_CACHED_SCHEMAS  = 128;
}

public final class TsonFileSchemaSource implements TsonSchemaSource {
    public static Builder builder();                // mapHost(host, dir), maxDocumentBytes,
                                                    // maxCachedSchemas, requireContentHashPin
    public void preload(String... references);
    public boolean isCached(String reference);
}
```

`SchemaReference` (same package) holds §2.2.1's rules on what an identity may be, shared by both.
Neither source verifies the `?sha256=` pin or the fetched `!!id` — the loader does both;
`requireContentHashPin` adds the one thing it cannot, that a pin be *present*.

---

## `io.ltr8.tson.compiler` (module `tson-compiler`) — the engine

### Readers

```java
public final class TsonTreeReader {
    public TsonTreeReader();                                  // standalone = schemaless (Class 1)
    public TsonTreeReader(TsonCompiledSchemaRegistry tree);

    public TsonTreeReader withSchema(String schemaUri);
    public TsonTreeReader withDiagnostics(TsonDiagnosticsReceiver receiver);
    public TsonTreeReader withTokenPolicy(TsonUnicodePolicy policy);
    public TsonTreeReader withNamePolicy(TsonUnicodePolicy policy);
    public TsonTreeReader preservingUnknownTypeRefs();

    public TsonValue    read(String|InputStream source);        // honours the document's own !!schema
    public TsonDocument readDocument(String|InputStream source);// + its !!id and !!schema
    public TsonValue    readWithoutSchema(String|InputStream source);
    public TsonValue    readAs(String|InputStream source, String typeName);
    public TsonValue    read(TsonReadContext ctx);
}

public final class TsonObjectReader {
    public TsonObjectReader();
    public TsonObjectReader(DataBindContext context);
    public TsonObjectReader(TsonCompiledSchemaRegistry bind, DataBindContext context);
    // the same six derivations, and:
    public <T> T                    read(String|InputStream source, Class<T> targetClass);
    public <T> TsonObjectDocument<T> readDocument(String|InputStream source, Class<T> targetClass);
    public <T> T                    readWithoutSchema(String|InputStream source, Class<T> targetClass);
    public <T> T                    readAs(String|InputStream source, String typeName, Class<T> targetClass);
    public <T> T                    read(TsonReadContext ctx, Class<T> targetClass);
}
```

Every derivation returns a **new** reader and leaves the original alone; derived readers share the
original's compiled-schema registry, so a schema compiles once per `Tson`, not once per reader.

`readDocument` returns what the read *established* about the document, not just its value:

```java
public record TsonDocument(Optional<String> id, Optional<String> schema, TsonValue root) {}   // tson-tree

public record TsonObjectDocument<T>(Optional<String> id, Optional<String> schema,
                                    Optional<String> rootType, T value) {}                    // tson-compiler
```

Two types rather than one: the object side needs a fourth component, `rootType`, a name a
`DataNameBinder` cannot invert, where a `TsonValue` already names its own type. Which is also why
`TsonObjectWriter.describing` takes two arguments and the tree writer's takes one.

**`requireDocumentEnd`: the pull is the point, not the assertion after it.** Nothing fails if you simply
stop reading a lazy `TsonDataStream`; pulling past the root value is what makes trailing content get
rejected. The facades do it; a caller driving the stream directly must.

### Writers

```java
public final class TsonTreeWriter {
    public TsonTreeWriter describing(String schemaUri);      // adds !!schema
    public TsonTreeWriter identifiedBy(String documentId);   // adds !!id
    public String toTson(TsonValue|TsonDocument value);
    public void   write(TsonValue|TsonDocument value, OutputStream|Appendable out);
}

public final class TsonObjectWriter {
    public TsonObjectWriter();
    public TsonObjectWriter(DataBindContext context);
    public TsonObjectWriter describing(String schemaUri, String rootTypeName);   // both, always
    public TsonObjectWriter identifiedBy(String documentId);
    public String toTson(Object|TsonObjectDocument<?> value);
    public void   write(Object|TsonObjectDocument<?> value, OutputStream|Appendable out);
}
```

The sink is written as UTF-8, **flushed and not closed** — it is the caller's, which is what makes an
HTTP response body the natural case. A document's own directives beat the writer's where it has them.
`TsonDataEmitter.typeRef` refuses a second type-ref on one value, which keeps a declared root type from
writing an unparseable document.

### Document header

```java
public record TsonDocumentHeader(Optional<String> id, Optional<String> schema, Optional<String> meta) {
    public static TsonDocumentHeader peek(String|InputStream source);   // total: never throws
    public static TsonDocumentPeek   peekResumable(InputStream source); // .header(), .document()
    public boolean isSchemaDocument();                                  // it carries !!meta
}
```

§7.1's classification from the opening bytes — at most two directives of lookahead and no value parsing.
A gigabyte document costs the same as a two-line one, and a document whose body will not parse still
classifies.

### Diagnostics

```java
public record Diagnostic(…) { public enum Code { … } }        // see references/diagnostics.md
public interface TsonDiagnosticsReceiver { void report(Diagnostic d);
    static TsonDiagnosticsReceiver throwing();
    static TsonDiagnosticsCollector collecting(); }
public final class TsonDiagnosticsCollector implements TsonDiagnosticsReceiver {
    public List<Diagnostic> diagnostics();  public boolean isEmpty(); }
public record Position(int line, int column, int byteOffset) implements SourcePosition {}
public record SchemaLocation(…)             // id + pointer + position, accumulated as a read descends
```

### Unicode policy

```java
public final class TsonUnicodePolicy {
    public enum Level { ASCII_ONLY, SINGLE_SCRIPT, HIGHLY_RESTRICTIVE,
                        MODERATELY_RESTRICTIVE, MINIMALLY_RESTRICTIVE, UNRESTRICTED }

    public static TsonUnicodePolicy of(Level level);
    public static TsonUnicodePolicy asciiOnly();
    public static TsonUnicodePolicy singleScript();
    public static TsonUnicodePolicy highlyRestrictive();       // the identifier default, whole-name
    public static TsonUnicodePolicy moderatelyRestrictive();
    public static TsonUnicodePolicy scriptsUnchecked();
    public static TsonUnicodePolicy unrestricted();            // the token default

    public TsonUnicodePolicy perSegment();                     // reach for this before loosening the level
    public TsonUnicodePolicy permitting(UnicodeScript... scripts);

    public static String dataVersion();                        // the Unicode data version, e.g. "16.0"
    public boolean checksScripts();
    public boolean appliesIdentifierProfile();
    public boolean isPerSegment();
    public Optional<String> violation(String text);
}
```

### Content hashing

```java
public final class TsonContentHash {
    public static String sha256(byte[] document);              // every byte past the !!id line
    public static int contentStart(byte[] document);
    public static Optional<String> declaredSha256(String uri); // read a ?sha256= pin back out
    public static void verify(byte[] content, String referenceUri);
}
```

Pins are **verification metadata, not identity** — checked through the loader on every fetched pinned
reference. Never invent or truncate a hash; `tson hash` stamps one idempotently (the `!!id` line is
excluded, so a document can carry its own). `declaredSha256` throws `IllegalArgumentException` on a
malformed pin (anything but 64 lowercase hex digits) rather than answering empty — a truncated pin is a
mistake, not an absent one.

### Compiled schemas

```java
public sealed class TsonCompiledSchema permits TsonCompiledMetaSchema {
    public TsonTypeReader<?>           get(String typeName);   // throws if there is no such entry
    public Optional<TsonTypeReader<?>> find(String typeName);
    public TsonSchema                  schema();
}

public final class TsonCompiledSchemaRegistry {
    public static TsonCompiledSchemaRegistry tree(TsonCompiledMetaRegistry core);
    public static TsonCompiledSchemaRegistry bind(TsonCompiledMetaRegistry core, DataBindContext ctx);
    public TsonCompiledSchema get(String uri);
    public TsonCompiledSchema get(String uri, TsonDiagnosticsReceiver receiver);
    public TsonCompiledSchema compile(TsonLinkedSchema linked);
    public TsonCompiledMetaRegistry core();
}

@FunctionalInterface
public interface TsonTypeReader<T> { T read(TsonReadContext ctx); }
```

`TsonTypeReader` is **strictly one method** — it reads one value at a cursor and polices nothing around
it. Framing and error policy live in the facades. Compilation is **eager**, so a broken entry surfaces
at compile time; an entry that cannot be built becomes an `ErrorReader` reporting `NOT_IMPLEMENTED` at
read, with two exceptions: a `TsonBindMismatchException` fails the compile, and a
`TsonMissingBindingException` is thrown unwrapped from its reader.

`TsonCompiledMetaRegistry` is the shared meta/resolution core: it compiles and caches **only**
meta-layer schemas, resolves/links/registers everything else without compiling it, and owns content-hash
verification, the bootstrap and §2.2.3's import-cycle guard.

### Pipeline stages, if you need one directly

`TsonSchemaParser`, `TsonSchemaResolver`, `TsonSchemaLinker`, `TsonSchemaCompiler`, `TsonDataParser`,
`TsonDataStream`, `ChoiceDisjointness`, `TypeInhabitance`, `SchemaFailure`, `VocabularyAtoms`.
`TsonSchemaParser` / `SchemaResolver` / `TsonSchemaLinker` each have a reporting overload that collects
every independent problem in one pass; namespace-level failures (unloadable `!!import`, ineligible
`!!meta`, `!!id` cross-check) still throw even with a receiver. Compilation, and the lexer under
everything, are fail-fast by design.

### Exported sub-packages

- `io.ltr8.tson.compiler.ast` — the parse-preserving AST: `Document`, `DataValue`, `CoreValue` and its
  branches (`RecordValue`, `MapValue`, `ArrayValue`, `TokenValue`, `AbsentValue`, `EmptyBrace`,
  `ScopedValue`), `Annotation`, `TokenForm`.
- `io.ltr8.tson.compiler.ast.schema` — `SchemaDocument` and the schema-grammar nodes.
- `io.ltr8.tson.compiler.stream` — the Tier 2 event vocabulary: `TsonEvent` (sealed) with
  `DocumentStart`/`End`, `RecordStart`/`End`, `MapStart`/`MapArrow`/`MapEnd`, `ArrayStart`/`End`,
  `FieldName`, `TokenEvent`, `AbsentEvent`, `EmptyBraceEvent`, `TypeRef`, `SchemaRef`,
  `AnnotationStart`/`End`; `TsonEventSource`, `ListEventSource`.
- `io.ltr8.tson.compiler.config` — `TsonAtomContext` (the two default bind contexts),
  `SchemaMetaNameBinder`.

---

## `io.ltr8.tson.tree` (module `tson-tree`)

A true leaf — depends on **nothing**, not even `tson-annotation`.

```java
public sealed interface TsonValue
        permits TsonRecord, TsonMap, TsonArray, TsonTuple, TsonAtom, TsonAbsent, TsonMissing {

    default boolean isRecord() / isMap() / isArray() / isTuple() / isAtom() / isAbsent() / isMissing();
    default boolean isContainer();
    default Optional<String> missingPath();          // the pointer up to the step that FAILED

    default TsonValue get(String name);              // never throws
    default TsonValue get(int index);
    default TsonValue at(String pointer);            // RFC 6901; "" is this node
    default Map<String, TsonValue> fields();
    default List<TsonValue> elements();

    default <T> Optional<T> as(Class<T> type);       // CAST
    default Optional<String>     asString();
    default Optional<Boolean>    asBoolean();
    default Optional<Number>     asNumber();
    default Optional<BigInteger> asBigInteger();
    default Optional<BigDecimal> asBigDecimal();

    default OptionalInt    asInt();                  // CONVERT, exactness-checked
    default OptionalLong   asLong();
    default OptionalDouble asDouble();

    default TsonValue withAnnotations(List<TsonAnnotation> leading);
}

public record TsonAtom(Object value, Optional<String> typeRef, List<TsonAnnotation> annotations)
        implements TsonValue { public static TsonAtom of(Object value[, String typeRef]); }

public record TsonDocument(Optional<String> id, Optional<String> schema, TsonValue root) {}
```

**No `meta` component on `TsonDocument`** — that would be a *schema* document, whose model is
`schema.meta`. Read-side only; no builders or transforms yet.

---

## `io.ltr8.tson.schema` (module `tson-schema`)

```java
public record TsonSchema(String id, String meta, List<String> imports,
                         AnnotatedMap<String, TypeDefinition> entries, boolean bootstrap) {}

public record TsonLinkedSchema(TsonSchema schema, Map<String, String> entryOrigins) {
    public String originOf(String entryName);       // which document declared it, transitively
}

public final class TsonSchemaRegistry implements TsonSchemaLoader {
    public TsonLinkedSchema register(TsonLinkedSchema schema);          // duplicate identity is an error
    public TsonLinkedSchema registerIfAbsent(TsonLinkedSchema schema);
    public Optional<TsonLinkedSchema> get(String uri);
    public Optional<TsonLinkedSchema> getByCanonicalIdentity(String id);
    public Optional<TsonLinkedSchema> load(String canonicalIdentity);
}

public final class TsonCanonicalIdentity {
    public static String  canonicalize(String uri);         // §2.2.1: strip scheme, strip query. Nothing else.
    public static void    validate(String uri);
    public static boolean sameIdentity(String a, String b);
}

public final class TsonBundledSchemas {
    public static final String META_KERNEL_ID / META_ID / CORE_ID;
    public static final String META_KERNEL_SHA256 / META_SHA256 / CORE_SHA256;
    public static Optional<String> declaredSha256(String uri);
    public static String fetch(String uri);
}
```

`register` rejecting a duplicate identity, plus an unmodifiable `entries()`, **is** the "locked"
guarantee. `io.ltr8.tson.schema.meta` is the resolved-schema value model — pure records, sealed
interfaces and enums, §8's `TypeDefinition` et al. `Top` is sealed except for its one deliberately open
branch, **`Data`**, which a consumer's own class implements: §4.1's fourth base kind, where an instance
of a meta-schema's own constructor lives when the thing it describes is not a data type. A consumer
registers such a class by carrying `@Typename` and being findable by the `metaNameBinder`;
`Data.references()` is how its own type references reach the linker, declared rather than discovered.

---

## `io.ltr8.bind` (module `tson-bind`)

```java
public class DataBindContext {
    public static Builder builder();                // allowAny, allowSerializable, nameBinder,
                                                    // nameBinderAliases, nameBinderPackages, profile
    public Optional<String> profile();
    public DataClass getDescriptor(Class<?> targetClass) throws DataBindException;
    public DataClass getDescriptor(Class<?> targetClass, Type parameterizedType) throws DataBindException;
    public DataClass getDescriptor(String schemaTypeName) throws DataBindException;
    public void          registerAtom(Class<?> targetClass) throws DataBindException;
    public DataClassAtom registerAtom(Class<?> targetClass, DataBridge<?, ?> bridge) throws DataBindException;
}
```

Also exports `io.ltr8.bind.mapper` and `io.ltr8.bind.bridge`. See `references/bindings.md`.

---

## `io.ltr8.annotation` (module `tson-annotation`)

`@Typename`, `@Field`, `@Record`, `@Tuple`, `@Union`, `@Atom`, `@Transparent`, `@Profile`, `@Unbound`,
`@FieldOrder`, `@Namespace`, plus `Annotations` / `Annotation` (the wire-annotation carrier) and
`Annotated` / `AnnotatedMap`. See `references/bindings.md`.

---

## `io.ltr8.tson.regex` (module `tson-regex`)

A native RFC 9485 I-Regexp engine — a true leaf, no TSON dependency.

```java
public final class TsonRegex {
    public static TsonRegex parse(String pattern);   // or TsonRegexSyntaxException
    public boolean   matches(String input);          // Thompson-NFA / Pike-VM: linear time, ReDoS-safe
    public boolean   isDisjointFrom(TsonRegex other);// exact — a symbolic product-NFA emptiness check
    public RegexNode ast();
    public String    pattern();
}
```

TSON pins its `regex` atom to I-Regexp, so this owns those semantics rather than delegating to
`java.util.regex`, a laxer superset. `isDisjointFrom` is the building block for §5.4 pattern
disjointness.

---

## `io.ltr8.tson.cli` (module `tson-cli`)

Exports nothing. `TsonCli.main` is the entry point; the wire shapes (`ValidationRun`, `FileReport`,
`ValidationReport`, `CliDiagnostic`) are package-private and declared as a real TSON schema in the
module's own `diagnostics.tn`, which `--output tson` is validated against.

---

## Resource limits

§9.1 asks (SHOULD) for configurable limits on **nesting depth, token length and document size** as DoS
hardening. **None is enforced here** — there is no `maxNestingDepth` knob, and a document a few thousand
containers deep overflows the stack. That arrives as a `StackOverflowError`, an `Error`, so it passes
through every `catch (RuntimeException)` in the reader stack and in the CLI: `tson validate` on one
prints a bare JVM stack trace and **exits 1**, as though the document were invalid. A deployment reading
untrusted documents must cap depth and bytes before handing them to a reader. Tracked in `BACKLOG.md`.

(The schema-side template materialiser does carry a depth backstop, at 64 nested instantiations, but
that is a resolver guard against non-regular recursion, not a document limit.)
