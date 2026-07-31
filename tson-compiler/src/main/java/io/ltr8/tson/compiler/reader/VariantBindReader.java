package io.ltr8.tson.compiler.reader;

import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.tson.compiler.Diagnostic;
import io.ltr8.tson.compiler.TsonReadContext;
import io.ltr8.tson.compiler.TsonValueReader;
import io.ltr8.tson.compiler.TsonValueReaderResolver;

import java.util.Optional;

/**
 * Object-binding mode's own record-subtype/union dispatcher -- the {@code DataClassUnion}-bounded
 * counterpart to {@code reader.VariantParser}. That class dispatches by {@code !typeName} over
 * {@code TypeDefinition.subtypes()} -- purely schema-derived, with each subtype's own target Java
 * class found by convention via {@code DataNameBinder} once dispatch reaches {@code resolver}. This
 * class exists for the same reason {@link RecordBindReader} exists alongside {@code
 * reader.RecordParser}: a caller with an *independently designed* Java sealed interface (not
 * necessarily named/structured the way the schema-driven binder convention would derive it) needs
 * dispatch bounded by that real Java type, the same way {@link RecordBindReader} takes a real {@code
 * DataClassRecord} instead of deriving one by convention.
 *
 * <p><b>Record-scoped, deliberately, for now.</b> {@code TypeDefinition.subtypes()} is non-empty for
 * any composed kind with real subtypes, not just records ({@code reader.VariantParser} handles
 * array's own {@code set}/{@code array_min}/{@code array_max}/{@code array_ranged} too) -- but those
 * aren't meant to be dispatched to by an explicit {@code !array_min [...]} type-ref the way a record
 * subtype is; whether/how the spec should even allow that is still open. Only {@link
 * RecordBindReader.Factory} constructs this class, and only for a record-shaped declaration with
 * real subtypes; {@link ArrayBindReader}/{@link MapBindReader}/{@link TupleBindReader} always read
 * as themselves, no dispatch layer at all.
 *
 * <p><b>Preserves {@code reader.VariantParser}'s own "own body is a valid reading too" rule</b> --
 * a value with no type-ref, or one naming this declaration itself, reads via {@code ownParser}
 * unconditionally; only a value naming something else dispatches at all. Whether {@code ownParser}
 * is ever actually reachable depends on what {@code name} binds to: {@link RecordBindReader.Factory}
 * supplies a real {@link RecordBindReader} when the schema declaration has its own real fields *and*
 * a real bound Java record exists for it, or a reader that unconditionally throws when {@code name}
 * binds to a pure marker interface with no data of its own (e.g. {@code top}/{@code atom} -- see
 * that factory's own Javadoc).
 *
 * <p><b>Membership is checked against {@code descriptor.memberTypes()} fresh on every read, not
 * precomputed at construction</b> -- unlike almost everything else in this package. {@link
 * DataClassUnion} explicitly supports a non-sealed union growing at runtime ({@code
 * addMemberType}, e.g. as more implementations get classloaded); a construction-time snapshot would
 * silently go stale for exactly that case. A union's own member count is small (a handful of
 * variants, not hundreds of record fields), so the linear scan this trades for isn't the kind of
 * per-read cost the rest of this package was built to avoid.
 *
 * <p>Matches {@code TsonObjectReader.resolveUnionMember}'s own two-pass precedence exactly (an exact
 * {@link Typename} match first, then a case-insensitive simple-class-name match for an un-annotated
 * member) -- except this only needs to confirm membership, not resolve to a {@code Class}: once a
 * type-ref is confirmed a real member, the actual read dispatches by schema name through {@code
 * resolver}, the same compiled-schema path every other dispatch in this codebase uses, not by
 * reflectively constructing the member class directly the way {@code TsonObjectReader} does.
 *
 * <p>The type-ref driving this decision is consumed here, via {@link EventSkip#annotationsAndTypeRef}
 * -- {@code ownParser} still calls it again on delegation (every reader does, as its own first step),
 * which is a safe no-op once nothing's left to consume.
 */
final class VariantBindReader implements TsonValueReader<Object> {

    private final String name;
    private final TsonValueReader<?> ownParser;
    private final DataClassUnion descriptor;
    private final TsonValueReaderResolver resolver;

    VariantBindReader(String name, TsonValueReader<?> ownParser, DataClassUnion descriptor,
                       TsonValueReaderResolver resolver) {
        this.name = name;
        this.ownParser = ownParser;
        this.descriptor = descriptor;
        this.resolver = resolver;
    }

    @Override
    public Object read(TsonReadContext ctx) {
        Optional<String> typeRef = EventSkip.annotationsAndTypeRef(ctx);
        if (typeRef.isEmpty() || typeRef.get().equals(name)) {
            return ownParser.read(ctx);
        }
        String ref = typeRef.get();
        if (!isMember(ref)) {
            ctx.report(Diagnostic.Code.UNKNOWN_TYPE_REF, "'" + ref + "' is not a member of the union '" + name
                            + "' binds against " + describeMembers(),
                    "one of " + describeMembers(), ref);
            EventSkip.coreValue(ctx);
            return null;
        }
        return resolver.resolve(ref).read(ctx);
    }

    private boolean isMember(String typeRef) {
        for (Class<?> member : descriptor.memberTypes()) {
            Typename tn = member.getAnnotation(Typename.class);
            if (tn != null && tn.name().equals(typeRef)) {
                return true;
            }
        }
        for (Class<?> member : descriptor.memberTypes()) {
            if (member.getAnnotation(Typename.class) == null && member.getSimpleName().equalsIgnoreCase(typeRef)) {
                return true;
            }
        }
        return false;
    }

    private String describeMembers() {
        Class<?>[] members = descriptor.memberTypes();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(members[i].getSimpleName());
        }
        return sb.append(']').toString();
    }
}
