package io.ltr8.tson.compiler.reader;

import io.ltr8.tson.compiler.ast.TokenForm;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.atom.ValueParser;
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
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.TypeDefinition;
import io.ltr8.tson.schema.meta.TypeKind;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/**
 * The four base-type classes [TSON-DATA] §4's resolution assigns an untagged token -- the granularity at
 * which TSON text discriminates. Used for a choice's untagged structural recovery (§5.4): a value is
 * recovered to the variant whose base-type class its own §4 resolution produces, and the tag is omissible
 * only where every variant occupies a distinct class (a same-class pair, e.g. {@code (email | uri)}, is not
 * separable by §4's single pass -- see {@code SPEC-FEEDBACK.md} #23 and the class-level note there).
 */
enum BaseTypeClass {

    NULL, BOOLEAN, NUMBER, STRING;

    /**
     * The base-type class a variant's untagged wire values fall into, or empty when the variant is not a
     * cleanly-recoverable scalar -- a non-atom, an atom whose untagged form needs a tag to resolve at all
     * ({@code rational}/{@code complex}), or an ambiguous one ({@code unit}, an {@code unknown}, a mixed-class
     * enum). An empty result means "don't attempt untagged recovery for this choice", the conservative side.
     */
    static Optional<BaseTypeClass> of(TypeDefinition def) {
        if (def.kind() != TypeKind.ATOM) {
            return Optional.empty();
        }
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
            default -> Optional.empty(); // rational/complex (need a tag), unit, unknown
        };
    }

    /** An enum's class is its members' shared base-type class (e.g. {@code [true false]} is BOOLEAN); mixed -> empty. */
    private static Optional<BaseTypeClass> ofEnum(EnumBody enumBody) {
        BaseTypeClass common = null;
        for (String member : enumBody.members()) {
            BaseTypeClass memberClass = ofValue(ValueParser.INSTANCE.read(new TokenValue(member, TokenForm.UNQUOTED)));
            if (common == null) {
                common = memberClass;
            } else if (common != memberClass) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(common);
    }

    /** The base-type class of a §4-resolved host value (as {@link ValueParser} produces). */
    static BaseTypeClass ofValue(Object hostValue) {
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
