package io.ltr8.tson.parser;

import io.ltr8.tson.parser.ast.Annotation;
import io.ltr8.tson.parser.ast.DataValue;
import io.ltr8.tson.parser.ast.TokenForm;
import io.ltr8.tson.parser.ast.TokenValue;
import io.ltr8.tson.parser.ast.schema.ArrayContainerDef;
import io.ltr8.tson.parser.ast.schema.AtomRefinement;
import io.ltr8.tson.parser.ast.schema.ChoiceRef;
import io.ltr8.tson.parser.ast.schema.ConstructionDef;
import io.ltr8.tson.parser.ast.schema.ContainerDef;
import io.ltr8.tson.parser.ast.schema.ContainerTypeDef;
import io.ltr8.tson.parser.ast.schema.ElementType;
import io.ltr8.tson.parser.ast.schema.FieldDef;
import io.ltr8.tson.parser.ast.schema.GenericRef;
import io.ltr8.tson.parser.ast.schema.GroupDef;
import io.ltr8.tson.parser.ast.schema.InlineArrayRef;
import io.ltr8.tson.parser.ast.schema.InlineTupleRef;
import io.ltr8.tson.parser.ast.schema.Instance;
import io.ltr8.tson.parser.ast.schema.RecordDef;
import io.ltr8.tson.parser.ast.schema.RecordEntry;
import io.ltr8.tson.parser.ast.schema.ReferenceTypeDef;
import io.ltr8.tson.parser.ast.schema.RefinedDef;
import io.ltr8.tson.parser.ast.schema.RemovalSet;
import io.ltr8.tson.parser.ast.schema.SchemaDocument;
import io.ltr8.tson.parser.ast.schema.SchemaMap;
import io.ltr8.tson.parser.ast.schema.SimpleRef;
import io.ltr8.tson.parser.ast.schema.SizeSpec;
import io.ltr8.tson.parser.ast.schema.StructuralDef;
import io.ltr8.tson.parser.ast.schema.StructuralTypeDef;
import io.ltr8.tson.parser.ast.schema.TupleContainerDef;
import io.ltr8.tson.parser.ast.schema.TypeArg;
import io.ltr8.tson.parser.ast.schema.TypeDef;
import io.ltr8.tson.parser.ast.schema.TypeRef;
import io.ltr8.tson.parser.lexer.Position;
import io.ltr8.tson.parser.lexer.Token;
import io.ltr8.tson.parser.lexer.TokenType;
import io.ltr8.tson.parser.resolver.NumberGrammar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses a token stream into a {@link SchemaDocument} per the schema grammar of Part 2 §5 and its
 * ABNF (§12.1). Extends {@link Parser} to reuse the machinery Part 2 itself says it imports from
 * Part 1 §7.4 -- {@code annotation}, {@code data-value}, directive parsing, and the separator/
 * adjacency primitives -- rather than re-implementing identical grammar a second time (see {@link
 * Parser}'s own Javadoc on why it isn't {@code final}). Deliberately in the same package and
 * module as {@code Parser} and {@code Lexer}, not a separate module: the schema *grammar* is just
 * as tightly coupled to the shared lexer/parser machinery as the data grammar is (§1.3: "higher
 * parts introduce no new tokens, no new lexer modes"), the same reasoning that already keeps the
 * lexer and structural parser in one module. {@code tson-schema} is reserved for the *produced*
 * schema (§8 resolver output, Class 2's actual semantic layer) -- genuinely separate, later work,
 * not started yet -- not for this grammar layer.
 *
 * <p>This is a grammar-only parser: it builds the AST of {@link io.ltr8.tson.parser.ast.schema}
 * faithfully from source text and does not resolve, validate, or desugar anything (no namespace
 * lookups, no {@code type_definition} materialisation, no IS-A computation -- all §8 concerns).
 * "Building the schema" here means the grammar layer only.
 */
public final class SchemaParser extends Parser {

    public SchemaParser(String source) {
        super(source);
    }

    public SchemaDocument parseSchemaDocument() {
        Optional<String> id = Optional.empty();
        if (check(TokenType.DIRECTIVE) && "id".equals(peekDirectiveName())) {
            id = Optional.of(parseNamedDirective("id"));
        }

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

        SchemaMap body = parseSchemaMap();

        if (!check(TokenType.EOF)) {
            throw parseError("unexpected content after the schema map: " + describe(peek()));
        }
        return new SchemaDocument(id, meta, imports, body);
    }

    // ── Schema Map (§2.1, §12.1) ─────────────────────────────────────────

    private SchemaMap parseSchemaMap() {
        List<Annotation> annotations = parseAnnotationList();
        expect(TokenType.LBRACE, "schema map");
        if (check(TokenType.RBRACE)) {
            throw parseError("a schema map requires at least one declaration; '{}' is not permitted here (§2.1)");
        }
        Map<String, SchemaMap.Declaration> declarations = new LinkedHashMap<>();
        putDeclaration(declarations, parseDeclaration());
        while (consumeSeparatorOrCloseCheck(TokenType.RBRACE)) {
            putDeclaration(declarations, parseDeclaration());
        }
        expect(TokenType.RBRACE, "schema map");
        return new SchemaMap(annotations, declarations);
    }

    private void putDeclaration(Map<String, SchemaMap.Declaration> declarations, SchemaMap.Declaration declaration) {
        declarations.put(declaration.name(), declaration);
    }

    private SchemaMap.Declaration parseDeclaration() {
        List<Annotation> nameAnnotations = parseAnnotationList();
        String name = expectTypeName("a declaration name");
        expect(TokenType.MAP_ARROW, "declaration");
        List<Annotation> typeDefAnnotations = parseAnnotationList();
        TypeDef typeDef = parseTypeDef();
        return new SchemaMap.Declaration(nameAnnotations, name, typeDefAnnotations, typeDef);
    }

    // ── Type Definitions (§5, §12.1) ─────────────────────────────────────

    private TypeDef parseTypeDef() {
        if (check(TokenType.BANG)) {
            return parseAtomRefinementOrInstance();
        }

        List<String> typeParams = parseTypeParamsOpt();

        if (check(TokenType.TILDE)) {
            advance();
            return new StructuralTypeDef(typeParams, true, parseMandatoryStructuralDef());
        }
        if (check(TokenType.LBRACE)) {
            return new StructuralTypeDef(typeParams, false, parseRecordDef());
        }
        if (check(TokenType.LPAREN)) {
            return new ReferenceTypeDef(typeParams, parseTypeRef());
        }
        if (check(TokenType.LBRACKET)) {
            return new ContainerTypeDef(typeParams, parseContainerDef());
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

    private TypeDef parseAtomRefinementOrInstance() {
        Token bang = expect(TokenType.BANG, "atom refinement or constructor application");
        Token name = peek();
        if (name.type() != TokenType.UNQUOTED) {
            throw parseError("expected a type name after '!', found " + describe(name));
        }
        if (!bang.end().equals(name.start())) {
            throw parseError("'!' must be immediately adjacent to the type name (no whitespace)");
        }
        advance();
        rejectNumericTypeName(name);
        String target = name.text();

        if (check(TokenType.CARET)) {
            advance();
            return new AtomRefinement(target, parseDataValue());
        }
        // instance = "!" type-name ws core-value, not the spec's own literal "data-value" -- see
        // Instance's own Javadoc and SPEC-FEEDBACK.md. The constructor name goes straight into the
        // wrapping DataValue's own typeRef; there's no room in this corrected grammar for the
        // payload to carry further annotations or a second, competing type-ref.
        return new Instance(new DataValue(List.of(), Optional.of(target), parseCoreValue()));
    }

    /**
     * Supertype chain, trailing body, and removal set (§5.8, §5.9). {@code first} is already
     * consumed. On each {@code &}, one token of lookahead decides whether {@code {} } terminates
     * the chain as the trailing body or another supertype follows -- see {@code
     * ConstructionDef}'s own Javadoc and {@code SPEC-FEEDBACK.md} #14 on why this, not the literal
     * ABNF, is the correct reading.
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
            Position beforeMinus = tokens.get(pos - 1).end();
            if (beforeMinus.equals(peek().start())) {
                throw parseError("a removal clause's '-' must be separated from the preceding token "
                        + "by whitespace (otherwise it would be absorbed into a hyphenated name)");
            }
            removal = Optional.of(parseRemovalSet());
        }
        return new ConstructionDef(supertypes, body, removal);
    }

    private RemovalSet parseRemovalSet() {
        expect(TokenType.MINUS, "removal set");
        expect(TokenType.LBRACE, "removal set");
        List<String> names = new ArrayList<>();
        names.add(expectFieldNameToken("a removed field name").text());
        while (consumeSeparatorOrCloseCheck(TokenType.RBRACE)) {
            names.add(expectFieldNameToken("a removed field name").text());
        }
        expect(TokenType.RBRACE, "removal set");
        return new RemovalSet(names);
    }

    // ── Records, Fields, Groups (§5.2, §5.11, §12.1) ─────────────────────

    private RecordDef parseRecordDef() {
        expect(TokenType.LBRACE, "record");
        List<RecordEntry> entries = new ArrayList<>();
        if (!check(TokenType.RBRACE)) {
            entries.add(parseRecordEntry());
            while (consumeSeparatorOrCloseCheck(TokenType.RBRACE)) {
                entries.add(parseRecordEntry());
            }
        }
        expect(TokenType.RBRACE, "record");
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
        Token name = expectFieldNameToken("a record field");
        expect(TokenType.COLON, "record field");

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
        return new FieldDef(annotations, name.text(), type, modifier);
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
                default -> throw parseError("expected a scalar token or the absent sentinel '_' after '"
                        + (kind == FieldDef.Modifier.Kind.DEFAULT ? "~" : "=") + "', found " + describe(t));
            };
            advance();
            value = new FieldDef.Modifier.Value.Literal(new TokenValue(t.text(), form));
        }
        return new FieldDef.Modifier(kind, value);
    }

    private GroupDef parseGroupDef(List<Annotation> annotations) {
        Position start = peek().start();
        expect(TokenType.LPAREN, "field group");
        List<GroupDef.Member> members = new ArrayList<>();
        members.add(parseGroupMember());
        if (!check(TokenType.PIPE)) {
            throw new ParseException("a field group requires at least two members separated by '|' (§5.11)", start);
        }
        while (check(TokenType.PIPE)) {
            advance();
            members.add(parseGroupMember());
        }
        expect(TokenType.RPAREN, "field group");
        boolean optional = consumeAdjacentQuestion();
        return new GroupDef(annotations, members, optional);
    }

    private GroupDef.Member parseGroupMember() {
        List<Annotation> annotations = parseAnnotationList();
        Token name = expectFieldNameToken("a field group member");
        expect(TokenType.COLON, "field group member");
        return new GroupDef.Member(annotations, name.text(), parseTypeRef());
    }

    // ── Type References (§5.3, §12.1) ────────────────────────────────────

    private TypeRef parseTypeRef() {
        if (check(TokenType.LPAREN)) {
            return parseChoiceRef();
        }
        if (check(TokenType.LBRACKET)) {
            return parseInlineArrayOrTuple();
        }
        return parseTypeRefHead();
    }

    /** {@code type-name ["<" type-args ">"]} -- the type-name-based tail shared by every type-ref position and by refinement/construction heads. */
    private TypeRef parseTypeRefHead() {
        String name = expectTypeName("a type reference");
        if (check(TokenType.LESS_THAN)) {
            advance();
            List<TypeArg> args = parseTypeArgs();
            expect(TokenType.GREATER_THAN, "type arguments");
            return new GenericRef(name, args);
        }
        return new SimpleRef(name);
    }

    private TypeRef parseChoiceRef() {
        Position start = peek().start();
        expect(TokenType.LPAREN, "choice type");
        List<TypeRef> variants = new ArrayList<>();
        variants.add(parseTypeRef());
        if (!check(TokenType.PIPE)) {
            throw new ParseException("a choice type requires at least two variants separated by '|' (§5.4)", start);
        }
        while (check(TokenType.PIPE)) {
            advance();
            variants.add(parseTypeRef());
        }
        expect(TokenType.RPAREN, "choice type");
        return new ChoiceRef(variants);
    }

    private TypeRef parseInlineArrayOrTuple() {
        expect(TokenType.LBRACKET, "inline array or tuple");
        List<TypeRef> elements = new ArrayList<>();
        elements.add(parseInlineElement());
        while (consumeSeparatorOrCloseCheck(TokenType.RBRACKET)) {
            elements.add(parseInlineElement());
        }
        expect(TokenType.RBRACKET, "inline array or tuple");
        return elements.size() == 1 ? new InlineArrayRef(elements.get(0)) : new InlineTupleRef(elements);
    }

    /** A single inline element, rejecting the declaration-level-only sugar (size specs, element {@code ?}) with a diagnostic suggesting a named declaration (§5.3). */
    private TypeRef parseInlineElement() {
        TypeRef ref = parseTypeRef();
        if (check(TokenType.QUESTION)) {
            throw parseError("element/position '?' is not permitted at an inline type-ref position (§5.3); "
                    + "declare a named type instead (e.g. 'foo => [T?]') and reference it by name");
        }
        if (check(TokenType.SEMICOLON)) {
            throw parseError("a size specifier is not permitted at an inline type-ref position (§5.3); "
                    + "declare a named type instead (e.g. 'foo => [T; N]') and reference it by name");
        }
        return ref;
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
            return new TypeArg.Value(new TokenValue(t.text(), form));
        }
        if (t.type() == TokenType.UNQUOTED) {
            if (NumberGrammar.tryParse(t.text()).isPresent()) {
                advance();
                return new TypeArg.Value(new TokenValue(t.text(), TokenForm.UNQUOTED));
            }
            return new TypeArg.Ref(parseTypeRefHead());
        }
        if (t.type() == TokenType.LPAREN) {
            return new TypeArg.Ref(parseChoiceRef());
        }
        if (t.type() == TokenType.LBRACKET) {
            return new TypeArg.Ref(parseInlineArrayOrTuple());
        }
        if (t.type() == TokenType.ABSENT) {
            throw parseError("the absent sentinel '_' is not valid in a type argument position (§7.6)");
        }
        throw parseError("expected a type argument (a type reference or a scalar value), found " + describe(t));
    }

    // ── Declaration-Level Container Forms (§5.3, §12.1) ──────────────────

    private ContainerDef parseContainerDef() {
        expect(TokenType.LBRACKET, "array or tuple type");
        ElementType first = parseElementType();
        if (check(TokenType.SEMICOLON)) {
            advance();
            SizeSpec size = parseSizeSpec();
            expect(TokenType.RBRACKET, "array type");
            return new ArrayContainerDef(first, Optional.of(size));
        }
        List<ElementType> elements = new ArrayList<>();
        elements.add(first);
        while (consumeSeparatorOrCloseCheck(TokenType.RBRACKET)) {
            elements.add(parseElementType());
        }
        expect(TokenType.RBRACKET, "array or tuple type");
        if (elements.size() == 1) {
            return new ArrayContainerDef(first, Optional.empty());
        }
        return new TupleContainerDef(elements);
    }

    private ElementType parseElementType() {
        ElementType.Expr expr = check(TokenType.LBRACKET)
                ? new ElementType.Expr.Nested(parseContainerDef())
                : new ElementType.Expr.Plain(parseTypeRef());
        boolean optional = consumeAdjacentQuestion();
        return new ElementType(expr, optional);
    }

    private SizeSpec parseSizeSpec() {
        if (check(TokenType.RANGE)) {
            advance();
            return new SizeSpec.Max(expectSizeBound());
        }
        String lower = expectSizeBound();
        if (check(TokenType.RANGE)) {
            advance();
            if (check(TokenType.RBRACKET)) {
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
        expect(TokenType.GREATER_THAN, "type parameters");
        return params;
    }

    /**
     * {@code type-name = unquoted-token} (§12.1), with the added restriction that its text MUST
     * NOT match [TSON-DATA] §7.6's {@code number} production -- "numbers are not declarable names"
     * (shared verbatim by {@code param-name}).
     */
    private String expectTypeName(String context) {
        Token t = expect(TokenType.UNQUOTED, context);
        rejectNumericTypeName(t);
        return t.text();
    }

    private void rejectNumericTypeName(Token t) {
        if (NumberGrammar.tryParse(t.text()).isPresent()) {
            throw new ParseException("'" + t.text() + "' is not a valid type name -- "
                    + "names that match the number grammar are not declarable (§12.1)", t.start());
        }
    }

    /** {@code "?"} MUST be immediately adjacent to the preceding token (§12.3) -- field type, tuple/array position, or field group. */
    private boolean consumeAdjacentQuestion() {
        if (!check(TokenType.QUESTION)) {
            return false;
        }
        Position prevEnd = tokens.get(pos - 1).end();
        if (!prevEnd.equals(peek().start())) {
            throw parseError("'?' must be immediately adjacent to the preceding type (no whitespace)");
        }
        advance();
        return true;
    }
}
