package io.ltr8.tson.compiler.atom;

import io.ltr8.tson.schema.meta.BinaryType;
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
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.Top;
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
 * {@code ValueReaderContext} because they build a {@link io.ltr8.tson.compiler.TsonTypeReader} that pulls
 * events and reports diagnostics. Nothing here reads a stream: the token is already in hand. That is what
 * lets a phase running before compilation -- {@code TsonSchemaLinker}, checking a field's {@code ~}/{@code =}
 * value against the field's declared type -- ask the question at all.
 *
 * <p><b>An empty result means "not an atom", never "unsupported".</b> A record, container, choice or
 * reference body has no token-level answer to give, and a caller is expected to have its own handling for
 * one rather than to treat the absence as a failure.
 */
public final class AtomParsers {

    private AtomParsers() {
    }

    /**
     * The parser for {@code body}, or empty if {@code body} is not an atom.
     *
     * <p>{@code complex}/{@code ipv4}/{@code ipv6} hand back their unconstrained singletons: their bodies
     * carry facets these parsers do not model (see each parser's own Javadoc), so the body selects the
     * parser without configuring it. Every other family is constructed from the body it was given.
     */
    public static Optional<AtomType<?>> forBody(Top body) {
        return Optional.ofNullable(switch (body) {
            case IntegerType t -> new IntegerParser(t);
            case TextType t -> new TextParser(t);
            case DecimalType t -> new DecimalParser(t);
            case FloatType t -> new FloatParser(t);
            case RationalType t -> new RationalParser(t);
            case UuidType t -> new UuidParser(t);
            case BinaryType t -> new BinaryParser(t);
            case DateType t -> new DateParser(t);
            case TimeType t -> new TimeParser(t);
            case DateTimeType t -> new DateTimeParser(t);
            case DurationType t -> new DurationParser(t);
            case UriType t -> new UriParser(t);
            case RegexType t -> new RegexParser(t);
            case MacType t -> new MacParser(t);
            case EmailType t -> new EmailParser(t);
            case Cidr4Type t -> new Cidr4Parser(t);
            case Cidr6Type t -> new Cidr6Parser(t);
            case EnumBody t -> new EnumParser(t);
            case ComplexType ignored -> ComplexParser.UNCONSTRAINED;
            case Ipv4Type ignored -> Ipv4Parser.UNCONSTRAINED;
            case Ipv6Type ignored -> Ipv6Parser.UNCONSTRAINED;
            default -> null;
        });
    }
}
