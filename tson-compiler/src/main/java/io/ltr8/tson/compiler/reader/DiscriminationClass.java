package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.Atom;
import io.ltr8.tson.schema.meta.Namespace;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.Sum;
import io.ltr8.tson.schema.meta.TemplateBody;
import io.ltr8.tson.schema.meta.BytesType;
import io.ltr8.tson.schema.meta.Cidr4Type;
import io.ltr8.tson.schema.meta.Cidr6Type;
import io.ltr8.tson.schema.meta.DateTimeType;
import io.ltr8.tson.schema.meta.DateType;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.DurationType;
import io.ltr8.tson.schema.meta.PeriodType;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IdentifierType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.KindBody;
import io.ltr8.tson.schema.meta.RefBody;
import io.ltr8.tson.schema.meta.HostType;
import io.ltr8.tson.schema.meta.HostnameType;
import io.ltr8.tson.schema.meta.MediaTypeType;
import io.ltr8.tson.schema.meta.Ipv4Type;
import io.ltr8.tson.schema.meta.Ipv6Type;
import io.ltr8.tson.schema.meta.MacType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.compiler.resolver.ReferenceChain;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.IriType;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;
import io.ltr8.tson.schema.meta.ValueType;
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
 * whose key type, at the end of its reference chain, is not a type a single scalar token denotes -- an atom
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
     * a single-token scalar), a nested choice or extern, or a name the namespace does not resolve. A reference
     * chain is followed to its terminal entry first (§8.3 makes an alias and its target one type); a cycle,
     * having no terminal, has no class. An empty result makes
     * the enclosing choice non-disjoint and blocks untagged recovery -- the conservative side, the tag
     * stays required.
     */
    public static Optional<DiscriminationClass> of(String name, Function<String, ? extends Top> namespace) {
        // A chain is followed to the type at its end, and an unresolved name or a cycle reaches none -- so
        // neither has a class, which is what the empty result means to every caller.
        return ReferenceChain.terminalDefinition(name, namespace).flatMap(def -> classify(def, namespace));
    }

    private static Optional<DiscriminationClass> classify(Top def, Function<String, ? extends Top> namespace) {
        return switch (def) {
            case IntegerType ignored -> Optional.of(NUMBER);
            case DecimalType ignored -> Optional.of(NUMBER);
            case FloatType f -> f.allowNan() || f.allowInfinity() ? Optional.empty() : Optional.of(NUMBER);
            case TextType ignored -> Optional.of(STRING);
            case IdentifierType ignored -> Optional.of(STRING);
            case RefBody ignored -> Optional.of(STRING); // a qualified name, written as a token
            case KindBody ignored -> Optional.of(STRING); // a type name, written as an identifier is
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
            case RecordBody ignored -> Optional.of(BRACE);
            case MapBody m -> hasScalarKey(m, namespace) ? Optional.of(BRACE) : Optional.empty();
            case ArrayBody ignored -> Optional.of(BRACKET);
            case TupleBody ignored -> Optional.of(BRACKET);
            case Atom ignored -> Optional.empty(); // rational/complex (need a tag), value, void, unknown
            case Sum ignored -> Optional.empty(); // a choice or a scope, classified through what it admits
            case Reference ignored -> Optional.empty();
            case TemplateBody ignored -> Optional.empty();
            case Namespace<?> ignored -> Optional.empty();
        };
    }

    /**
     * [TSON-SCHEMA] §5.4's key test: whether the map's key type, at the end of its reference chain, is a type a
     * single scalar token denotes by its content -- an atom-family instance or an enum, {@code value} and
     * {@code void} excepted.
     */
    private static boolean hasScalarKey(MapBody map, Function<String, ? extends Top> namespace) {
        return ReferenceChain.terminalDefinition(map.keyType().name(), namespace)
                .filter(key -> key instanceof Atom && !(key instanceof ValueType) && !(key instanceof VoidType))
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
