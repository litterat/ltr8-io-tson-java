package io.ltr8.tson.compiler.stream;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * A fixed, in-memory {@link TsonEventSource} replaying a pre-built list of events in order --
 * for a synthetic or already-fully-decoded value with no real lazy stream behind it (e.g. a
 * schema-composed literal default, or an already-parsed value tree being replayed through a
 * compiled reader). Every position carries its own {@link io.ltr8.tson.compiler.Position}, same as a real {@link
 * io.ltr8.tson.compiler.TsonDataStream}-backed source -- a caller synthesizing events with no
 * genuine source position to report is free to use a placeholder one, since nothing here
 * requires it to be meaningful.
 */
public final class ListEventSource implements TsonEventSource {

    private final List<TsonEvent> events;
    private int index = 0;

    public ListEventSource(List<TsonEvent> events) {
        this.events = List.copyOf(events);
    }

    @Override
    public boolean hasNext() {
        return index < events.size();
    }

    @Override
    public TsonEvent next() {
        if (!hasNext()) {
            throw new NoSuchElementException("no more TSON stream events");
        }
        return events.get(index++);
    }

    @Override
    public TsonEvent peek() {
        if (!hasNext()) {
            throw new NoSuchElementException("no more TSON stream events");
        }
        return events.get(index);
    }
}
