package io.ltr8.tson.base;

/**
 * A schema type with no Java class this bind context can build: none is mapped to its name, or the class that is
 * cannot be analysed (an interface that is no union, a collection with no constructor to build it). The message
 * says which.
 *
 * <p><b>A misconfiguration, not a gap</b>, which is the whole reason it exists as a type: reported as "no usable
 * compiled reader", a missing line of configuration would read as this library being unable to do the job, and
 * that reading travels -- a downstream service maps it to a 501.
 *
 * <p><b>Deferred, unlike its parent.</b> {@link BindMismatchException} fails the compile because a class that
 * exists and disagrees would lose data on every document of that type; a type with no class to build is
 * different, because a schema legitimately declares types a given consumer never binds -- core.tn's forty, the
 * kernel's {@code data} base kind, every constructor a meta layer declares. Failing the compile for those would
 * make bind mode unusable, so this rides an {@code ErrorReader} to the first read of that <em>specific</em> type
 * and is thrown there, still saying what it is.
 */
public class MissingBindingException extends BindMismatchException {

    public MissingBindingException(String message) {
        super(message);
    }

    /** The same, keeping the bind engine's own account of why nothing resolved. */
    public MissingBindingException(String message, Throwable cause) {
        super(message, cause);
    }
}
