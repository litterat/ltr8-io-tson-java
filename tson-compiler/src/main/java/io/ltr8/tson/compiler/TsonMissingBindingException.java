package io.ltr8.tson.compiler;

/**
 * A schema type nothing in the bind context resolves to a Java class.
 *
 * <p><b>A misconfiguration, not a gap</b>, which is the whole reason it exists as a type. It used to be an
 * {@code IllegalStateException} that the compile turned into an {@code ErrorReader} and the first read of
 * that type into "no usable compiled reader" -- a library-gap shape, for a caller who simply never mapped
 * the type. That reading travels: a downstream service mapped it to a 501, so a missing line of
 * configuration presented as "this library cannot do that".
 *
 * <p><b>Deferred, unlike its parent.</b> {@link TsonBindMismatchException} fails the compile because a class
 * that exists and disagrees would lose data on every document of that type; a type with no class at all is
 * different, because a schema legitimately declares types a given consumer never binds -- core.tn's forty,
 * the kernel's {@code data} base kind, every constructor a meta layer declares. Failing the compile for
 * those would make bind mode unusable, so this rides an {@code ErrorReader} to the first read of that
 * <em>specific</em> type and is thrown there, still saying what it is.
 */
public class TsonMissingBindingException extends TsonBindMismatchException {

    public TsonMissingBindingException(String message) {
        super(message);
    }
}
