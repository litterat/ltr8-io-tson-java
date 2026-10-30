package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.Annotation;
import io.ltr8.tson.compiler.ast.ArrayValue;
import io.ltr8.tson.compiler.ast.CoreValue;
import io.ltr8.tson.compiler.ast.DataValue;
import io.ltr8.tson.compiler.ast.Document;
import io.ltr8.tson.compiler.ast.EmptyBrace;
import io.ltr8.tson.compiler.ast.MapValue;
import io.ltr8.tson.compiler.ast.RecordValue;
import io.ltr8.tson.compiler.ast.TokenValue;
import io.ltr8.tson.compiler.ast.VoidValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The wait graph of a resolved schema document: which entries must be ready before each entry's declaration can be
 * read, under the resolution-order rule proposed in {@code SPEC-FEEDBACK.md #4}.
 *
 * <p><b>The rule.</b> Reading a value against a type {@code T} waits on {@code T} being ready, and on each type a
 * <i>written</i> part of the value is read against as the read reaches it: a field's declared type, an array or set
 * element's, a map's key and value types, a nested {@code !X} tag's, an annotation's. An entry is ready when its
 * declaration (its value) has been read, so an entry {@code name => !C { … }} waits on {@code C} and on everything
 * reading its body reaches. An omitted field is not reached. Everything else is a consequence:
 *
 * <ul>
 *   <li>An alias ({@code !reference { target: X }}) is ready when its target is: an edge alias → {@code X}, beside
 *       what reading its own declaration reaches.</li>
 *   <li><b>A reference orders nothing.</b> A token at a slot typed by a kind ({@code type_kind},
 *       {@code identifier_kind}, any {@code !kind_type} instance), a {@code !ref_type} instance or {@code type_ref}
 *       waits on the slot's own type and what reading that reaches, never on the entry the name names. This falls
 *       out of the walk: a kind is an atom, so reading against one reaches nothing further.</li>
 *   <li><b>A held value orders nothing.</b> A slot typed {@code value} waits on {@code value} only, and a template's
 *       body text on {@code text} only -- again by the walk, both being atoms.</li>
 *   <li>A tagged value read at a position typed {@code P} waits on {@code P} and on its tag. An untagged value at a
 *       choice, or at an abstract record (a dispatch over its {@code subtypes}), waits on the one variant its form
 *       admits; where the form admits more than one, it waits on all of them, and {@link #overapproximations()}
 *       records the case.</li>
 * </ul>
 *
 * <p><b>Self-describing.</b> Every field, element and key type comes from the document's own declarations: a
 * type's form is read off its constructor's declaration ({@code record}, {@code array} and what composes it,
 * {@code map}, {@code namespace}, {@code tuple}, {@code choice}, {@code declared_type}, anything composing {@code
 * atom}), a record's fields off its flattened {@code fields} map, a namespace type's key and value types off its own
 * {@code key_type} and {@code value_type}. Nothing compiled is consulted, which is what keeps a measurement of the
 * kernel from assuming it.
 *
 * <p><b>Where the rule as written is silent</b>, this implements: an entry's value is read against its head tag,
 * not against the enclosing schema's {@code value_type} (the document read waits on that, see
 * {@link #documentWaits()}); a schema-map key's annotations ({@code @synthetic}) belong to the schema map, so
 * they too are waits of the document read and not of the entry they mark; a bare token at a record position is
 * that record's shorthand for its one required field ({@code type_ref}'s {@code name}); a namespace type stating no
 * {@code key_type} has its keys read by their spelling, as {@code text}, the host judging them later.
 */
final class WaitGraph {

    /** One wait: the entry waited on, the edge rule that produced it, and where in the declaration it was reached. */
    record Wait(String target, String rule, String path) {

        @Override
        public String toString() {
            return target + " [" + rule + (path.isEmpty() ? "" : " at " + path) + "]";
        }
    }

    /** An untagged value at a dispatch position whose form admits several variants: it waits on all of them. */
    record Overapproximation(String entry, String path, String type, List<String> variants) {
    }

    private enum Form { RECORD, ABSTRACT, NAMESPACE, ARRAY, MAP, TUPLE, CHOICE, DECLARED, ATOM, TEMPLATE }

    private record Shape(String terminal, DataValue declaration, Form form) {
    }

    private final Map<String, DataValue> declarations = new LinkedHashMap<>();
    private final Map<String, List<Annotation>> keyAnnotations = new LinkedHashMap<>();
    private final Map<String, Map<String, Wait>> waits = new LinkedHashMap<>();
    private final Map<String, Wait> documentWaits = new LinkedHashMap<>();
    private final List<Overapproximation> overapproximations = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();

    private WaitGraph() {
    }

    /** The wait graph of {@code document}, whose root is a schema map ({@code !schema { name => value … }}). */
    static WaitGraph of(Document document) {
        WaitGraph graph = new WaitGraph();
        DataValue root = document.root();
        if (!(root.coreValue() instanceof MapValue map)) {
            throw new IllegalArgumentException("the document root is not a schema map: " + root.coreValue());
        }
        for (MapValue.MapEntry entry : map.entries()) {
            String name = text(entry.key().coreValue());
            graph.declarations.put(name, entry.value());
            graph.keyAnnotations.put(name, entry.key().annotations());
        }
        for (Map.Entry<String, DataValue> entry : graph.declarations.entrySet()) {
            graph.readEntry(entry.getKey(), entry.getValue());
        }
        graph.readDocument(root);
        return graph;
    }

    /** Every entry, in document order. */
    Set<String> entries() {
        return declarations.keySet();
    }

    /** The head constructor of {@code entry}'s declaration, the {@code C} in {@code !C { … }}. */
    String head(String entry) {
        return declarations.get(entry).typeRef().orElse("?");
    }

    /** What {@code entry} waits on, each target once, at the first place the read reaches it. */
    Map<String, Wait> waits(String entry) {
        return waits.get(entry);
    }

    /** What reading the document itself waits on: its root tag, its annotations, its keys' and values' types. */
    Map<String, Wait> documentWaits() {
        return documentWaits;
    }

    List<Overapproximation> overapproximations() {
        return overapproximations;
    }

    /** Anything the walk could not decide from the document: a name with no entry, a value of the wrong form. */
    List<String> problems() {
        return problems;
    }

    /** The strongly connected components, each emitted after every component it waits on (dependencies first). */
    List<Set<String>> components() {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();
        for (String entry : declarations.keySet()) {
            if (!index.containsKey(entry)) {
                strongConnect(entry, index, low, stack, onStack, components);
            }
        }
        return components;
    }

    /** A component that is a cycle: more than one entry, or one that waits on itself. */
    boolean isCycle(Set<String> component) {
        if (component.size() > 1) {
            return true;
        }
        String only = component.iterator().next();
        return waits.get(only).containsKey(only);
    }

    private void strongConnect(String entry, Map<String, Integer> index, Map<String, Integer> low,
                               Deque<String> stack, Set<String> onStack, List<Set<String>> components) {
        index.put(entry, index.size());
        low.put(entry, index.get(entry));
        stack.push(entry);
        onStack.add(entry);
        for (String target : waits.get(entry).keySet()) {
            if (!declarations.containsKey(target)) {
                continue;
            }
            if (!index.containsKey(target)) {
                strongConnect(target, index, low, stack, onStack, components);
                low.put(entry, Math.min(low.get(entry), low.get(target)));
            } else if (onStack.contains(target)) {
                low.put(entry, Math.min(low.get(entry), index.get(target)));
            }
        }
        if (low.get(entry).equals(index.get(entry))) {
            Set<String> component = new TreeSet<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(entry));
            components.add(component);
        }
    }

    // ── Reading ──────────────────────────────────────────────────────────

    private void readEntry(String name, DataValue declaration) {
        Read read = new Read(name);
        waits.put(name, read.out);
        Optional<String> head = declaration.typeRef();
        if (head.isEmpty()) {
            problems.add(name + ": an untagged declaration, so no head to read it against");
            return;
        }
        read.read(declaration, head.get(), "head", "");
        if (head.get().equals("reference")) {
            targetName(declaration).ifPresentOrElse(
                    target -> read.waitOn(target, "alias target", "target"),
                    () -> problems.add(name + ": an alias with no target name"));
        }
    }

    private void readDocument(DataValue root) {
        Read read = new Read("<document>");
        String rootType = root.typeRef().orElse("schema");
        read.waitOn(rootType, "head", "");
        read.annotations(root.annotations(), "");
        Shape shape = shape(rootType, read, "");
        if (shape == null || shape.form() != Form.NAMESPACE) {
            problems.add("<document>: the root type " + rootType + " is not a namespace");
        } else {
            namespaceType(shape, "key_type", read, "").ifPresent(t -> read.waitOn(t, "key", "keys"));
            namespaceType(shape, "value_type", read, "").ifPresent(t -> read.waitOn(t, "value", "values"));
        }
        keyAnnotations.forEach((name, annotations) -> read.annotations(annotations, name));
        documentWaits.putAll(read.out);
    }

    /** One entry's read, collecting what it waits on. */
    private final class Read {

        final String entry;
        final Map<String, Wait> out = new LinkedHashMap<>();

        Read(String entry) {
            this.entry = entry;
        }

        void waitOn(String target, String rule, String path) {
            if (!declarations.containsKey(target)) {
                problems.add(entry + ": waits on " + target + ", which no entry declares (" + rule + " at " + path + ")");
            }
            out.putIfAbsent(target, new Wait(target, rule, path));
        }

        void annotations(List<Annotation> annotations, String path) {
            for (Annotation annotation : annotations) {
                String at = path + "@" + annotation.name();
                waitOn(annotation.name(), "annotation", at);
                annotation.value().ifPresent(value -> read(value, annotation.name(), "annotation value", at));
            }
        }

        void read(DataValue value, String type, String rule, String path) {
            waitOn(type, rule, path);
            annotations(value.annotations(), path);
            String readAs = type;
            if (value.typeRef().isPresent() && !value.typeRef().get().equals(type)) {
                readAs = value.typeRef().get();
                waitOn(readAs, "tag", path + "!" + readAs);
            }
            structure(value.coreValue(), readAs, path);
        }

        private void structure(CoreValue core, String type, String path) {
            Shape shape = shape(type, this, path);
            if (shape == null) {
                return;
            }
            switch (shape.form()) {
                case RECORD -> record(core, shape, path);
                case NAMESPACE -> {
                    if (core instanceof MapValue map) {
                        Optional<String> keyType = namespaceType(shape, "key_type", this, path);
                        Optional<String> valueType = namespaceType(shape, "value_type", this, path);
                        for (MapValue.MapEntry entry : map.entries()) {
                            String key = text(entry.key().coreValue());
                            keyType.ifPresent(t -> read(entry.key(), t, "key", path + "{" + key + "}"));
                            valueType.ifPresent(t -> read(entry.value(), t, "value", path + "." + key));
                        }
                    } else if (!(core instanceof EmptyBrace)) {
                        problems.add(entry + ": a " + kind(core) + " at namespace " + type + " (" + path + ")");
                    }
                }
                case ARRAY -> {
                    String element = token(field(shape.declaration(), "element_type"));
                    if (core instanceof ArrayValue array) {
                        for (int i = 0; i < array.elements().size(); i++) {
                            read(array.elements().get(i), element, "element", path + "[" + i + "]");
                        }
                    } else if (!(core instanceof EmptyBrace)) {
                        problems.add(entry + ": a " + kind(core) + " at array " + type + " (" + path + ")");
                    }
                }
                case MAP -> {
                    String keyType = token(field(shape.declaration(), "key_type"));
                    String valueType = token(field(shape.declaration(), "value_type"));
                    if (core instanceof MapValue map) {
                        for (MapValue.MapEntry entry : map.entries()) {
                            String key = text(entry.key().coreValue());
                            read(entry.key(), keyType, "key", path + "{" + key + "}");
                            read(entry.value(), valueType, "value", path + "." + key);
                        }
                    } else if (!(core instanceof EmptyBrace)) {
                        problems.add(entry + ": a " + kind(core) + " at map " + type + " (" + path + ")");
                    }
                }
                case TUPLE -> {
                    List<DataValue> elements = elements(field(shape.declaration(), "elements"));
                    if (core instanceof ArrayValue array && array.elements().size() <= elements.size()) {
                        for (int i = 0; i < array.elements().size(); i++) {
                            String element = token(field(elements.get(i), "element_type"));
                            read(array.elements().get(i), element, "element", path + "[" + i + "]");
                        }
                    } else {
                        problems.add(entry + ": a " + kind(core) + " at tuple " + type + " (" + path + ")");
                    }
                }
                case CHOICE -> dispatch(core, type, tokens(field(shape.declaration(), "variants")), path);
                // A tagged value is read against its tag (`read`), so what reaches here is untagged, and names no type.
                case DECLARED -> problems.add(entry + ": an untagged value at the declared " + type + " (" + path + ")");
                case ABSTRACT -> dispatch(core, type, subtypes(shape.declaration()), path);
                case ATOM -> {
                    if (!(core instanceof TokenValue) && !(core instanceof VoidValue)) {
                        problems.add(entry + ": a " + kind(core) + " at atom " + type + " (" + path + ")");
                    }
                }
                case TEMPLATE -> problems.add(entry + ": a value read against the open template " + type
                        + " (" + path + ")");
            }
        }

        private void record(CoreValue core, Shape shape, String path) {
            Map<String, DataValue> fields = recordFields(shape.declaration());
            switch (core) {
                case RecordValue record -> {
                    for (RecordValue.Field field : record.fields()) {
                        DataValue declared = fields.get(field.name());
                        if (declared == null) {
                            problems.add(entry + ": " + shape.terminal() + " declares no field " + field.name()
                                    + " (" + path + ")");
                            continue;
                        }
                        read(field.value(), token(field(declared, "type")), "field",
                                path + "." + field.name());
                    }
                }
                case EmptyBrace ignored -> {
                }
                case TokenValue token -> {
                    List<String> required = fields.entrySet().stream()
                            .filter(f -> !"true".equals(token(field(f.getValue(), "optional"))))
                            .map(Map.Entry::getKey)
                            .toList();
                    if (required.size() != 1) {
                        problems.add(entry + ": a token at record " + shape.terminal() + ", which has "
                                + required.size() + " required fields (" + path + ")");
                        return;
                    }
                    String name = required.getFirst();
                    read(new DataValue(List.of(), Optional.empty(), token), token(field(fields.get(name), "type")),
                            "record shorthand", path + "(." + name + ")");
                }
                default -> problems.add(entry + ": a " + kind(core) + " at record " + shape.terminal()
                        + " (" + path + ")");
            }
        }

        private void dispatch(CoreValue core, String type, List<String> variants, String path) {
            List<String> admitting = new ArrayList<>();
            for (String variant : variants) {
                Shape shape = shape(variant, null, path);
                if (shape != null && admits(shape.form(), core)) {
                    admitting.add(variant);
                }
            }
            if (admitting.size() == 1) {
                read(new DataValue(List.of(), Optional.empty(), core), admitting.getFirst(), "untagged dispatch",
                        path);
                return;
            }
            overapproximations.add(new Overapproximation(entry, path, type, List.copyOf(admitting)));
            for (String variant : admitting) {
                waitOn(variant, "untagged dispatch, undecided", path);
            }
        }
    }

    private static boolean admits(Form form, CoreValue core) {
        return switch (form) {
            case ATOM -> core instanceof TokenValue || core instanceof VoidValue;
            case RECORD -> core instanceof RecordValue || core instanceof EmptyBrace;
            case NAMESPACE, MAP -> core instanceof MapValue || core instanceof EmptyBrace;
            case ARRAY, TUPLE -> core instanceof ArrayValue;
            case CHOICE, ABSTRACT, DECLARED -> true;
            case TEMPLATE -> false;
        };
    }

    // ── A type's form, from the document's own declarations ─────────────

    /** {@code type}'s terminal declaration, through any aliases, and the form its constructor reads. */
    private Shape shape(String type, Read read, String path) {
        String terminal = terminal(type);
        if (terminal == null) {
            if (read != null) {
                problems.add(read.entry + ": " + type + " has no terminal declaration (" + path + ")");
            }
            return null;
        }
        DataValue declaration = declarations.get(terminal);
        String constructor = terminal(declaration.typeRef().orElse(""));
        if (constructor == null) {
            problems.add(terminal + ": its head " + declaration.typeRef() + " has no declaration");
            return null;
        }
        Set<String> constructorSupertypes = new HashSet<>(supertypes(declarations.get(constructor)));
        Form form;
        if (constructor.equals("record")) {
            if ("ABSTRACT".equals(token(field(declaration, "extension")))) {
                form = Form.ABSTRACT;
            } else {
                form = Form.RECORD;
            }
        } else if (constructor.equals("array") || constructorSupertypes.contains("array")) {
            form = Form.ARRAY;
        } else if (constructor.equals("map")) {
            form = Form.MAP;
        } else if (constructor.equals("namespace")) {
            form = Form.NAMESPACE;
        } else if (constructor.equals("declared_type")) {
            form = Form.DECLARED;
        } else if (constructor.equals("tuple")) {
            form = Form.TUPLE;
        } else if (constructor.equals("choice")) {
            form = Form.CHOICE;
        } else if (constructor.equals("template")) {
            form = Form.TEMPLATE;
        } else if (constructorSupertypes.contains("atom")) {
            form = Form.ATOM;
        } else {
            problems.add(terminal + ": no form for its constructor " + constructor);
            return null;
        }
        return new Shape(terminal, declaration, form);
    }

    /** {@code type} with every alias hop followed, or {@code null} when a hop names no entry or the hops loop. */
    private String terminal(String type) {
        Set<String> seen = new HashSet<>();
        String current = type;
        while (current != null && seen.add(current)) {
            DataValue declaration = declarations.get(current);
            if (declaration == null) {
                return null;
            }
            if (!"reference".equals(declaration.typeRef().orElse(""))) {
                return current;
            }
            current = targetName(declaration).orElse(null);
        }
        return null;
    }

    /**
     * A namespace type's key or value type, as its declaration states it; a key type it leaves to the host is read
     * by spelling, as {@code text}.
     */
    private Optional<String> namespaceType(Shape shape, String slot, Read read, String path) {
        String type = token(field(shape.declaration(), slot));
        if (type == null && slot.equals("key_type")) {
            return Optional.of("text");
        }
        if (type == null) {
            problems.add(read.entry + ": namespace " + shape.terminal() + " carries no " + slot + " (" + path + ")");
        }
        return Optional.ofNullable(type);
    }

    private static Optional<String> targetName(DataValue alias) {
        DataValue target = field(alias, "target");
        if (target == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(target.coreValue() instanceof RecordValue ? token(field(target, "name"))
                : token(target));
    }

    private static Map<String, DataValue> recordFields(DataValue declaration) {
        Map<String, DataValue> fields = new LinkedHashMap<>();
        DataValue map = field(declaration, "fields");
        if (map != null && map.coreValue() instanceof MapValue entries) {
            for (MapValue.MapEntry entry : entries.entries()) {
                fields.put(text(entry.key().coreValue()), entry.value());
            }
        }
        return fields;
    }

    private static List<String> supertypes(DataValue declaration) {
        return declaration == null ? List.of() : tokens(field(field(declaration, "chain"), "supertypes"));
    }

    private static List<String> subtypes(DataValue declaration) {
        return tokens(field(field(declaration, "chain"), "subtypes"));
    }

    // ── AST access ───────────────────────────────────────────────────────

    private static DataValue field(DataValue value, String name) {
        if (value != null && value.coreValue() instanceof RecordValue record) {
            for (RecordValue.Field field : record.fields()) {
                if (field.name().equals(name)) {
                    return field.value();
                }
            }
        }
        return null;
    }

    private static List<DataValue> elements(DataValue value) {
        if (value != null && value.coreValue() instanceof ArrayValue array) {
            return array.elements().stream().map(d -> d).toList();
        }
        return List.of();
    }

    private static List<String> tokens(DataValue value) {
        return elements(value).stream().map(WaitGraph::token).toList();
    }

    private static String token(DataValue value) {
        return value != null && value.coreValue() instanceof TokenValue token ? token.text() : null;
    }

    private static String text(CoreValue value) {
        return value instanceof TokenValue token ? token.text() : String.valueOf(value);
    }

    private static String kind(CoreValue value) {
        return value.getClass().getSimpleName();
    }
}
