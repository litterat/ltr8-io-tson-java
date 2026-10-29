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
import io.ltr8.tson.atom.parser.IdentifierParser;
import io.ltr8.tson.atom.parser.FloatParser;
import io.ltr8.tson.atom.parser.IntegerParser;
import io.ltr8.tson.atom.parser.KindParser;
import io.ltr8.tson.atom.parser.RefParser;
import io.ltr8.tson.atom.parser.HostParser;
import io.ltr8.tson.atom.parser.HostnameParser;
import io.ltr8.tson.atom.parser.MediaTypeParser;
import io.ltr8.tson.atom.parser.Ipv4Parser;
import io.ltr8.tson.atom.parser.Ipv6Parser;
import io.ltr8.tson.atom.parser.MacParser;
import io.ltr8.tson.atom.parser.PeriodParser;
import io.ltr8.tson.atom.parser.RationalParser;
import io.ltr8.tson.atom.parser.RegexParser;
import io.ltr8.tson.atom.parser.TextParser;
import io.ltr8.tson.atom.parser.TimeParser;
import io.ltr8.tson.atom.parser.IriParser;
import io.ltr8.tson.atom.parser.UriParser;
import io.ltr8.tson.atom.parser.UuidParser;
import io.ltr8.unicode.Normalization;
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
import io.ltr8.tson.schema.meta.PeriodType;
import io.ltr8.tson.schema.meta.RationalType;
import io.ltr8.tson.schema.meta.RegexType;
import io.ltr8.tson.schema.meta.TextType;
import io.ltr8.tson.schema.meta.TimeType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.IriType;
import io.ltr8.tson.schema.meta.UriType;
import io.ltr8.tson.schema.meta.UuidType;
import io.ltr8.tson.schema.meta.ValueType;
import io.ltr8.tson.schema.meta.VoidType;
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
     * The parser for an entry with body {@code body}, or empty if that entry is not a scalar type.
     *
     * <p>{@code complex} hands back its unconstrained singleton: its body's {@code component} facet is one the
     * parser does not model (see {@code ComplexParser}), so the body selects the parser without configuring it.
     * Every other family is constructed from the body it was given.
     *
     * <p><b>{@code value} and {@code void} yield nothing.</b> {@code void} is the type with no value, so no token
     * is one, and {@code value} is read by base type resolution, which is the <em>encoding's</em> ([TSON-DATA]
     * §4 for the text encoding, [TSON-JSON] §5.7 for JSON) rather than this vocabulary's -- reading it depends on
     * how the token was written, which an {@link AtomType} deliberately cannot see. A caller holding an encoding
     * supplies its own.
     */
    public static Optional<AtomType<?>> forType(Top body) {
        return forType(body, Normalization.NONE);
    }

    /**
     * {@link #forType(Top)}, with the form an enum matches its members in: its name type's {@code normalization}
     * ([TSON-SCHEMA] §5.5), which the body does not carry -- {@code enum_type.name_type} names an entry the governing
     * meta may hold, which only linking sees, so the caller supplies what linking recorded
     * ({@code TsonLinkedSchema.enumForms}). Ignored for every other body.
     *
     * <p>A reference body is this library's gap here, an {@link UnsupportedOperationException}: these callers judge a
     * value a schema writes ({@code ~}/{@code =}, a template argument) by its token, and a reference's value is judged
     * by what its name reaches, which only a value body's recorded names are.
     */
    public static Optional<AtomType<?>> forType(Top body, Normalization enumForm) {
        if (body instanceof RefBody reference) {
            throw new UnsupportedOperationException("a value a schema writes at a reference-typed position ('ref<"
                    + reference.of() + ">') is not yet judged: a reference's value is judged by what its name reaches");
        }
        return forType(body, enumForm, false);
    }

    /**
     * {@link #forType(Top, Normalization)} for a position read under a schema that {@code governsSchemas} -- a meta
     * layer, whose documents are schemas or describe one. A name such a document writes at a kind-typed position
     * resolves in the described schema's namespace and not in the closure the kind's members were computed over, so
     * the kind reads the type-name grammar alone ({@link KindParser#namesOnly}) and that schema's link, or ingest,
     * judges membership ([TSON-SCHEMA] §4.2, §8.1). Every other body is read as {@link #forType(Top, Normalization)}
     * reads it.
     *
     * <p>A reference ({@code ref_type}) is read only here, with {@code governsSchemas} true: what its name reaches
     * is judged when the schema holding it links, and a document such a schema governs is one that links. Any
     * schema may declare a reference (SPEC-FEEDBACK.md #7), and under any other schema a reference-typed position
     * is this library's gap, an {@link UnsupportedOperationException}: a data document is never linked, and
     * nothing yet judges the name it writes there.
     */
    public static Optional<AtomType<?>> forType(Top body, Normalization enumForm, boolean governsSchemas) {
        if (governsSchemas && body instanceof KindBody kind) {
            return Optional.of(KindParser.namesOnly(kind));
        }
        if (body instanceof RefBody reference) {
            if (!governsSchemas) {
                throw new UnsupportedOperationException("a value at a reference-typed position ('ref<"
                        + reference.of() + ">') in a data document is not yet read: what its name reaches is judged "
                        + "when a schema links, and a data document is not linked");
            }
            return Optional.of(new RefParser(reference));
        }
        return Optional.ofNullable(switch (body) {
            case ValueType ignored -> null;
            case VoidType ignored -> null;
            case IdentifierType t -> new IdentifierParser(t);
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
            case IriType t -> new IriParser(t);
            case RegexType t -> new RegexParser(t);
            case MacType t -> new MacParser(t);
            case EmailType t -> new EmailParser(t);
            case Cidr4Type t -> new Cidr4Parser(t);
            case Cidr6Type t -> new Cidr6Parser(t);
            case EnumBody t -> new EnumParser(t, enumForm);
            case KindBody t -> new KindParser(t);
            case ComplexType ignored -> ComplexParser.UNCONSTRAINED;
            case Ipv4Type t -> Ipv4Parser.of(t);
            case HostnameType t -> new HostnameParser(t);
            case HostType t -> new HostParser(t);
            case MediaTypeType t -> new MediaTypeParser(t);
            case Ipv6Type t -> Ipv6Parser.of(t);
            default -> null;
        });
    }
}
