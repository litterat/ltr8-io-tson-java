package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.ValueParser;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.BinaryType;
import io.ltr8.tson.schema.meta.Cidr4Type;
import io.ltr8.tson.schema.meta.Cidr6Type;
import io.ltr8.tson.schema.meta.DateTimeType;
import io.ltr8.tson.schema.meta.DateType;
import io.ltr8.tson.schema.meta.DecimalType;
import io.ltr8.tson.schema.meta.DurationType;
import io.ltr8.tson.schema.meta.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.meta.FloatType;
import io.ltr8.tson.schema.meta.IntegerType;
import io.ltr8.tson.schema.meta.Ipv4Type;
import io.ltr8.tson.schema.meta.Ipv6Type;
import io.ltr8.tson.schema.meta.MacType;
import io.ltr8.tson.schema.meta.MapBody;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.Reference;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.TupleBody;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The granularity at which TSON text discriminates an untagged value: [TSON-DATA] §4's four scalar
 * base-type classes plus the two container delimiter forms. One class function serves both §5.4 consumers --
 * {@code ChoiceDisjointness} derives a choice's {@code disjoint} fact as "every variant classifies, and no
 * class repeats", and {@link ChoiceReader} recovers an untagged value to the variant of its own class -- so
 * "the choice is disjoint" and "the encoding can tell the variants apart" are one statement, never two
 * facts to hold apart.
 *
 * <p><b>Records and maps share {@link #BRACE} deliberately</b> (and arrays and tuples {@link #BRACKET}):
 * both are {@code {...}} on the wire and the empty {@code {}} is ambiguous between them, so calling them
 * distinct would promise a discrimination the encoding cannot deliver on every value.
 */
public enum DiscriminationClass {

    NULL, BOOLEAN, NUMBER, STRING, BRACE, BRACKET;

    /** The four §4 scalar classes -- what {@link #ofValue} can produce, and what untagged token recovery handles. */
    public boolean scalar() {
        return this != BRACE && this != BRACKET;
    }

    /**
     * The class of the named type's untagged wire values, or empty when it has none: an atom whose untagged
     * form §4's single pass cannot recover ({@code rational}/{@code complex}, whose typed forms straddle
     * classes; {@code unit}; a mixed-class enum; an {@code unknown}), a nested choice or extern, or a name
     * the namespace does not resolve. A reference chain is followed to its terminal entry first (§8.3 makes
     * an alias and its target one type); a cycle, having no terminal, has no class. An empty result makes
     * the enclosing choice non-disjoint and blocks untagged recovery -- the conservative side, the tag
     * stays required.
     */
    public static Optional<DiscriminationClass> of(String name, Map<String, TypeDefinition> namespace) {
        Set<String> walked = new HashSet<>();
        String current = name;
        while (walked.add(current)) {
            TypeDefinition def = namespace.get(current);
            if (def == null) {
                return Optional.empty();
            }
            if (def.body() instanceof Reference reference) {
                current = reference.target();
                continue;
            }
            return classify(def);
        }
        return Optional.empty(); // a reference cycle has no terminal entry, so no class
    }

    private static Optional<DiscriminationClass> classify(TypeDefinition def) {
        return switch (def.body()) {
            case IntegerType ignored -> Optional.of(NUMBER);
            case DecimalType ignored -> Optional.of(NUMBER);
            case FloatType ignored -> Optional.of(NUMBER);
            case TextType ignored -> Optional.of(STRING);
            case UriType ignored -> Optional.of(STRING);
            case RegexType ignored -> Optional.of(STRING);
            case UuidType ignored -> Optional.of(STRING);
            case DateType ignored -> Optional.of(STRING);
            case TimeType ignored -> Optional.of(STRING);
            case DateTimeType ignored -> Optional.of(STRING);
            case DurationType ignored -> Optional.of(STRING);
            case BinaryType ignored -> Optional.of(STRING);
            case EmailType ignored -> Optional.of(STRING);
            case Ipv4Type ignored -> Optional.of(STRING);
            case Ipv6Type ignored -> Optional.of(STRING);
            case Cidr4Type ignored -> Optional.of(STRING);
            case Cidr6Type ignored -> Optional.of(STRING);
            case MacType ignored -> Optional.of(STRING);
            case EnumBody members -> ofEnum(members);
            case RecordBody ignored -> Optional.of(BRACE);
            case MapBody ignored -> Optional.of(BRACE);
            case ArrayBody ignored -> Optional.of(BRACKET);
            case TupleBody ignored -> Optional.of(BRACKET);
            default -> Optional.empty(); // rational/complex (need a tag), unit, unknown, choice, extern
        };
    }

    /** An enum's class is its members' shared base-type class (e.g. {@code [true false]} is BOOLEAN); mixed -> empty. */
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
            case null -> NULL;
            case Boolean ignored -> BOOLEAN;
            case BigInteger ignored -> NUMBER;
            case BigDecimal ignored -> NUMBER;
            case Double ignored -> NUMBER;
            default -> STRING;
        };
    }
}
