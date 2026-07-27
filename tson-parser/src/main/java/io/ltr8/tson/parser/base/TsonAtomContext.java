package io.ltr8.tson.parser.base;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.UUID;

/**
 * The default built-in-vocabulary atom registrations every {@link DataBindContext} consumer in
 * this library needs -- pulled out from {@code io.ltr8.tson.parser.mapper.TsonMapperContext} (its
 * original, sole home) once a third consumer (an object-binding
 * {@code io.ltr8.tson.parser.bind.ObjectRecordShapeFactory}, in a different package
 * with no dependency on {@code mapper}) needed the identical list -- see that class's own,
 * unchanged Javadoc for why each individual registration exists (UUID/byte[]/LocalDate/
 * OffsetTime/OffsetDateTime/URI/Inet4Address/Inet6Address); none of that reasoning changed by
 * moving the code, only its address. Lives at this layer (alongside {@link NumberNarrowing},
 * `base`'s own "shared behind more than one downstream consumer" spot) rather than in
 * `compiler` itself, since `mapper` needs it too and `mapper` doesn't depend on `compiler`.
 */
public final class TsonAtomContext {

    private TsonAtomContext() {
    }

    public static DataBindContext defaultContext() {
        DataBindContext context = DataBindContext.builder().build();
        try {
            context.registerAtom(UUID.class);
            context.registerAtom(byte[].class);
            context.registerAtom(LocalDate.class);
            context.registerAtom(OffsetTime.class);
            context.registerAtom(OffsetDateTime.class);
            context.registerAtom(URI.class);
            context.registerAtom(Inet4Address.class);
            context.registerAtom(Inet6Address.class);
        } catch (DataBindException e) {
            throw new IllegalStateException("failed to register default atom types on a fresh DataBindContext", e);
        }
        return context;
    }
}
