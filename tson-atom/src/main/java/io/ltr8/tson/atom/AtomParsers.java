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
import io.ltr8.tson.atom.parser.TypeRefParser;
import io.ltr8.tson.atom.parser.ValueRefParser;
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
import io.ltr8.tson.schema.binary.BytesType;
import io.ltr8.tson.schema.net.Cidr4Type;
import io.ltr8.tson.schema.net.Cidr6Type;
import io.ltr8.tson.schema.numeric.ComplexType;
import io.ltr8.tson.schema.temporal.DateTimeType;
import io.ltr8.tson.schema.temporal.DateType;
import io.ltr8.tson.schema.numeric.DecimalType;
import io.ltr8.tson.schema.temporal.DurationType;
import io.ltr8.tson.schema.net.EmailType;
import io.ltr8.tson.schema.meta.EnumBody;
import io.ltr8.tson.schema.numeric.FloatType;
import io.ltr8.tson.schema.unicode.IdentifierType;
import io.ltr8.tson.schema.numeric.IntegerType;
import io.ltr8.tson.schema.meta.RefBody;
import io.ltr8.tson.schema.net.HostType;
import io.ltr8.tson.schema.net.HostnameType;
import io.ltr8.tson.schema.net.MediaTypeType;
import io.ltr8.tson.schema.net.Ipv4Type;
import io.ltr8.tson.schema.net.Ipv6Type;
import io.ltr8.tson.schema.net.MacType;
import io.ltr8.tson.schema.temporal.PeriodType;
import io.ltr8.tson.schema.numeric.RationalType;
import io.ltr8.tson.schema.unicode.RegexType;
import io.ltr8.tson.schema.unicode.TextType;
import io.ltr8.tson.schema.temporal.TimeType;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.net.IriType;
import io.ltr8.tson.schema.net.UriType;
import io.ltr8.tson.schema.net.UuidType;
import io.ltr8.tson.schema.meta.VoidType;
import java.util.Optional;

/**
 * A resolved atom body to the {@link AtomType} that parses tokens against it -- the answer to "does this
 * token satisfy this type?" for a caller that holds a schema-model body and nothing else.
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
     * <p><b>{@code void} yields nothing.</b> It is the type with no value, so no token is one.
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
     * <p>A reference that names values is this library's gap here, an {@link UnsupportedOperationException}: these
     * callers judge a value a schema writes (a template argument, a discriminator's pin) by its token before the
     * schema links, and such a reference's value is judged by what its name reaches, which only the schema's own link
     * can follow -- the linker judges a field's {@code ~}/{@code =} there itself.
     */
    public static Optional<AtomType<?>> forType(Top body, Normalization enumForm) {
        if (body instanceof RefBody reference && !reference.namesTypes()) {
            throw new UnsupportedOperationException("a value a schema writes at a reference-typed position ('meta.ref<"
                    + reference.of().text()
                    + ">') is not yet judged: a reference's value is judged by what its name reaches");
        }
        return forRead(body, enumForm);
    }

    /**
     * How a value read at a position typed by {@code body} reads in a schema document's value -- a constructor's or
     * a value entry's payload -- where that differs from a data document's ({@link #forRead}): a reference that names
     * types reads the type-name grammar alone ({@link TypeRefParser#namesOnly}), and one that names values its qualified
     * name alone ({@link ValueRefParser#namesOnly}). A name there resolves in the schema being resolved, which the
     * closure the reference was linked over does not hold, so the read records it and the schema's link judges it
     * ([TSON-SCHEMA] §4.2, §5.12).
     * Empty for every other body, which reads alike in both.
     */
    public static Optional<AtomType<?>> inSchemaDocument(Top body) {
        return switch (body) {
            case RefBody reference -> Optional.of(reference.namesTypes() ? TypeRefParser.namesOnly(reference)
                    : ValueRefParser.namesOnly(reference));
            default -> Optional.empty();
        };
    }

    /**
     * {@link #forType(Top, Normalization)} for a position a data document is read at. A reference that names types
     * judges membership in the closure its members were computed over, which is the reading document's ([TSON-SCHEMA]
     * §4.2). One that names values reads its qualified name and judges what it reaches in that closure ({@link
     * ValueRefParser}), as the linker judges a value body's names (§5.12).
     */
    public static Optional<AtomType<?>> forRead(Top body, Normalization enumForm) {
        if (body instanceof RefBody reference) {
            return Optional.of(reference.namesTypes() ? new TypeRefParser(reference) : new ValueRefParser(reference));
        }
        return Optional.ofNullable(switch (body) {
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
