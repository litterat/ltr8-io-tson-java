package io.ltr8.tson.tree;

import java.util.List;
import java.util.Optional;

/**
 * The result of navigating to something that isn't in the tree -- a query artifact, not a real value, so
 * {@link #get}/{@link #at} keep returning it and a deep chain never throws. Distinct from {@link TsonAbsent}
 * (the sentinel {@code _}/{@code null}), which is a position the document actually wrote.
 *
 * <p><b>It carries {@link #path()}, the RFC 6901 pointer of the step that failed</b>, so a chain that comes
 * back empty still says where it died: {@code at("/a/b/c")} over a tree with no {@code b} yields a missing
 * whose path is {@code "/a/b"}, not {@code "/a/b/c"}. That is the difference between "no {@code b}" and
 * "{@code b} had no {@code c}", which the node's mere existence can't express. Once navigation has failed the
 * path is fixed -- every further {@code get}/{@code at} returns this same node rather than extending it,
 * because the first failure is the informative one.
 *
 * <p>The pointer is <b>relative to the node navigation started from</b>, which is the only frame a node has:
 * a tree node doesn't know where it sits in its parent. So {@code root.at("/a/b")} reports {@code "/a/b"}
 * while {@code root.get("a").get("b")} reports {@code "/b"} -- each relative to its own receiver.
 *
 * <p>Every instance is produced by a navigation step, so there is no shared singleton and equality is by
 * path: two missings are equal exactly when they failed at the same place.
 */
public record TsonMissing(String path) implements TsonValue {

    public TsonMissing {
        if (path == null) {
            throw new IllegalArgumentException("a TsonMissing must carry the pointer at which navigation failed");
        }
    }

    /** A missing produced by stepping into the field/entry {@code name} of the receiver. */
    public static TsonMissing atField(String name) {
        return new TsonMissing("/" + escape(name));
    }

    /** A missing produced by stepping into element {@code index} of the receiver. */
    public static TsonMissing atIndex(int index) {
        return new TsonMissing("/" + index);
    }

    /** RFC 6901 §3 escaping: {@code ~} before {@code /}, so {@code ~1} round-trips as {@code ~01}. */
    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    @Override
    public Optional<String> typeRef() {
        return Optional.empty();
    }

    @Override
    public List<TsonAnnotation> annotations() {
        return List.of();
    }

    @Override
    public boolean isMissing() {
        return true;
    }

    @Override
    public Optional<String> missingPath() {
        return Optional.of(path);
    }

    @Override
    public TsonValue get(String name) {
        return this;
    }

    @Override
    public TsonValue get(int index) {
        return this;
    }
}
