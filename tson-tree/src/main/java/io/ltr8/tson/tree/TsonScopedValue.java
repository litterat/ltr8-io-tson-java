package io.ltr8.tson.tree;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A value read under a schema scope the document pushed onto it -- a nested {@code !!schema} directive and
 * the value it governs ([TSON-DATA] §2.3, [TSON-SCHEMA] §7.8).
 *
 * <p><b>A wrapper, because the directive belongs to the position and not to the value.</b> {@code
 * scoped-value = [ schema-directive ws ] data-value} is the grammar's own shape: the same record means the
 * same thing whether or not a directive precedes it, so the directive attaches around the value rather than
 * as an eighth component on each of the seven node types. That is the same argument {@link TsonDocument}
 * makes at document level, which is why the two are separate types rather than one -- a document also
 * carries {@code !!id}, is not itself a value, and cannot appear at a field position.
 *
 * <p><b>Transparent to navigation.</b> Every kind predicate, accessor and navigation step delegates to
 * {@link #root()}, so {@code tree.at("/attachments/0/claim_id")} reads the same whether the element pushed a
 * scope or not, and a consumer that does not care about scopes never has to unwrap. One asks for the scope
 * by asking for it -- {@code v instanceof TsonScopedValue s} then {@code s.schema()}.
 *
 * <p><b>Only a genuine scope push produces one.</b> A value whose type came from the governing namespace
 * carries no directive and is read as its own node; the wrapper exists to preserve what the document wrote,
 * so a tree round-trips through {@code TsonTreeWriter} with its directives where the author put them.
 *
 * @param schema the schema bound over {@link #root()} -- the {@code !!schema} directive's own URI, as written
 * @param root   the value that directive governs, read against the schema it names
 */
public record TsonScopedValue(String schema, TsonValue root) implements TsonValue {

    public TsonScopedValue {
        if (schema == null || schema.isEmpty()) {
            throw new IllegalArgumentException("schema -- a scoped value exists because a !!schema named one");
        }
        if (root == null) {
            throw new IllegalArgumentException("root -- the value the directive governs");
        }
    }

    // --- the scope's own two facts, then everything else through to the value it governs ---

    @Override
    public Optional<String> typeRef() {
        return root.typeRef();
    }

    @Override
    public List<TsonAnnotation> annotations() {
        return root.annotations();
    }

    @Override public boolean isRecord()    { return root.isRecord(); }
    @Override public boolean isMap()       { return root.isMap(); }
    @Override public boolean isArray()     { return root.isArray(); }
    @Override public boolean isTuple()     { return root.isTuple(); }
    @Override public boolean isAtom()      { return root.isAtom(); }
    @Override public boolean isAbsent()    { return root.isAbsent(); }
    @Override public boolean isMissing()   { return root.isMissing(); }
    @Override public boolean isContainer() { return root.isContainer(); }

    @Override public Optional<String> missingPath()      { return root.missingPath(); }
    @Override public TsonValue get(String name)          { return root.get(name); }
    @Override public TsonValue get(int index)            { return root.get(index); }
    @Override public Map<String, TsonValue> fields()     { return root.fields(); }
    @Override public List<TsonValue> elements()          { return root.elements(); }
    @Override public <T> Optional<T> as(Class<T> type)   { return root.as(type); }
}
