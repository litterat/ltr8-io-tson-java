package io.ltr8.tson.atom;

import io.ltr8.tson.atom.parser.BytesParser;
import io.ltr8.tson.atom.parser.Cidr4Parser;
import io.ltr8.tson.atom.parser.Cidr6Parser;
import io.ltr8.tson.atom.parser.ComplexParser;
import io.ltr8.tson.atom.parser.DateParser;
import io.ltr8.tson.atom.parser.DateTimeParser;
import io.ltr8.tson.atom.parser.DecimalParser;
import io.ltr8.tson.atom.parser.DurationParser;
import io.ltr8.tson.atom.parser.EmailParser;
import io.ltr8.tson.atom.parser.EnumParser;
import io.ltr8.tson.atom.parser.IdentifierAtom;
import io.ltr8.tson.atom.parser.FloatParser;
import io.ltr8.tson.atom.parser.IntegerParser;
import io.ltr8.tson.atom.parser.Ipv4Parser;
import io.ltr8.tson.atom.parser.Ipv6Parser;
import io.ltr8.tson.atom.parser.MacParser;
import io.ltr8.tson.atom.parser.PeriodParser;
import io.ltr8.tson.atom.parser.RationalParser;
import io.ltr8.tson.atom.parser.RegexParser;
import io.ltr8.tson.atom.parser.TextParser;
import io.ltr8.tson.atom.parser.TimeParser;
import io.ltr8.tson.atom.parser.UriParser;
import io.ltr8.tson.atom.parser.UuidParser;
import io.ltr8.tson.schema.meta.BytesType;
import io.ltr8.tson.schema.meta.Cidr4Type;
import io.ltr8.tson.schema.meta.Cidr6Type;
import io.ltr8.tson.schema.meta.ComplexType;
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
import io.ltr8.tson.schema.meta.PeriodType;
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.Unit;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;
import java.util.Optional;

/**
 * A resolved atom body to the {@link AtomType} that parses tokens against it -- the answer to "does this
 * token satisfy this type?" for a caller that holds a {@code schema.meta} body and nothing else.
 *
 * <p><b>Distinct from {@link BuiltinTypeVocabulary}, which answers a different question.</b> That maps a
 * built-in <em>name</em> ({@code int32}) to a parser with the constraints §5 fixes for it; this maps a
 * <em>body</em> ({@code IntegerType(size: {bits: 32, signed: true})}) to a parser carrying whatever
 * constraints the schema actually resolved -- so a user's own {@code !integer ^ { max: 100 }} is parsed
 * against its own maximum, which no name-keyed table could know about.
 *
 * <p><b>And distinct from the reader stack's own factories</b>, which take a name and a
 * {@code ValueReaderContext} because they build a {@code TsonTypeReader} that pulls
 * events and reports diagnostics. Nothing here reads a stream: the token is already in hand. That is what
 * lets a phase running before compilation -- {@code TsonSchemaLinker}, checking a field's {@code ~}/{@code =}
 * value against the field's declared type -- ask the question at all.
 *
 * <p><b>An empty result means "not a scalar type", never "unsupported".</b> A record, container, choice or
 * reference body has no token-level answer to give, and neither does {@code void}. A caller is expected to
 * have its own handling for one rather than to treat the absence as a failure.
 */
public final class AtomParsers {

    private AtomParsers() {
    }

    /**
     * The parser for the entry named {@code declaredName} with body {@code body}, or empty if that entry is
     * not a scalar type.
     *
     * <p>{@code complex}/{@code ipv4}/{@code ipv6} hand back their unconstrained singletons: their bodies
     * carry facets these parsers do not model (see each parser's own Javadoc), so the body selects the
     * parser without configuring it. Every other family is constructed from the body it was given.
     *
     * <p><b>{@code declaredName} is needed for exactly one family, and §4.2 makes that normative.</b>
     * {@code value}, {@code token} and {@code void} are three declarations sharing one deliberately
     * uninformative resolved shape ({@code unit}), so the name is the only thing that tells them apart --
     * the same dispatch the reader stack's own {@code unit} factory performs. Two of the three yield
     * nothing here: {@code void} because it is the type with no value, so no token is one, and
     * {@code value} because base type resolution is the <em>encoding's</em> ([TSON-DATA] §4 for the text
     * encoding, [TSON-JSON] §5.7 for JSON) rather than this vocabulary's -- reading it depends on how the
     * token was written, which an {@link AtomType} deliberately cannot see. A caller holding an encoding
     * supplies its own.
     */
    public static Optional<AtomType<?>> forType(String declaredName, Top body) {
        return Optional.ofNullable(switch (body) {
            case Unit ignored -> switch (declaredName) {
                // Neither is a token this vocabulary can answer for: `void` is the type with no value, so
                // no token is one, and `value` is read by whatever base type resolution the *encoding*
                // defines -- [TSON-DATA] §4 for the text encoding, [TSON-JSON] §5.7's own rule for JSON.
                // A caller that has one supplies it; see the note above.
                case "void", "value" -> null;
                default -> IdentifierAtom.INSTANCE;
            };
            case IntegerType t -> new IntegerParser(t);
            case TextType t -> new TextParser(t);
            case DecimalType t -> new DecimalParser(t);
            case FloatType t -> new FloatParser(t);
            case RationalType t -> new RationalParser(t);
            case UuidType t -> new UuidParser(t);
            // The alphabet rides the body's own encoding selector, like every other facet here.
            case BytesType t -> new BytesParser(t);
            case DateType t -> new DateParser(t);
            case TimeType t -> new TimeParser(t);
            case DateTimeType t -> new DateTimeParser(t);
            case DurationType t -> new DurationParser(t);
            case PeriodType t -> new PeriodParser(t);
            case UriType t -> new UriParser(t);
            case RegexType t -> new RegexParser(t);
            case MacType t -> new MacParser(t);
            case EmailType t -> new EmailParser(t);
            case Cidr4Type t -> new Cidr4Parser(t);
            case Cidr6Type t -> new Cidr6Parser(t);
            case EnumBody t -> new EnumParser(t);
            case ComplexType ignored -> ComplexParser.UNCONSTRAINED;
            case Ipv4Type t -> Ipv4Parser.of(t);
            case Ipv6Type t -> Ipv6Parser.of(t);
            default -> null;
        });
    }
}
