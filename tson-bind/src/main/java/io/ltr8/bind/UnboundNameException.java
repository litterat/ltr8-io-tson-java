package io.ltr8.bind;

/**
 * A schema type name this context's {@link DataNameBinder} resolves to no class at all -- distinct from a name it
 * does resolve, to a class that cannot be analysed, which {@link DataBindContext#getDescriptor(String)} raises as
 * a plain {@link DataBindException} naming that class. The two are different mistakes: the first is a mapping
 * nobody wrote, the second a mapping to a class this binder cannot build.
 */
public class UnboundNameException extends DataBindException {

	private static final long serialVersionUID = 1L;

	public UnboundNameException(String message, Throwable cause) {
		super(message, cause);
	}
}
