package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.schema.ArrayRef;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.ChoiceRef;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.ElementType;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.GenericRef;
import io.ltr8.tson.compiler.ast.schema.GroupDef;
import io.ltr8.tson.compiler.ast.schema.Instance;
import io.ltr8.tson.compiler.ast.schema.MapRef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RecordEntry;
import io.ltr8.tson.compiler.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.RemovalSet;
import io.ltr8.tson.compiler.ast.schema.SchemaDocument;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.SimpleRef;
import io.ltr8.tson.compiler.ast.schema.SizeSpec;
import io.ltr8.tson.compiler.ast.schema.StructuralDef;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TupleRef;
import io.ltr8.tson.compiler.ast.schema.TypeArg;
import io.ltr8.tson.compiler.ast.schema.TypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeRef;
import io.ltr8.tson.compiler.lexer.Token;
import io.ltr8.tson.schema.TsonCanonicalIdentity;
import io.ltr8.tson.schema.TsonSchemaValidationException;
import io.ltr8.tson.compiler.lexer.TokenType;
import io.ltr8.tson.compiler.atom.AtomTypeException;
import io.ltr8.tson.compiler.atom.IdentifierParser;
import io.ltr8.tson.compiler.base.NumberGrammar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a token stream into a {@link SchemaDocument} per the schema grammar of Part 2 §5 and its
 * ABNF (§12.1). Extends {@link TsonDataParser} to reuse the machinery Part 2 itself says it imports from
 * Part 1 §7.4 -- {@code annotation}, {@code data-value}, directive parsing, and the separator/
 * adjacency primitives -- rather than re-implementing identical grammar a second time (see {@link
 * TsonDataParser}'s own Javadoc on why it isn't {@code final}). Deliberately in the same package and
 * module as {@code TsonDataParser} and {@code Lexer}, not a separate module: the schema *grammar* is just
 * as tightly coupled to the shared lexer/compiler machinery as the data grammar is (§1.3: "higher
 * parts introduce no new tokens, no new lexer modes"), the same reasoning that already keeps the
 * lexer and structural compiler in one module. {@code tson-schema} is reserved for the *produced*
 * schema (§8 resolver output, Class 2's actual semantic layer) -- genuinely separate, later work,
 * not started yet -- not for this grammar layer.
 *
 * <p>This is a grammar-only compiler: it builds the AST of {@link io.ltr8.tson.compiler.ast.schema}
 * faithfully from source text and does not resolve, validate, or desugar anything (no namespace
 * lookups, no {@code type_definition} materialisation, no IS-A computation -- all §8 concerns).
 * "Building the schema" here means the grammar layer only.
 */
public final class TsonSchemaParser extends TsonDataParser {

    /**
     * Every {@link SchemaMap.Declaration} built during this parse, keyed by reference identity --
     * same reasoning as {@link #positions} (inherited from {@link TsonDataParser}), a separate
     * table because {@code SchemaMap.Declaration} is a different key type from {@code CoreValue}.
     * This is what lets a resolver look up "where was this declared" using the exact {@code
     * Declaration} object it already holds, without {@code SchemaMap.Declaration} itself carrying a
     * {@code Position} field (it's compared structurally elsewhere, same concern as {@code
     * CoreValue}).
     */
    private final Map<SchemaMap.Declaration, Position> declarationPositions = new IdentityHashMap<>();

    /**
     * Every {@link FieldDef} built during this parse, at its own name token, keyed by reference identity --
     * same table shape and same reasoning as {@link #declarationPositions}, one level finer. What lets a read
     * diagnostic that already points at {@code /person/age} position itself at {@code age} rather than at
     * {@code person}'s declaration line, which is all a per-declaration table can offer.
     */
    private final Map<FieldDef, Position> fieldPositions = new IdentityHashMap<>();

    /**
     * Where a recovered parse's problems go, and the switch between the two modes: {@code null} (the
     * {@link #parseSchemaDocument()} entry point) is fail-fast, and the first {@link TsonParseException}
     * leaves this class. A receiver turns on the declaration-level recovery in {@link #parseSchemaMap}.
     */
    private TsonDiagnosticsReceiver receiver;

    /** Count of problems handed to {@link #receiver} -- the {@code ctx.reported()} idiom, for a receiver that keeps no list. */
    private int reported;

    /** This document's own {@code !!id} once read, so a recovered declaration's diagnostic can say which schema it is in. */
    private String schemaId = "";

    /** The declaration currently being parsed, or {@code ""} before its name token is reached -- the {@code /name} schema pointer a recovered diagnostic carries. */
    private String declarationInProgress = "";

    public TsonSchemaParser(String source) {
        super(source);
    }

    /** Every {@link SchemaMap.Declaration} built by {@link #parseSchemaDocument()}, mapped to its own name token's start {@link Position} -- see {@link #declarationPositions}'s own Javadoc for why this is identity-keyed. */
    public Map<SchemaMap.Declaration, Position> declarationPositions() {
        return Collections.unmodifiableMap(declarationPositions);
    }

    /**
     * Everything this parse recorded about where things sit in the source, as the one carrier the resolver
     * chain threads -- see {@link SchemaPositions}.
     */
    public SchemaPositions schemaPositions() {
        return new SchemaPositions(new IdentityHashMap<>(declarationPositions), new IdentityHashMap<>(fieldPositions));
    }

    /** Parses fail-fast: the first syntax error anywhere in the document leaves as a {@link TsonParseException}. */
    public SchemaDocument parseSchemaDocument() {
        // Only recovery can empty this, and recovery needs a receiver, which this entry point never sets.
        return parseDocumentBody().orElseThrow();
    }

    /**
     * Parses reporting every <em>declaration</em>'s syntax error to {@code receiver} instead of throwing at the
     * first, so an author sees all of them in one pass -- the parse-phase peer of what resolution and linking
     * already do. Panic-mode recovery: a failed declaration is reported, its remaining tokens are skipped to the
     * next {@code name =>} back at schema-map depth, and parsing resumes there.
     *
     * <p><b>A parse that reported anything hands back no document at all</b> ({@link Optional#empty()}), even
     * though the declarations around the broken ones did parse. A half-document is not a thing a later phase
     * can use: resolving it reports every reference to a dropped declaration as unresolved, on top of the
     * syntax error that is the real problem, and §8.1's error categories are per layer precisely so a layer's
     * verdict is not second-guessed by the next one. The surviving nodes exist only to keep parsing.
     *
     * <p><b>Two failures are not recoverable and still throw.</b> A malformed <em>header</em> (a missing or
     * ill-placed {@code !!meta}, a directive argument that isn't a URI) has no following construct to
     * resynchronise on -- the schema map has not started. And a token that will not <em>lex</em> raises
     * {@link io.ltr8.tson.compiler.lexer.LexException} from underneath this recovery, since resynchronising
     * means reading the very tokens that don't exist; the lexer being fail-fast is the floor on how much of a
     * broken document this can report ({@code STRUCTURED-OUTPUT.md} tracks it).
     */
    public Optional<SchemaDocument> parseSchemaDocument(TsonDiagnosticsReceiver receiver) {
        this.receiver = receiver;
        Optional<SchemaDocument> document = parseDocumentBody();
        return reported > 0 ? Optional.empty() : document;
    }

    /**
     * This document's {@code !!id} canonicalized (§2.2.1) so a parse diagnostic's {@code schemaId} matches the
     * one every later phase reports under, <b>falling back to the id as written</b> when it does not canonicalize.
     * A non-canonical id is a real error, but it is the resolver's to report; raising it from the grammar layer
     * would replace a syntax diagnostic the author can act on with a different complaint about a different line.
     */
    private static String canonicalIdOrAsWritten(String id) {
        try {
            return TsonCanonicalIdentity.canonicalize(id);
        } catch (TsonSchemaValidationException e) {
            return id;
        }
    }

    /** How many problems {@link #parseSchemaDocument(TsonDiagnosticsReceiver)} reported -- zero meaning the document parsed clean. */
    public int reported() {
        return reported;
    }

    private Optional<SchemaDocument> parseDocumentBody() {
        Optional<String> documentId = Optional.empty();
        if (check(TokenType.DIRECTIVE) && "id".equals(peekDirectiveName())) {
            documentId = Optional.of(parseNamedDirective("id"));
            schemaId = canonicalIdOrAsWritten(documentId.get());
        }
        final Optional<String> id = documentId;

        if (!check(TokenType.DIRECTIVE) || !"meta".equals(peekDirectiveName())) {
            throw parseError("expected '!!meta' (a schema document requires exactly one, "
                    + "immediately after '!!id' if present)");
        }
        String meta = parseNamedDirective("meta");

        List<String> imports = new ArrayList<>();
        while (check(TokenType.DIRECTIVE) && "import".equals(peekDirectiveName())) {
            imports.add(parseNamedDirective("import"));
        }
        if (check(TokenType.DIRECTIVE)) {
            throw parseError("directive '!!" + peekDirectiveName() + "' is not permitted here "
                    + "(expected '!!import' or the schema map's opening '{')");
        }

        Optional<SchemaMap> body = parseSchemaMap();

        if (!check(TokenType.EOF)) {
            throw parseError("unexpected content after the schema map: " + describe(peek()));
        }
        return body.map(map -> new SchemaDocument(id, meta, imports, map));
    }

    // ── Schema Map (§2.1, §12.1) ─────────────────────────────────────────

    /**
     * Empty only when recovery reported every declaration there was, leaving nothing §2.1's at-least-one rule
     * could be satisfied with -- {@link #parseSchemaDocument(TsonDiagnosticsReceiver)} already discards the
     * document in that case, so there is no map to build and nothing that would read it.
     */
    private Optional<SchemaMap> parseSchemaMap() {
        List<Annotation> annotations = parseAnnotationList();
        expect(TokenType.LBRACE, "a schema map's opening '{'");
        if (check(TokenType.RBRACE)) {
            throw parseError("a schema map requires at least one declaration; '{}' is not permitted here (§2.1)");
        }
        int mapDepth = nesting();
        Map<String, SchemaMap.Declaration> declarations = new LinkedHashMap<>();
        boolean more = true;
        while (more) {
            try {
                putDeclaration(declarations, parseDeclaration());
                more = consumeSeparatorOrCloseCheck(TokenType.RBRACE);
            } catch (TsonParseException e) {
                if (receiver == null) {
                    throw e;
                }
                report(e);
                more = recoverToNextDeclaration(mapDepth);
            }
        }
        expect(TokenType.RBRACE, "a schema map's closing '}'");
        return declarations.isEmpty() ? Optional.empty() : Optional.of(new SchemaMap(annotations, declarations));
    }

    /** Hands one recovered declaration's syntax error to {@link #receiver}, pointed at the declaration it was found in. */
    private void report(TsonParseException e) {
        reported++;
        receiver.report(Diagnostic.ofSchemaSyntaxError(schemaId, declarationInProgress, e));
    }

    /**
     * Panic-mode resynchronisation after a reported declaration: discards tokens until the next declaration
     * begins, returning {@code true} if one does and {@code false} at the schema map's own {@code }} or at
     * end of input.
     *
     * <p><b>A declaration start is {@code name =>} back at {@code mapDepth}, and nothing else.</b> Two tokens
     * decide it unambiguously, which is exactly the lookahead this stream has. The looser candidates were
     * rejected: a bare name is most of a broken declaration's own wreckage, and a leading {@code @} is equally
     * an annotation on a field. The cost of the strict rule is that an annotated declaration resynchronises at
     * its <em>name</em>, so the recovered node loses annotations the author wrote -- harmless, since a document
     * that reported here is discarded whole.
     *
     * <p><b>Depth is the cursor's, not a tally kept here</b> ({@link TsonDataStream#nesting()}): a declaration
     * failing inside a record body leaves the cursor on that <em>record's</em> closing brace, and a local
     * counter starting at zero would read it as the schema map's own and stop one declaration in.
     */
    private boolean recoverToNextDeclaration(int mapDepth) {
        declarationInProgress = "";
        while (true) {
            if (check(TokenType.EOF)) {
                return false;
            }
            if (nesting() == mapDepth) {
                if (check(TokenType.RBRACE)) {
                    return false;
                }
                if (check(TokenType.UNQUOTED) && peekSecond().type() == TokenType.MAP_ARROW) {
                    return true;
                }
            }
            advance();
        }
    }

    private void putDeclaration(Map<String, SchemaMap.Declaration> declarations, SchemaMap.Declaration declaration) {
        declarations.put(declaration.name(), declaration);
    }

    private SchemaMap.Declaration parseDeclaration() {
        List<Annotation> nameAnnotations = parseAnnotationList();
        Position namePosition = peek().start();
        String name = expectTypeName("a declaration name");
        declarationInProgress = name;
        expect(TokenType.MAP_ARROW, "a declaration's '=>'");
        List<Annotation> typeDefAnnotations = parseAnnotationList();
        TypeDef typeDef = parseTypeDef();
        SchemaMap.Declaration declaration = new SchemaMap.Declaration(nameAnnotations, name, typeDefAnnotations, typeDef);
        declarationPositions.put(declaration, namePosition);
        declarationInProgress = "";
        return declaration;
    }

    // ── Type Definitions (§5, §12.1) ─────────────────────────────────────

    private TypeDef parseTypeDef() {
        // The parameter list comes first, so one token then decides the alternative: `!` with no parameters
        // is an instance or an atom refinement, `!` with parameters an instance-template (§12.1). `<` only
        // ever starts a parameter list, so consuming it costs no lookahead.
        List<String> typeParams = parseTypeParamsOpt();

        if (check(TokenType.BANG)) {
            return parseAtomRefinementOrInstance(typeParams);
        }

        if (check(TokenType.TILDE)) {
            advance();
            return new StructuralTypeDef(typeParams, true, parseMandatoryStructuralDef());
        }
        if (check(TokenType.LBRACE)) {
            return braceTypeDef(typeParams);
        }
        if (check(TokenType.LPAREN)) {
            return new ReferenceTypeDef(typeParams, parseTypeRef());
        }
        if (check(TokenType.LBRACKET)) {
            return new ReferenceTypeDef(typeParams, parseBracket());
        }

        TypeRef head = parseTypeRefHead();
        if (check(TokenType.CARET)) {
            advance();
            return new StructuralTypeDef(typeParams, false, new RefinedDef(head, parseRecordDef()));
        }
        if (check(TokenType.AMPERSAND) || check(TokenType.MINUS)) {
            return new StructuralTypeDef(typeParams, false, parseConstructionDefContinuation(head));
        }
        if (check(TokenType.LBRACE)) {
            throw parseError("expected '^' (refinement) or '&' (composition) after a bare type-ref, "
                    + "found '{'");
        }
        return new ReferenceTypeDef(typeParams, head);
    }

    /** The {@code structural-def} reached after a leading {@code ~} -- unlike {@link #parseTypeDef}'s own dispatch, a bare type-ref here (nothing following) is a parse error: {@code ~} promises a refinement, composition, or record body. */
    private StructuralDef parseMandatoryStructuralDef() {
        if (check(TokenType.LBRACE)) {
            return parseRecordDef();
        }
        TypeRef head = parseTypeRefHead();
        if (check(TokenType.CARET)) {
            advance();
            return new RefinedDef(head, parseRecordDef());
        }
        if (check(TokenType.AMPERSAND) || check(TokenType.MINUS)) {
            return parseConstructionDefContinuation(head);
        }
        throw parseError("expected '^', '&', '-', or a record body after '~' (constructor marker)");
    }

    /**
     * §12.2's brace dispatch at a type-def position, where a {@code '{'} opens either a record body or the
     * map sugar. The token is consumed and the decision made on what follows -- the consume-one-then-inspect
     * idiom [TSON-DATA] §2.8 already fixes for the data grammar's own brace, within the same budget of one
     * consumed token plus one of lookahead. {@code '}'} (the empty record, {@code top}'s shape), {@code '('}
     * (a leading field group) and {@code '@'} (§6 annotations, which the map sugar admits nowhere inside its
     * braces) commit to a record; a name followed by {@code '=>'}, or by {@code '<'} opening a generic key's
     * arguments, commits to a map. A name followed by anything else is a record whose field is missing its
     * {@code ':'}, and {@link #parseFieldDef} names it as one.
     */
    private TypeDef braceTypeDef(List<String> typeParams) {
        expect(TokenType.LBRACE, "a record body's or map type's opening '{'");
        if (braceOpensMap()) {
            return new ReferenceTypeDef(typeParams, parseMapBody());
        }
        return new StructuralTypeDef(typeParams, false, parseRecordBody());
    }

    /** The dispatch decision itself, with the {@code '{'} already consumed -- see {@link #braceTypeDef}. */
    private boolean braceOpensMap() {
        return check(TokenType.UNQUOTED)
                && (peekSecond().type() == TokenType.MAP_ARROW || peekSecond().type() == TokenType.LESS_THAN);
    }

    /**
     * The two brace meanings, distinguished (§12.2). Everywhere except a type-def position a {@code '{'} opens
     * the map sugar and nothing else: a bare record body is not spellable at a type position (§5.2), so a
     * field name behind the brace is answered by saying which of the two constructs the author reached for.
     */
    private void requireMapBrace() {
        if (!braceOpensMap()) {
            throw parseError("'{' here opens the map sugar '{K => V}'; a record body is not permitted at a "
                    + "type position (§5.2), so declare a named record type and reference it by name");
        }
    }

    private TypeDef parseAtomRefinementOrInstance(List<String> typeParams) {
        Token bang = expect(TokenType.BANG, "an atom refinement or constructor application ('!name')");
        Token name = peek();
        if (name.type() != TokenType.UNQUOTED) {
            throw mismatch("a type name immediately after '!'");
        }
        if (!bang.end().equals(name.start())) {
            throw parseError("'!' must be immediately adjacent to the type name (no whitespace)");
        }
        advance();
        requireIdentifierName(name);
        String target = name.text();

        if (check(TokenType.CARET)) {
            // §12.1 gives `atom-refinement` no parameter list: a refinement of an atom instance has no
            // parameter to take, so the two forms are told apart here rather than by a production of their
            // own.
            if (!typeParams.isEmpty()) {
                throw parseError("a parameterized atom refinement is not a type-def (§12.1): '^' takes no type "
                        + "parameters, since a refinement of an atom instance has none to bind");
            }
            advance();
            // atom-refinement = "!" type-name ws "^" ws record-def (§12.1) -- a braced record of constraint
            // bindings, and nothing wider. The `^` has already committed this production and the grammar
            // does not backtrack, so anything but a brace is this production's own error rather than a
            // fall-through to `instance`, whose payload could not start with `^` either.
            if (!check(TokenType.LBRACE)) {
                throw mismatch("'{' -- an atom refinement's body is a braced record of constraint "
                        + "bindings (§5.5), never a bare value, a second type-ref or an annotation");
            }
            return new AtomRefinement(target, new DataValue(List.of(), Optional.empty(), parseCoreValue()));
        }
        // instance = [type-params] "!" type-name ws core-value (§12.1) -- see Instance's own Javadoc. The
        // constructor name goes straight into the wrapping DataValue's own typeRef; there is no room in this
        // production for the payload to carry further annotations or a second, competing type-ref.
        //
        // A parameter list makes it a template and changes nothing else, which is the point: the payload is
        // held rather than read against the constructor's own vocabulary, so every core-value the closed form
        // admits the open one admits too -- a collection included, which is what the separate template-def
        // production could not express.
        return new Instance(typeParams, new DataValue(List.of(), Optional.of(target), parseCoreValue()));
    }

    /**
     * Supertype chain, trailing body, and removal set (§5.8, §5.9). {@code first} is already
     * consumed. On each {@code &}, one token of lookahead decides whether {@code {} } terminates
     * the chain as the trailing body or another supertype follows -- see {@code ConstructionDef}'s own
     * Javadoc; §12.1's {@code construction-def} draws its operands from {@code supertype-ref} and admits the
     * trailing {@code record-def} on each alternative.
     */
    private ConstructionDef parseConstructionDefContinuation(TypeRef first) {
        List<TypeRef> supertypes = new ArrayList<>();
        supertypes.add(first);
        Optional<RecordDef> body = Optional.empty();
        while (check(TokenType.AMPERSAND)) {
            advance();
            if (check(TokenType.LBRACE)) {
                body = Optional.of(parseRecordDef());
                break;
            }
            supertypes.add(parseTypeRef());
        }
        Optional<RemovalSet> removal = Optional.empty();
        if (check(TokenType.MINUS)) {
            // §12.3: "-" MUST be separated from the preceding token by whitespace. After an
            // unquoted supertype name the lexer already guarantees this (otherwise the hyphen
            // would have been absorbed into the name, per the same footgun as data-grammar's
            // "-"/continuation rule) -- but after a construction's closing "}" it does not, since
            // "}" isn't an unquoted-continuation character either way, so this check is only ever
            // load-bearing in that second case.
            Position beforeMinus = lastTokenEnd();
            if (beforeMinus.equals(peek().start())) {
                throw parseError("a removal clause's '-' must be separated from the preceding token "
                        + "by whitespace (otherwise it would be absorbed into a hyphenated name)");
            }
            removal = Optional.of(parseRemovalSet());
        }
        return new ConstructionDef(supertypes, body, removal);
    }

    private RemovalSet parseRemovalSet() {
        expect(TokenType.MINUS, "a removal clause's '-'");
        expect(TokenType.LBRACE, "a removal set's opening '{'");
        List<String> names = new ArrayList<>();
        names.add(expectFieldNameToken("a removed field name").text());
        while (consumeSeparatorOrCloseCheck(TokenType.RBRACE)) {
            names.add(expectFieldNameToken("a removed field name").text());
        }
        expect(TokenType.RBRACE, "a removal set's closing '}'");
        return new RemovalSet(names);
    }

    // ── Records, Fields, Groups (§5.2, §5.11, §12.1) ─────────────────────

    private RecordDef parseRecordDef() {
        expect(TokenType.LBRACE, "a record body's opening '{'");
        return parseRecordBody();
    }

    /**
     * {@link #parseRecordDef} past its opening {@code '{'} -- {@link #braceTypeDef} consumes that token
     * before it knows which construct it opened.
     */
    private RecordDef parseRecordBody() {
        List<RecordEntry> entries = new ArrayList<>();
        if (!check(TokenType.RBRACE)) {
            entries.add(parseRecordEntry());
            while (consumeSeparatorOrCloseCheck(TokenType.RBRACE)) {
                entries.add(parseRecordEntry());
            }
        }
        expect(TokenType.RBRACE, "a record body's closing '}'");
        return new RecordDef(entries);
    }

    private RecordEntry parseRecordEntry() {
        List<Annotation> annotations = parseAnnotationList();
        if (check(TokenType.LPAREN)) {
            return parseGroupDef(annotations);
        }
        return parseFieldDef(annotations);
    }

    private FieldDef parseFieldDef(List<Annotation> annotations) {
        // Taken before the token is consumed, exactly as a declaration's own name position is.
        Position namePosition = peek().start();
        Token name = expectFieldNameToken("a record field name");
        if (check(TokenType.MAP_ARROW)) {
            throw parseError("a record body's entries are 'name: type'; '=>' begins a map type only where a "
                    + "type is expected (§12.2), not in a refinement body, a composition tail or a "
                    + "constructor vocabulary");
        }
        expect(TokenType.COLON, "a record field's ':'");

        Optional<FieldDef.FieldType> type = Optional.empty();
        Optional<FieldDef.Modifier> modifier = Optional.empty();
        if (check(TokenType.TILDE) || check(TokenType.EQUAL)) {
            modifier = Optional.of(parseFieldModifier());
        } else {
            TypeRef ref = parseTypeRef();
            boolean optional = consumeAdjacentQuestion();
            type = Optional.of(new FieldDef.FieldType(ref, optional));
            if (check(TokenType.TILDE) || check(TokenType.EQUAL)) {
                modifier = Optional.of(parseFieldModifier());
            }
        }
        FieldDef field = new FieldDef(annotations, name.text(), type, modifier);
        fieldPositions.put(field, namePosition);
        return field;
    }

    private FieldDef.Modifier parseFieldModifier() {
        FieldDef.Modifier.Kind kind = check(TokenType.TILDE) ? FieldDef.Modifier.Kind.DEFAULT : FieldDef.Modifier.Kind.FIXED;
        advance();

        FieldDef.Modifier.Value value;
        if (check(TokenType.ABSENT)) {
            advance();
            value = new FieldDef.Modifier.Value.Absent();
        } else {
            Token t = peek();
            TokenForm form = switch (t.type()) {
                case UNQUOTED -> TokenForm.UNQUOTED;
                case SINGLE_LINE_STRING -> TokenForm.SINGLE_LINE_QUOTED;
                case MULTI_LINE_STRING -> TokenForm.MULTI_LINE_QUOTED;
                default -> throw mismatch("a scalar token or the absent sentinel '_' after '"
                        + (kind == FieldDef.Modifier.Kind.DEFAULT ? "~" : "=") + "'");
            };
            advance();
            value = new FieldDef.Modifier.Value.Literal(recordPosition(new TokenValue(t.text(), form), t.start()));
        }
        return new FieldDef.Modifier(kind, value);
    }

    private GroupDef parseGroupDef(List<Annotation> annotations) {
        Position start = peek().start();
        expect(TokenType.LPAREN, "a field group's opening '('");
        List<GroupDef.Member> members = new ArrayList<>();
        members.add(parseGroupMember());
        if (!check(TokenType.PIPE)) {
            throw new TsonParseException("a field group requires at least two members separated by '|' (§5.11)", start);
        }
        while (check(TokenType.PIPE)) {
            advance();
            members.add(parseGroupMember());
        }
        expect(TokenType.RPAREN, "a field group's closing ')'");
        boolean optional = consumeAdjacentQuestion();
        return new GroupDef(annotations, members, optional);
    }

    private GroupDef.Member parseGroupMember() {
        List<Annotation> annotations = parseAnnotationList();
        Token name = expectFieldNameToken("a field group member's name");
        expect(TokenType.COLON, "a field group member's ':'");
        return new GroupDef.Member(annotations, name.text(), parseTypeRef());
    }

    // ── Type References (§5.3, §12.1) ────────────────────────────────────

    /**
     * A type-ref position (§5.3): a field's type, a group member's type, a choice variant, an inline element,
     * a type argument. <b>{@code !} is rejected here by name rather than by falling through to "expected a
     * type reference"</b> -- writing the refinement inline ({@code quantity: !integer ^ { min: 1 }}) is the
     * natural first attempt, and the grammar's answer (hoist it to its own declaration, reference it by name)
     * is a one-line fix an author cannot guess from a token-level complaint.
     */
    private TypeRef parseTypeRef() {
        if (check(TokenType.LPAREN)) {
            return parseChoiceRef();
        }
        if (check(TokenType.LBRACKET)) {
            return parseBracket();
        }
        if (check(TokenType.LBRACE)) {
            return parseMap();
        }
        if (check(TokenType.BANG)) {
            throw parseError("an atom refinement or constructor application is not permitted at a type-ref "
                    + "position (§5.3); declare a named type instead (e.g. 'quantity_t => !integer ^ { min: 1 }') "
                    + "and reference it by name");
        }
        return parseTypeRefHead();
    }

    /** {@code type-name ["<" type-args ">"]} -- the type-name-based tail shared by every type-ref position and by refinement/construction heads. */
    private TypeRef parseTypeRefHead() {
        String name = expectTypeName("a type reference");
        if (check(TokenType.LESS_THAN)) {
            advance();
            List<TypeArg> args = parseTypeArgs();
            expect(TokenType.GREATER_THAN, "a type argument list's closing '>'");
            return new GenericRef(name, args);
        }
        return new SimpleRef(name);
    }

    private TypeRef parseChoiceRef() {
        Position start = peek().start();
        expect(TokenType.LPAREN, "a choice type's opening '('");
        List<TypeRef> variants = new ArrayList<>();
        variants.add(parseTypeRef());
        if (!check(TokenType.PIPE)) {
            throw new TsonParseException("a choice type requires at least two variants separated by '|' (§5.4)", start);
        }
        while (check(TokenType.PIPE)) {
            advance();
            variants.add(parseTypeRef());
        }
        expect(TokenType.RPAREN, "a choice type's closing ')'");
        return new ChoiceRef(variants);
    }

    /**
     * {@code bracket-type} (§12.1) -- the one bracket production, serving every position. One element with an
     * optional size specifier is an {@link ArrayRef}; two or more are a {@link TupleRef}. Arity is all that
     * distinguishes them, which is why both alternatives share a first element.
     *
     * <p>There is no separate inline form, and no position where a size specifier or an element {@code ?} is
     * refused. That split existed because a sized form had no inline representation to carry it; every form
     * lifts to an entry now, so the restriction protected nothing and is gone rather than relocated: §12.1
     * has one bracket production, and §5.3 makes both legal at every type-ref position.
     */
    private TypeRef parseBracket() {
        expect(TokenType.LBRACKET, "an array or tuple type's opening '['");
        ElementType first = parseElementType();
        if (check(TokenType.SEMICOLON)) {
            advance();
            SizeSpec size = parseSizeSpec(TokenType.RBRACKET);
            expect(TokenType.RBRACKET, "an array type's closing ']'");
            return new ArrayRef(first, Optional.of(size));
        }
        List<ElementType> elements = new ArrayList<>();
        elements.add(first);
        while (consumeSeparatorOrCloseCheck(TokenType.RBRACKET)) {
            elements.add(parseElementType());
        }
        expect(TokenType.RBRACKET, "an array or tuple type's closing ']'");
        return elements.size() == 1 ? new ArrayRef(first, Optional.empty()) : new TupleRef(elements);
    }

    /** {@code map-type} (§12.1) including its opening brace -- {@link #braceTypeDef} reaches the body directly, having consumed one already. */
    private TypeRef parseMap() {
        expect(TokenType.LBRACE, "a map type's opening '{'");
        requireMapBrace();
        return parseMapBody();
    }

    /**
     * {@code map-key = type-name ["<" type-args ">"]} (§12.1) -- a plain reference, optionally carrying type
     * arguments, and never a bracket or paren form. That restriction is what holds {@link #braceOpensMap}'s
     * record/map decision to two tokens; a composite key type earns a named declaration instead.
     */
    private TypeRef parseMapKey() {
        TypeRef key = parseTypeRefHead();
        rejectKeyQuestion();
        return key;
    }

    /**
     * A map's <em>key</em> never admits {@code ?}. [TSON-DATA] §2.9 forbids the absent sentinel in key
     * position outright and [TSON-SCHEMA] §7.6 restates it, so there is no state for the marker to bind and
     * no reading under which one could be wanted -- unlike the value side, which takes it ({@link
     * #parseMapBody}).
     */
    private void rejectKeyQuestion() {
        if (check(TokenType.QUESTION)) {
            throw parseError("'?' is not permitted on a map type's key (§7.6); a map key is never absent, "
                    + "so there is no state to mark -- write it on the value if the value may be absent");
        }
    }

    /**
     * The single-entry rule (§5.3): a map <em>type</em> carries one key type and one value type, mirroring the
     * data notation's shape rather than its arity. A second entry -- the habit the data grammar's multi-entry
     * {@code {k => v  k2 => v2}} teaches -- is named as one rather than reported as an unexpected token.
     */
    private void requireMapClose() {
        if (!check(TokenType.RBRACE)) {
            throw parseError("a map type is a single 'key => value' entry (§5.3), however many entries a map "
                    + "value may hold; found " + describe(peek()));
        }
    }

    private List<TypeArg> parseTypeArgs() {
        List<TypeArg> args = new ArrayList<>();
        args.add(parseTypeArg());
        while (consumeSeparatorOrCloseCheck(TokenType.GREATER_THAN)) {
            args.add(parseTypeArg());
        }
        return args;
    }

    /**
     * {@code type-arg = type-ref / value-literal} (§12.1, §5.10). A quoted token or a numeric
     * unquoted token is unambiguously a {@link TypeArg.Value}; any other unquoted token parses as
     * a {@link TypeArg.Ref} -- §12.1's own prose says this classification is deliberately deferred
     * to the semantic layer ("settled against the applied signature's parameter kinds... not by
     * the grammar"), so a bare enum-member-shaped argument parses as a type reference here and is
     * reclassified later, exactly as the spec describes.
     */
    private TypeArg parseTypeArg() {
        Token t = peek();
        if (t.type() == TokenType.SINGLE_LINE_STRING || t.type() == TokenType.MULTI_LINE_STRING) {
            advance();
            TokenForm form = t.type() == TokenType.SINGLE_LINE_STRING ? TokenForm.SINGLE_LINE_QUOTED : TokenForm.MULTI_LINE_QUOTED;
            return new TypeArg.Value(recordPosition(new TokenValue(t.text(), form), t.start()));
        }
        if (t.type() == TokenType.UNQUOTED) {
            if (NumberGrammar.tryParse(t.text()).isPresent()) {
                advance();
                return new TypeArg.Value(recordPosition(new TokenValue(t.text(), TokenForm.UNQUOTED), t.start()));
            }
            return new TypeArg.Ref(parseTypeRefHead());
        }
        if (t.type() == TokenType.LPAREN) {
            return new TypeArg.Ref(parseChoiceRef());
        }
        if (t.type() == TokenType.LBRACKET) {
            return new TypeArg.Ref(parseBracket());
        }
        if (t.type() == TokenType.LBRACE) {
            return new TypeArg.Ref(parseMap());
        }
        if (t.type() == TokenType.ABSENT) {
            throw parseError("the absent sentinel '_' is not valid in a type argument position (§7.6)");
        }
        throw mismatch("a type argument (a type reference or a scalar value)");
    }

    // ── Declaration-Level Container Forms (§5.3, §12.1) ──────────────────

    /**
     * {@code map-type} past its opening {@code '{'} (§12.1): one {@code key => value} entry, an optional size
     * specifier after {@code ';'}, and the closing brace. The size specifier is the bracket form's own, under
     * the same grammar and the same {@code N <= M} coherence rule (§5.3).
     *
     * <p>The value is an {@link #parseElementType}, so {@code {K => V?}} marks it OPTIONAL exactly as
     * {@code [T?]} marks an element -- §12.3's adjacency rule included, rather than restated. The key is not
     * ({@link #rejectKeyQuestion}). This is [TSON-SCHEMA] §7.6's own permission made sayable: the value is
     * optional there with no condition attached, which leaves an author unable to require one. See {@code
     * SPEC-FEEDBACK.md} #12 -- §5.3 still says neither side admits the marker.
     */
    private MapRef parseMapBody() {
        TypeRef key = parseMapKey();
        expect(TokenType.MAP_ARROW, "a map type's '=>'");
        ElementType value = parseElementType();
        Optional<SizeSpec> size = Optional.empty();
        if (check(TokenType.SEMICOLON)) {
            advance();
            size = Optional.of(parseSizeSpec(TokenType.RBRACE));
        }
        requireMapClose();
        expect(TokenType.RBRACE, "a map type's closing '}'");
        return new MapRef(key, value, size);
    }

    /**
     * {@code element-type = type-ref ["?"]} (§12.1). Nesting needs no case of its own: a bracket or map form
     * <em>is</em> a type-ref, so {@code [[T; 2]; 3]} is the recursion here and not a second production.
     */
    private ElementType parseElementType() {
        TypeRef ref = parseTypeRef();
        return new ElementType(ref, consumeAdjacentQuestion());
    }

    /**
     * {@code size-spec} (§12.1), shared by the bracket and map forms -- {@code closing} is the bracket or brace
     * the open-ended {@code N..} form runs up against.
     */
    private SizeSpec parseSizeSpec(TokenType closing) {
        if (check(TokenType.RANGE)) {
            advance();
            return new SizeSpec.Max(expectSizeBound());
        }
        String lower = expectSizeBound();
        if (check(TokenType.RANGE)) {
            advance();
            if (check(closing)) {
                return new SizeSpec.Min(lower);
            }
            return new SizeSpec.Ranged(lower, expectSizeBound());
        }
        return new SizeSpec.Exact(lower);
    }

    private String expectSizeBound() {
        return expect(TokenType.UNQUOTED, "a size bound").text();
    }

    // ── Names and Small Helpers ───────────────────────────────────────────

    private List<Annotation> parseAnnotationList() {
        List<Annotation> annotations = new ArrayList<>();
        while (check(TokenType.AT)) {
            annotations.add(parseAnnotation());
        }
        return annotations;
    }

    private List<String> parseTypeParamsOpt() {
        if (!check(TokenType.LESS_THAN)) {
            return List.of();
        }
        advance();
        List<String> params = new ArrayList<>();
        params.add(expectTypeName("a type parameter"));
        while (consumeSeparatorOrCloseCheck(TokenType.GREATER_THAN)) {
            params.add(expectTypeName("a type parameter"));
        }
        expect(TokenType.GREATER_THAN, "a type parameter list's closing '>'");
        return params;
    }

    /**
     * {@code type-name = identifier} (§12.1, through the kernel's {@code type_name} role; {@code param-name}
     * shares it verbatim). The token is matched in full against meta-kernel's {@code identifier} profile, which
     * <b>subsumes</b> §12.1's separate "numbers are not declarable names" rule rather than standing beside it:
     * identifier-Start is {@code XID_Start}, and every spelling the number grammar admits begins with a digit, a
     * sign or a dot, all of which sit in token-Start only so a <em>number</em> can be an unquoted token. One rule
     * therefore answers both, and answers the names that begin like a number without being one.
     */
    private String expectTypeName(String context) {
        Token t = expect(TokenType.UNQUOTED, context);
        requireIdentifierName(t);
        return t.text();
    }

    private void requireIdentifierName(Token t) {
        try {
            IdentifierParser.validate(t.text());
        } catch (AtomTypeException e) {
            throw new TsonParseException("'" + t.text() + "' is not a valid type name -- " + e.getMessage(),
                    t.start());
        }
    }

    /** {@code "?"} MUST be immediately adjacent to the preceding token (§12.3) -- field type, tuple/array position, or field group. */
    private boolean consumeAdjacentQuestion() {
        if (!check(TokenType.QUESTION)) {
            return false;
        }
        Position prevEnd = lastTokenEnd();
        if (!prevEnd.equals(peek().start())) {
            throw parseError("'?' must be immediately adjacent to the preceding type (no whitespace)");
        }
        advance();
        return true;
    }
}
