package io.ltr8.tson.compiler;

import io.ltr8.tson.compiler.stream.AnnotationStart;
import io.ltr8.tson.compiler.stream.FieldName;
import io.ltr8.tson.compiler.stream.TokenEvent;
import io.ltr8.tson.compiler.stream.TsonEvent;
import io.ltr8.tson.compiler.stream.TsonEventSource;
import io.ltr8.tson.compiler.stream.TypeRef;

import java.util.Optional;

/**
 * Applies a read's {@link TsonUnicodePolicy} to every token as it leaves the stream
 * ([TSON-DATA] §8.2's "Values") -- the token-surface half of UTS #39 §5.2, where the name surface's half
 * runs in {@code TsonSchemaLinker} over declared names.
 *
 * <p><b>Why a decorator on the source rather than a check in the context.</b> {@code TsonReadContext}
 * rewinds: an event consumed during lookahead can be delivered a second time, and a probe context can be
 * built over events already seen ({@code AnnotationCapture}). Checking there would report one token twice.
 * The underlying stream produces each token exactly once, so wrapping it makes the check exactly-once for
 * free, with no set of already-reported positions to carry.
 *
 * <p><b>This is why a name is a token.</b> The four events carrying text are checked alike -- a value, a
 * field name, a type-ref, an annotation name -- because at this layer nothing yet knows which is which. So a
 * token policy stricter than the identifier policy subsumes it: the name has already cleared the stricter
 * rule by the time the name rule looks at it. That is a property of where the check sits, and the setter is
 * named {@code tokenPolicy} to state it rather than hide it.
 *
 * <p>Document directives are deliberately not checked. A {@code !!schema}/{@code !!id} token is a URI naming
 * an external resource rather than document content, [TSON-DATA] §2.2.1 governs what an identity may be, and
 * an IRI's scripts are the resource owner's business, not this document's.
 *
 * <p>At {@link TsonUnicodePolicy#unrestricted()} -- the default -- {@code checksScripts()} is false and
 * {@link TsonUnicodePolicy#violation} returns immediately, so an ordinary read pays a predicate per token and
 * nothing else. {@link #wrap} skips the decorator entirely in that case, so it pays not even that.
 */
final class TokenPolicyEventSource implements TsonEventSource {

    private final TsonEventSource delegate;
    private final TsonUnicodePolicy policy;
    private final TsonDiagnosticsReceiver receiver;

    private TokenPolicyEventSource(TsonEventSource delegate, TsonUnicodePolicy policy,
                                   TsonDiagnosticsReceiver receiver) {
        this.delegate = delegate;
        this.policy = policy;
        this.receiver = receiver;
    }

    /**
     * {@code source} itself when {@code policy} checks nothing, so the default costs a read no wrapper at
     * all; a checking decorator otherwise.
     */
    static TsonEventSource wrap(TsonEventSource source, TsonUnicodePolicy policy,
                                TsonDiagnosticsReceiver receiver) {
        return policy == null || !policy.checksScripts() ? source
                : new TokenPolicyEventSource(source, policy, receiver);
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public TsonEvent peek() {
        return delegate.peek();
    }

    /**
     * Checked on consumption rather than on {@link #peek()}: a peeked event may be looked at repeatedly, and
     * every event a read actually uses passes through here once.
     */
    @Override
    public TsonEvent next() {
        TsonEvent event = delegate.next();
        String text = switch (event) {
            case TokenEvent t -> t.text();
            case FieldName f -> f.name();
            case TypeRef t -> t.name();
            case AnnotationStart a -> a.name();
            default -> null;
        };
        if (text != null) {
            // isPresent/get rather than ifPresent: the lambda would capture text, event and receiver, so it
            // would allocate once per token on the read path. This runs for every token of every document.
            Optional<String> why = policy.violation(text);
            if (why.isPresent()) {
                receiver.report(Diagnostic.ofRestrictedToken(text, why.get(), event.position()));
            }
        }
        return event;
    }
}
