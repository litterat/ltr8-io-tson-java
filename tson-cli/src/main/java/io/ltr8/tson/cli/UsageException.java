package io.ltr8.tson.cli;

/**
 * The caller's command line is wrong -- a missing argument, an unknown flag value, too many operands.
 * {@link TsonCli#run} catches it, prints the message plus usage, and exits 2.
 *
 * <p><b>A distinct type, not {@code IllegalArgumentException}</b>, because those two failures deserve
 * opposite treatment and are otherwise indistinguishable at the top of {@code run}. An {@code
 * IllegalArgumentException} arriving from inside a command is a fault in the library, not a mistake the
 * user made: catching it as usage would print "usage: tson validate ..." at someone whose command line was
 * fine, and exit 2 as though they could fix it by retyping. Only what this CLI's own argument parsing
 * throws is a usage error; everything else reaches {@code run}'s fault handler with its stack trace intact.
 */
final class UsageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
