package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.NamespaceBody;
import io.ltr8.tson.schema.meta.ValueEntry;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.Sum;

import io.ltr8.tson.schema.binary.BytesType;
import io.ltr8.tson.schema.net.Cidr4Type;
import io.ltr8.tson.schema.net.Cidr6Type;
import io.ltr8.tson.schema.temporal.DateTimeType;
import io.ltr8.tson.schema.temporal.DateType;
import io.ltr8.tson.schema.numeric.DecimalType;
import io.ltr8.tson.schema.temporal.DurationType;
import io.ltr8.tson.schema.temporal.PeriodType;
import io.ltr8.tson.schema.net.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.numeric.FloatType;
import io.ltr8.tson.schema.unicode.IdentifierType;
import io.ltr8.tson.schema.numeric.IntegerType;
import io.ltr8.tson.schema.meta.ReferenceBody;
import io.ltr8.tson.schema.net.HostType;
import io.ltr8.tson.schema.net.HostnameType;
import io.ltr8.tson.schema.net.MediaTypeType;
import io.ltr8.tson.schema.net.Ipv4Type;
import io.ltr8.tson.schema.net.Ipv6Type;
import io.ltr8.tson.schema.net.MacType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;

import io.ltr8.tson.schema.unicode.RegexType;
import io.ltr8.tson.schema.unicode.TextType;
import io.ltr8.tson.schema.temporal.TimeType;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import io.ltr8.tson.compiler.resolver.AliasChain;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.net.IriType;
import io.ltr8.tson.schema.net.UriType;
import io.ltr8.tson.schema.net.UuidType;
import io.ltr8.tson.schema.meta.VoidType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Function;

/**
 * The granularity at which TSON text discriminates an untagged value: [TSON-DATA] §4's three scalar
 * base-type classes plus the two container delimiter forms. One class function serves both §5.4 consumers --
 * {@code ChoiceDisjointness} derives a choice's {@code disjoint} fact as "every variant classifies, and no
 * class repeats", and {@link ChoiceReader} recovers an untagged value to the variant of its own class -- so
 * "the choice is disjoint" and "the encoding can tell the variants apart" are one statement, never two
 * facts to hold apart.
 *
 * <p><b>Records and maps share {@link #BRACE} deliberately</b> (and arrays and tuples {@link #BRACKET}):
 * both are {@code {...}} on the wire and the empty {@code {}} is ambiguous between them, so calling them
 * distinct would promise a discrimination the encoding cannot deliver on every value.
 *
 * <p><b>A class is given only to a type whose every value lands in it in every encoding</b> ([TSON-SCHEMA]
 * §5.4), so a choice's untagged values are one set whichever encoding reads them. Two kinds straddle classes
 * and get none: an approximate atom whose {@code allow_nan} or {@code allow_infinity} is true (a NaN is a
 * number token in TSON text and a string in JSON; narrowing both to false restores {@link #NUMBER}), and a map
 * whose key type, at the end of its alias chain, is not a type a single scalar token denotes -- an atom
 * other than {@code value} and {@code void}, enums included -- since such a key has no single-token spelling
 * and JSON writes the map in pairs form, as an array.
 */
public enum DiscriminationClass {

    BOOLEAN, NUMBER, STRING, BRACE, BRACKET;

    /** The three §4 scalar classes -- what {@link #ofValue} can produce, and what untagged token recovery handles. */
    public boolean scalar() {
        return this != BRACE && this != BRACKET;
    }

    /**
     * The class of the named type's untagged wire values, or empty when it has none: an atom whose untagged
     * form §4's single pass cannot recover ({@code rational}/{@code complex}, whose typed forms straddle
     * classes; {@code value} and {@code void}; a mixed-class enum; an {@code unknown}), one of [TSON-SCHEMA]
     * §5.4's two straddling kinds (an approximate atom admitting NaN or an infinity, a map whose key type is not
     * a single-token scalar), a nested choice or extern, or a name the namespace does not resolve. An alias
     * chain is followed to its terminal entry first (§8.3 makes an alias and its target one type); a cycle,
     * having no terminal, has no class. An empty result makes
     * the enclosing choice non-disjoint and blocks untagged recovery -- the conservative side, the tag
     * stays required.
     */
    public static Optional<DiscriminationClass> of(String name, Function<String, ? extends Top> namespace) {
        // A chain is followed to the type at its end, and an unresolved name or a cycle reaches none -- so
        // neither has a class, which is what the empty result means to every caller.
        return AliasChain.terminalDefinition(name, namespace).flatMap(def -> classify(def, namespace));
    }

    /**
     * The class of what a type slot holds: a name's, as {@link #of(String, Function)} gives it, or a type written in
     * place classified by its own body -- an array or a tuple bracket-class, a map brace-class where its keys are
     * scalar, a reference string-class, and a nested choice none.
     */
    public static Optional<DiscriminationClass> of(TypeExpr type, Function<String, ? extends Top> namespace) {
        return switch (type) {
            case TypeRef reference -> of(reference.name(), namespace);
            case TypeInline inline -> classify(inline.of(), namespace);
        };
    }

    private static Optional<DiscriminationClass> classify(Top def, Function<String, ? extends Top> namespace) {
        return switch (def) {
            case IntegerType ignored -> Optional.of(NUMBER);
            case DecimalType ignored -> Optional.of(NUMBER);
            case FloatType f -> f.allowNan() || f.allowInfinity() ? Optional.empty() : Optional.of(NUMBER);
            case TextType ignored -> Optional.of(STRING);
            case IdentifierType ignored -> Optional.of(STRING);
            case ReferenceBody ignored -> Optional.of(STRING); // a name, written as a token
            case UriType ignored -> Optional.of(STRING);
            case IriType ignored -> Optional.of(STRING);
            case RegexType ignored -> Optional.of(STRING);
            case UuidType ignored -> Optional.of(STRING);
            case DateType ignored -> Optional.of(STRING);
            case TimeType ignored -> Optional.of(STRING);
            case DateTimeType ignored -> Optional.of(STRING);
            case DurationType ignored -> Optional.of(STRING);
            case PeriodType ignored -> Optional.of(STRING);
            case BytesType ignored -> Optional.of(STRING);
            case EmailType ignored -> Optional.of(STRING);
            case Ipv4Type ignored -> Optional.of(STRING);
            case HostnameType ignored -> Optional.of(STRING);
            case HostType ignored -> Optional.of(STRING);
            case MediaTypeType ignored -> Optional.of(STRING);
            case Ipv6Type ignored -> Optional.of(STRING);
            case Cidr4Type ignored -> Optional.of(STRING);
            case Cidr6Type ignored -> Optional.of(STRING);
            case MacType ignored -> Optional.of(STRING);
            case EnumBody members -> ofEnum(members);
            case RecordBody record when record.isOpen() -> Optional.empty(); // an open record is no type
            case RecordBody ignored -> Optional.of(BRACE);
            case MapBody m -> hasScalarKey(m, namespace) ? Optional.of(BRACE) : Optional.empty();
            case NamespaceBody ignored -> Optional.of(BRACE); // its keys are names
            case ArrayBody ignored -> Optional.of(BRACKET);
            case TupleBody ignored -> Optional.of(BRACKET);
            case Atom ignored -> Optional.empty(); // rational/complex (need a tag), value, void, unknown
            case Sum ignored -> Optional.empty(); // a choice or a scope, classified through what it admits
            case Alias ignored -> Optional.empty();
            case ValueEntry ignored -> Optional.empty();
        };
    }

    /**
     * [TSON-SCHEMA] §5.4's key test: whether the map's key type, at the end of its alias chain, is a type a
     * single scalar token denotes by its content -- an atom-family instance or an enum, {@code void} excepted.
     */
    private static boolean hasScalarKey(MapBody map, Function<String, ? extends Top> namespace) {
        return AliasChain.terminalDefinition(map.keyType().name(), namespace)
                .filter(key -> key instanceof Atom && !(key instanceof VoidType))
                .isPresent();
    }

    /**
     * An enum over an identifier family: its members are names, written as bare tokens, so its class is their
     * shared base-type class ({@code [true false]} is BOOLEAN); mixed -> empty.
     */
    private static Optional<DiscriminationClass> ofEnum(EnumBody enumBody) {
        DiscriminationClass common = null;
        for (String member : enumBody.members()) {
            DiscriminationClass memberClass =
                    ofValue(ValueParser.INSTANCE.read(new TokenValue(member, TokenForm.UNQUOTED)));
            if (common == null) {
                common = memberClass;
            } else if (common != memberClass) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(common);
    }

    /** The base-type class of a §4-resolved host value (as {@link ValueParser} produces). Always scalar. */
    static DiscriminationClass ofValue(Object hostValue) {
        return switch (hostValue) {
            case Boolean ignored -> BOOLEAN;
            case BigInteger ignored -> NUMBER;
            case BigDecimal ignored -> NUMBER;
            case Double ignored -> NUMBER;
            default -> STRING;
        };
    }
}
