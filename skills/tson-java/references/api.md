# Public API inventory

Module by module. Javadoc on the source is the authority where this and the code disagree; **`./gradlew
build` runs javadoc**, so a dangling `{@link}` fails the build.

Only the packages listed here are exported. JPMS enforcement is real, not convention — `tson-compiler`'s
`lexer`, `atom`, `base`, `reader` and `resolver`, and `tson-atom`'s `parser`, are genuinely unreachable from
another module.

---

## `io.ltr8.tson` (module `tson`) — the front door

### `Tson`

```java
public final class Tson {
    public static Tson standard();                     // ProcessorConfig.defaults()
    public static Tson of(ProcessorConfig config);

    public TsonDocumentPeek begin(String|InputStream source);   // the header, before the body
    public TsonObjectReader objectReader();      // schema-aware, over this instance's bindRegistry
    public TsonTreeReader   treeReader();        // schema-aware, over this instance's treeRegistry
    public TsonObjectWriter objectWriter();
    public TsonTreeWriter   treeWriter();
    public DataBindContext  dataBindContext();
    public ProcessorPolicy  processorPolicy();   // the §8.2 policies, the limits, the Unicode data version
    public LimitsPolicy     limitsPolicy();

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

`Tson.standard()` bootstraps meta-kernel / meta.tn / core.tn and returns an immutable instance.
Resolution is **always bind-anchored** (meta instances bind to `schema.meta.Top`), so `resolve` takes no
mode; only the final compile picks one, which is why **the read mode is which registry you hold**.

`validate` *is* `treeReader()` with a collecting receiver — there is no second implementation to drift
from it. `validateSchema` stops at the first phase that reports anything (parse, then resolve, then
link), javac-style, so consequences of an earlier error are not reported as independent problems; and a
schema that reported anything is never registered.

---

## `io.ltr8.tson.base` (module `tson-base`) — the shared vocabulary

How a problem is stated, what this processor will admit and spend, and where it may obtain a schema.
**Every encoding reads the same values here** — [TSON-JSON] §9.4 makes JSON report in [TSON-DATA]
§8.1's four categories and add none of its own, and §10.1 gives it §9.1's bounds with the same
defaults. It is also the one module where the `Tson` prefix is dropped, since the name it would
disambiguate from here is another encoding's type in this same library.

### `ProcessorConfig`

```java
public final class ProcessorConfig {                 // io.ltr8.tson.base
    public static ProcessorConfig defaults();

    public ProcessorConfig withSchemaAccess(SchemaAccess access);      // where a schema may come from
    public ProcessorConfig withDataBindContext(DataBindContext ctx);   // which classes the types bind to
    public ProcessorConfig withMetaNameBinder(DataNameBinder binder);  // a consumer's own meta vocabulary

    public ProcessorConfig withProcessorPolicy(ProcessorPolicy policy);   // the whole value
    public ProcessorConfig withIdentifierPolicy(UnicodePolicy policy);    // declared names
    public ProcessorConfig withTokenPolicy(UnicodePolicy policy);         // every token a read pulls
    public ProcessorConfig withLimits(LimitsPolicy limits);               // §9.1's resource bounds

    public SchemaAccess schemaAccess();
    public DataBindContext dataBindContext();
    public DataNameBinder metaNameBinder();
    public ProcessorPolicy processorPolicy();
}
```

**It is a value, not a builder** — every setter returns a new instance and there is no `build()`.
Construction belongs to whichever encoding is being built: `Tson.of(config)` for TSON text,
`Json.of(config)` for JSON, from the same value. The three policy components each derive from what is
already stated rather than replacing it, so a piecewise configuration and a composed one agree.

Where schemas come from is one value: `withSchemaAccess(SchemaAccess.httpSchemas(hosts…))`,
`SchemaAccess.fileSchemas(host, dir)`, or `SchemaAccess.of(source)` for one you built. The
mutual-exclusion rules among those live on `SchemaAccess.Builder`, not here.

### Schema sources

```java
public interface SchemaSource {                     // io.ltr8.tson.base.source
    String fetch(String uri);                       // throws SchemaFetchException and nothing else
    static SchemaSource registeredOnly();           // the default: refuses everything, NOT_PERMITTED
    static SchemaSource ofMap(Map<String, String> schemas);   // matched by canonical identity
}

public final class SchemaAccess {                   // a source plus the FetchPolicy governing it
    public static SchemaAccess registeredOnly();    // ProcessorConfig's default
    public static SchemaAccess of(SchemaSource source);           // the general seam
    public static SchemaAccess httpSchemas(String... hosts);      // one-call forms
    public static SchemaAccess fileSchemas(String host, Path directory);
    public static Builder builder();                // httpSchemas, fileSchemas, source, fetchPolicy

    public SchemaSource source();
    public FetchPolicy fetchPolicy();
}

public record FetchPolicy(int maxDocumentBytes, int maxCachedSchemas,
                          boolean requireContentHashPin) {
    public static FetchPolicy defaults();
    public FetchPolicy withMaxDocumentBytes(int n);      // and withMaxCachedSchemas,
                                                         // withRequireContentHashPin
    public static final int DEFAULT_MAX_DOCUMENT_BYTES = 1 << 20;
    public static final int DEFAULT_MAX_CACHED_SCHEMAS = 128;
}

public final class HttpSchemaSource implements SchemaSource, AutoCloseable {
    public static Builder builder();                // allowHost, mapHost, fetchPolicy, timeout,
                                                    // httpClient (and the three FetchPolicy
                                                    // components as short forms)
    public void preload(String... references);
    public boolean isCached(String reference);
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
}

public final class FileSchemaSource implements SchemaSource {
    public static Builder builder();                // mapHost(host, dir), fetchPolicy
    public void preload(String... references);
    public boolean isCached(String reference);
}
```

**`SchemaAccess` is the pair, and it is what `ProcessorConfig` takes** — a source with no policy has
unstated bounds and a policy with no source governs nothing, so handing them separately makes every
caller reassemble the pair. The mutual-exclusion rules among `httpSchemas`/`fileSchemas`/`source` live
on `SchemaAccess.Builder`, stated once where the access is built.

A `timeout` is deliberately not a `FetchPolicy` component: a directory has none, and a component one
implementation silently ignores is what makes a shared policy value untrustworthy. It stays
`HttpSchemaSource.Builder`'s own.

`SchemaReference` (§2.2.1's rules on what an identity may be) is **package-private** among these — shared
by both sources, nameable by neither a consumer nor another module. Neither source verifies the
`?sha256=` pin or the fetched `!!id`; the loader does both, and `requireContentHashPin` adds the one
thing it cannot — that a pin be *present*.

### Diagnostics

```java
public record Diagnostic(…) { public enum Code { … } }        // see references/diagnostics.md
public interface DiagnosticsReceiver { void report(Diagnostic d);
    static DiagnosticsReceiver throwing();
    static DiagnosticsCollector collecting(); }
public final class DiagnosticsCollector implements DiagnosticsReceiver {
    public List<Diagnostic> diagnostics();  public boolean isEmpty(); }
// The root package holds Diagnostic, the receivers, SourcePosition, CanonicalIdentity, ProcessorConfig,
// and the exceptions whose fact is the processor's rather than one encoding's: ReadException,
// ParseException, WriteException, LimitExceededException, SchemaValidationException,
// BindMismatchException, MissingBindingException, SchemaFetchException, ContentHashMismatchException.
// The classifying half stays with each encoding (TsonDiagnostics, JsonDiagnostics); the one factory on
// the record is Diagnostic.ofLimitExceeded.

public record Position(int line, int column, int byteOffset)   // io.ltr8.tson.compiler --
        implements SourcePosition {}                           // any encoding's own position type
                                                               // may implement SourcePosition instead
public record SchemaLocation(…)   // io.ltr8.tson.compiler -- id + pointer + position, accumulated
                                  // as a read descends
```

```java
public record ProcessorPolicy(UnicodePolicy identifierPolicy,       // io.ltr8.tson.base.policy
                              UnicodePolicy tokenPolicy,
                              LimitsPolicy limits,
                              String unicodeDataVersion) {
    public static ProcessorPolicy defaults();
    public static ProcessorPolicy of(UnicodePolicy identifier, UnicodePolicy token, LimitsPolicy limits);
    public ProcessorPolicy withIdentifierPolicy(UnicodePolicy policy);
    public ProcessorPolicy withTokenPolicy(UnicodePolicy policy);    // refused if per-segment
    public ProcessorPolicy withLimits(LimitsPolicy limits);
}

public record LimitsPolicy(int maxDepth) {                           // io.ltr8.tson.base.policy
    public static final int DEFAULT_MAX_DEPTH = 64;
    public static LimitsPolicy defaults();
    public LimitsPolicy withMaxDepth(int depth);
}
```

The compact constructor refuses a per-segment token policy with `IllegalArgumentException`. `LimitsPolicy`
is §9.1's bounds; nesting depth is the one enforced, by the event stream, and a document past it is reported
as `LIMIT_EXCEEDED` — not a verdict, the document perhaps being valid and readable by a processor configured
for more.

**What a report is read against, stated once.** [TSON-DATA] §8.2's rules read Unicode data the Consortium
does not freeze, at a level this deployment chose, so the same document can be refused here and accepted
elsewhere — and that reason is in neither the document nor the schema. `Tson.processorPolicy()` gives it, and
so does `processorPolicy()` on either read facade, which is the one to use when a derived reader may have
changed a policy. A refusal carries **no** copy of its own: it is constant for a run, and what a sender needs
in order not to be refused is this record *before* it writes. `tson policy` prints it from the shell.

### Unicode policy

```java
public final class UnicodePolicy {                          // io.ltr8.tson.base.policy
    public enum Level { ASCII_ONLY, SINGLE_SCRIPT, HIGHLY_RESTRICTIVE,
                        MODERATELY_RESTRICTIVE, MINIMALLY_RESTRICTIVE, UNRESTRICTED }

    public static UnicodePolicy of(Level level);
    public static UnicodePolicy asciiOnly();
    public static UnicodePolicy singleScript();
    public static UnicodePolicy highlyRestrictive();       // the identifier default, whole-name
    public static UnicodePolicy moderatelyRestrictive();
    public static UnicodePolicy scriptsUnchecked();
    public static UnicodePolicy unrestricted();            // the token default

    public UnicodePolicy perSegment();                     // identifiers only -- tokenPolicy throws on one
    public UnicodePolicy permitting(UnicodeScript... scripts);

    public static String dataVersion();                        // the Unicode data version, e.g. "16.0"
    public Level level();                                      // the three below are the whole of a policy
    public boolean isPerSegment();
    public List<Set<UnicodeScript>> permittedScripts();
    public boolean checksScripts();
    public boolean appliesIdentifierProfile();
    public Optional<String> violation(String text);
    // equals/hashCode are by value: two policies configured alike are equal
}
```

### The other `tson-base` packages

- `io.ltr8.tson.base.atom` — the **host values** built-in atoms read to: `Rational`, `Complex`,
  `CidrInet4Network`/`CidrInet6Network` (and their `CidrNetwork` supertype), `InternetAddress`. What you
  hold after reading `!rational` or `!cidr4`, and what a component declares to bind one.
- `io.ltr8.tson.base.bind` — `AtomContext`: `hostTypes()` (the list to `registerAtoms` on a builder) and
  `defaultContext()`, the context both front doors start from.
- `io.ltr8.tson.base.io` — `ByteSource`/`ByteSink`: bytes, never characters; closing releases only what was
  acquired, and closing is not flushing.
- `io.ltr8.tson.base.unicode` — the UCD-derived tables (`Xid`, the identifier profile, scripts,
  confusables) the §8.2 rules read.
- `io.ltr8.tson.base.diagnostics` — the rule classes whose prose and `expected` both encodings report.

---

## `io.ltr8.tson.atom` (module `tson-atom`) — the built-in atom vocabulary

Which tokens each atom family accepts and what host value results, over a `String`, shared by both
encodings. Exports `io.ltr8.tson.atom` and `io.ltr8.tson.atom.number`; the per-family parsers are in the
unexported `parser` package.

```java
public interface AtomType<T> { … }                 // an atom's parsing contract over text (§5.2)
public final class VocabularyAtoms {                // the atom host classes, and how each binds
    public static Map<Class<?>, Entry> defaults(); … }
public sealed abstract class AtomTypeException extends RuntimeException
        permits AtomParseException, AtomValidationException { public String expected(); }
```

`AtomParseException` is a token outside the atom's grammar (`ATOM_FORM_INVALID`); `AtomValidationException`
a token that parsed and broke a declared constraint (`ATOM_CONSTRAINT_VIOLATION`). `expected()` is the
constraint that failed, never the type's name.

---

## `io.ltr8.tson.compiler` (module `tson-compiler`) — the engine

### Readers

```java
public final class TsonTreeReader {
    public TsonTreeReader();                                  // standalone = schemaless (Class 1)
    public TsonTreeReader(TsonCompiledSchemaRegistry tree);

    public TsonTreeReader withSchema(String schemaUri);
    public TsonTreeReader withDiagnostics(DiagnosticsReceiver receiver);
    public TsonTreeReader withProcessorPolicy(ProcessorPolicy policy);
    public TsonTreeReader withTokenPolicy(UnicodePolicy policy);
    public TsonTreeReader withIdentifierPolicy(UnicodePolicy policy);
    public TsonTreeReader withLimits(LimitsPolicy limits);
    public TsonTreeReader preservingUnknownTypeRefs();
    public ProcessorPolicy processorPolicy();                 // what THIS reader judges under
    public LimitsPolicy    limitsPolicy();

    // source is String, InputStream, ByteSource (tson-base's io) or a TsonDocumentPeek
    public TsonValue    read(source);                        // honours the document's own !!schema
    public TsonDocument readDocument(source);                // + its !!id and !!schema
    public TsonValue    readWithoutSchema(source);           // String, InputStream or peek
    public TsonValue    readAs(source, String typeName);
    public TsonValue    read(TsonReadContext ctx);
}

public final class TsonObjectReader {
    public TsonObjectReader();
    public TsonObjectReader(DataBindContext context);
    public TsonObjectReader(TsonCompiledSchemaRegistry bind, DataBindContext context);
    // the same derivations and policy accessors, plus:
    public TsonObjectReader ignoringUnknownFields();          // schemaless only: drop, don't report
    public <T> T                    read(source, Class<T> targetClass);
    public <T> TsonObjectDocument<T> readDocument(source, Class<T> targetClass);
    public <T> T                    readWithoutSchema(source, Class<T> targetClass);
    public <T> T                    readAs(source, String typeName, Class<T> targetClass);  // not from a peek
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
public final class TsonDocumentPeek {
    public static TsonDocumentPeek of(String|InputStream source);                   // total: never throws
    public static TsonDocumentPeek of(String|InputStream source, ProcessorPolicy policy);
    public TsonDocumentHeader header();
    public boolean isSchemaDocument();
}                                   // then reader.read(peek) / readAs(peek, …) continues the same stream

public record TsonDocumentHeader(Optional<String> id, Optional<String> schema, Optional<String> meta) {
    public static final TsonDocumentHeader NONE;
    public boolean isSchemaDocument();                                  // it carries !!meta
}
```

`tson.begin(source)` is `TsonDocumentPeek.of(source, tson.processorPolicy())`. The reader that continues
from a peek must judge under the same policy the header was read under, and refuses the peek if not.

§7.1's classification from the opening bytes — at most two directives of lookahead and no value parsing.
A gigabyte document costs the same as a two-line one, and a document whose body will not parse still
classifies.

### Content hashing

```java
public final class TsonContentHash {
    public static String sha256(byte[] document);              // every byte past the !!id line
    public static Optional<String> sha256IfAddressable(byte[] document);  // empty: not content-addressable
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
    public TsonCompiledSchema get(String uri, DiagnosticsReceiver receiver);
    public TsonCompiledSchema compile(TsonLinkedSchema linked);
    public TsonCompiledMetaRegistry core();
}

@FunctionalInterface
public interface TsonTypeReader<T> { T read(TsonReadContext ctx); }
```

`TsonTypeReader` is **strictly one method** — it reads one value at a cursor and polices nothing around
it. Framing and error policy live in the facades. Compilation is **eager**, so a broken entry surfaces
at compile time; an entry that cannot be built becomes an `ErrorReader` reporting `NOT_IMPLEMENTED` at
read, with two exceptions: a `BindMismatchException` fails the compile, and a
`MissingBindingException` is thrown unwrapped from its reader.

`TsonCompiledMetaRegistry` is the shared meta/resolution core: it compiles and caches **only**
meta-layer schemas, resolves/links/registers everything else without compiling it, and owns content-hash
verification, the bootstrap and §2.2.3's import-cycle guard.

### Pipeline stages, if you need one directly

`TsonSchemaParser`, `TsonSchemaResolver`, `TsonSchemaLinker`, `TsonSchemaCompiler`, `TsonDataParser`,
`TsonDataStream`, `TsonDiagnostics` (the classifiers over this engine's exceptions).
`TsonSchemaParser` / `TsonSchemaResolver` / `TsonSchemaLinker` each have a reporting overload that collects
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
- `io.ltr8.tson.compiler.config` — `ResolverBindContext` (`defaultContext()`, `registerDefaults(builder)`:
  the schema pipeline's own bind context), `SchemaMetaNameBinder` (`INSTANCE`, `defaultContext()`,
  `contextExtendedWith(binder)`, `extendedWith(binder)`: the meta vocabulary's names), and
  `SourcePositionStringBridge`.

---

## `io.ltr8.tson.tree` (module `tson-tree`)

A true leaf — depends on **nothing**, not even `tson-annotation`.

```java
public sealed interface TsonValue
        permits TsonRecord, TsonMap, TsonArray, TsonTuple, TsonAtom, TsonAbsent, TsonMissing,
                TsonScopedValue {

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

public record TsonScopedValue(String schema, TsonValue root) implements TsonValue {}  // §7.8, transparent

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

public final class TsonBundledSchemas {
    public static final String META_KERNEL_ID / META_ID / CORE_ID;
    public static final String META_KERNEL_SHA256 / META_SHA256 / CORE_SHA256;
    public static Optional<String> declaredSha256(String uri);
    public static String fetch(String uri);
}
```

`register` rejecting a duplicate identity, plus an unmodifiable `entries()`, **is** the "locked"
guarantee. `CanonicalIdentity` (§2.2.1's algorithm — strip scheme, strip query, nothing else) is
`tson-base`'s, `io.ltr8.tson.base.CanonicalIdentity`.

The module exports two packages: `io.ltr8.tson.schema` (the above) and `io.ltr8.tson.schema.meta`, the resolved-schema
value model — pure records, sealed interfaces and enums, §8's `TypeDefinition` et al. The host values the atoms read to
are not here (see `tson-base`'s `atom` package, above). `Top` is sealed except for its one deliberately open branch,
**`Data`**, which a consumer's own class implements: §4.1's fourth base kind, where an instance of a meta-schema's own
constructor lives when the thing it describes is not a data type. A consumer registers such a class by carrying
`@Typename` and being findable by the `metaNameBinder`; `Data.references()` is how its own type references reach the
linker, declared rather than discovered.

---

## `io.ltr8.bind` (module `tson-bind`)

```java
public class DataBindContext {
    public static Builder builder();                // allowAny, allowSerializable, nameBinder,
                                                    // nameBinderAliases, nameBinderPackages, profile,
                                                    // registerAtom(Class[, DataBridge]), registerAtoms(List)
    public Optional<String> profile();
    public DataClass getDescriptor(Class<?> targetClass) throws DataBindException;
    public DataClass getDescriptor(Class<?> targetClass, Type parameterizedType) throws DataBindException;
    public DataClass getDescriptor(String schemaTypeName) throws DataBindException;
    public Supplier<DataClass> componentSource(Class<?> targetClass, Type parameterizedType);
}
```

**Everything is fixed at `build()`** — atoms, the name binder and the profile are the builder's, and the
built context has no mutators.

```java
```

Also exports `io.ltr8.bind.mapper` and `io.ltr8.bind.bridge`. See `references/bindings.md`.

---

## `io.ltr8.annotation` (module `tson-annotation`)

`@Typename`, `@Field`, `@Record`, `@Tuple`, `@Union`, `@Atom`, `@Transparent`, `@Profile`, `@Unbound`,
`@FieldOrder`, `@Namespace`, plus `Annotations` / `Annotation` (the wire-annotation carrier),
`Annotated` / `AnnotatedMap`, and the `DataBridge` / `ToData` conversion interfaces. See `references/bindings.md`.

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

## `io.ltr8.tson.json` (module `tson-json`) — the JSON encoding

A stack of its own — lexer, event stream, tree, readers, writers, schema-directed readers — with **no
dependency on `tson-compiler`**. Exports `io.ltr8.tson.json`, `io.ltr8.tson.json.tree` and
`io.ltr8.tson.json.stream`.

```java
public final class Json {
    public static Json standard();                      // AtomContext.defaultContext(), default policy
    public static Json of(ProcessorConfig config);      // the same value Tson.of takes
    public Json withSchemas(TsonSchemaLoader loader);   // e.g. tson.schemaRegistry()

    public JsonTreeReader   treeReader();
    public JsonObjectReader objectReader();
    public JsonTreeWriter   treeWriter();
    public JsonObjectWriter objectWriter();
    public List<Diagnostic> validate(String|InputStream source, String schemaUri, String rootType);
    public ProcessorPolicy processorPolicy();  public LimitsPolicy limitsPolicy();
    public DataBindContext dataBindContext();

    public static JsonValue parse(String|InputStream source[, ProcessorPolicy policy]);  // JEP 540's spelling
    public static String toDisplayString(JsonValue value[, String indent]);
}

public final class JsonTreeReader {         // withProcessorPolicy, withDiagnostics, withSchema(uri)
    public JsonValue read(String|InputStream|ByteSource source);
    public JsonValue readAs(String|InputStream|ByteSource source, String rootType);   // schema-directed
}
public final class JsonObjectReader {       // withProcessorPolicy, withDiagnostics, ignoringUnknownFields,
                                            // withSchema(uri) -- from Json.withSchemas(loader).objectReader()
    public <T> T read(String|InputStream|ByteSource source, Class<T> type);                  // the class is the schema
    public <T> T readAs(String|InputStream|ByteSource source, String typeName, Class<T> type);  // schema-directed
}
public final class JsonTreeWriter {          // indented(), indented(indent)
    public String toJson(JsonValue v);  public void write(JsonValue v, OutputStream|Appendable|ByteSink out); }
public final class JsonObjectWriter {        // standard(), using(context), indented(…)
    public String toJson(Object v);     public void write(Object v, OutputStream|Appendable|ByteSink out); }

public sealed interface JsonValue           // io.ltr8.tson.json.tree
        permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBoolean, JsonNull { … }
```

**A JSON document binds out of band** — it names neither its schema nor its root type, so both are
arguments. Obtaining the schema is the TSON engine's job: resolve through `Tson` and hand
`tson.schemaRegistry()` to `withSchemas`. A schema-directed tree read returns a `JsonValue`, never a `TsonValue`;
`JsonObjectReader.readAs` validates in full and binds, all-or-nothing, the peer of `TsonObjectReader.readAs`. Both
report in the same `Diagnostic` vocabulary through `ReadException` — there is no `JsonParseException`.

---

## `io.ltr8.tson.cli` (module `tson-cli`)

Exports nothing. `TsonCli.main` is the entry point; the wire shapes (`ValidationRun`, `FileReport`,
`ValidationReport`, `CliDiagnostic`) are package-private and declared as a real TSON schema in the
module's own `diagnostics.tn`, which `--output tson` is validated against.

---

## Resource limits

§9.1 asks (SHOULD) for configurable limits on **nesting depth, token length and document size** as DoS
hardening. **Nesting depth is enforced**: `LimitsPolicy.maxDepth`, 64 by default, checked by the event
stream as a container opens, so a document a few thousand containers deep is refused before it can overflow
the stack. Configure it on `ProcessorConfig.withLimits`, on either reader's `withLimits`, or with
`tson validate --max-depth`. The refusal is `LIMIT_EXCEEDED` (a `LimitExceededException` on a fail-fast
read) and is **not a verdict**. Token length and document size are not bounded yet; a deployment reading
untrusted documents caps bytes before handing them to a reader. `LimitsPolicy`'s Javadoc and `BACKLOG.md`
carry what is left.

(The schema-side template materialiser carries its own depth backstop, at 64 nested instantiations — a
resolver guard against non-regular recursion, not a document limit.)
