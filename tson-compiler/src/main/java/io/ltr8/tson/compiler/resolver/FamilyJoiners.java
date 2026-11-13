package io.ltr8.tson.compiler.resolver;

import io.ltr8.tson.compiler.ast.schema.ApplicationRef;
import io.ltr8.tson.compiler.ast.schema.AtomRefinement;
import io.ltr8.tson.compiler.ast.schema.ConstructionDef;
import io.ltr8.tson.compiler.ast.schema.FieldDef;
import io.ltr8.tson.compiler.ast.schema.NameRef;
import io.ltr8.tson.compiler.ast.schema.RecordDef;
import io.ltr8.tson.compiler.ast.schema.RecordEntry;
import io.ltr8.tson.compiler.ast.schema.RefinedDef;
import io.ltr8.tson.compiler.ast.schema.SchemaMap;
import io.ltr8.tson.compiler.ast.schema.StructuralTypeDef;
import io.ltr8.tson.compiler.ast.schema.TypeExpr;
import io.ltr8.tson.compiler.ast.schema.TypeExprDef;
import io.ltr8.tson.schema.meta.Alias;
import io.ltr8.tson.schema.meta.FieldRole;
import io.ltr8.tson.schema.meta.RecordBody;
import io.ltr8.tson.schema.meta.RecordField;
import io.ltr8.tson.schema.meta.Top;
import io.ltr8.tson.schema.meta.TypeChain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * The declarations a read's readers do not hold that may be the member of a family ([TSON-SCHEMA] §5.2) a value's pins
 * select, judged from what each writes, never by resolving one ([TSON-SCHEMA] §3.4.1): its written operands (a
 * composition's, a refinement's source, an alias's target, an application's head) reach the family's base, an entry
 * IS-A it, or a declaration that may be a member; a subtraction joins nothing (§5.9). Its pin for a selector is the
 * nearest one written on that walk, its own first, since a restatement never changes a pin (§5.7), and a declaration
 * whose pin differs from the value's is none.
 */
final class FamilyJoiners {

    private FamilyJoiners() {
    }

    /** Each declaration {@code notHeld} admits that may be the member of {@code base} the value's pins select. */
    static List<String> of(String base, Set<String> selectors, BiPredicate<String, String> samePin,
                           Map<String, SchemaMap.Declaration> declarations, Map<String, Top> entries,
                           Predicate<String> notHeld) {
        Walk walk = new Walk(base, declarations, entries);
        List<String> joiners = new ArrayList<>();
        for (String name : declarations.keySet()) {
            // An open record is no type, so no member (§5.10); it is an operand of the applications that close it.
            if (notHeld.test(name) && SchemaDesugarer.typeParams(declarations.get(name).typeDef()).isEmpty()
                    && walk.mayJoin(name, new HashSet<>()) && selectors.stream().allMatch(selector ->
                    walk.pin(name, selector, new HashSet<>()).map(pin -> samePin.test(selector, pin)).orElse(true))) {
                joiners.add(name);
            }
        }
        return joiners;
    }

    private record Walk(String base, Map<String, SchemaMap.Declaration> declarations, Map<String, Top> entries) {

        /** Whether {@code name}, a declaration, may be IS-A {@code base} by what it writes. */
        boolean mayJoin(String name, Set<String> visiting) {
            if (!visiting.add(name)) {
                return false;
            }
            for (String operand : operands(declarations.get(name))) {
                if (isA(operand, visiting)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isA(String name, Set<String> visiting) {
            if (name.equals(base)) {
                return true;
            }
            Top ready = entries.get(name);
            if (ready != null) {
                return ready instanceof Alias alias ? isA(alias.target().name(), visiting)
                        : TypeChain.supertypesOf(ready).contains(base);
            }
            return declarations.containsKey(name) && mayJoin(name, visiting);
        }

        /** The nearest pin written for {@code selector} on {@code name}'s walk; empty where none is. */
        Optional<String> pin(String name, String selector, Set<String> visiting) {
            if (!visiting.add(name)) {
                return Optional.empty();
            }
            Top ready = entries.get(name);
            if (ready != null) {
                if (ready instanceof Alias alias) {
                    return pin(alias.target().name(), selector, visiting);
                }
                if (ready instanceof RecordBody record && record.fields().get(selector) instanceof RecordField field
                        && field.role() == FieldRole.FIXED && field.value().isPresent()) {
                    return Optional.of(field.value().get().text());
                }
                return Optional.empty();
            }
            SchemaMap.Declaration declaration = declarations.get(name);
            if (declaration == null) {
                return Optional.empty();
            }
            Optional<String> own = writtenPin(body(declaration), selector);
            if (own.isPresent()) {
                return own;
            }
            for (String operand : operands(declaration)) {
                Optional<String> inherited = pin(operand, selector, visiting);
                if (inherited.isPresent()) {
                    return inherited;
                }
            }
            return Optional.empty();
        }

        private static Optional<String> writtenPin(Optional<RecordDef> body, String selector) {
            for (RecordEntry entry : body.map(RecordDef::entries).orElse(List.of())) {
                if (entry instanceof FieldDef field && field.name().equals(selector) && field.modifier().isPresent()
                        && field.modifier().get().kind() == FieldDef.Modifier.Kind.FIXED
                        && field.modifier().get().value() instanceof FieldDef.Modifier.Value.Literal literal) {
                    return Optional.of(literal.token().text());
                }
            }
            return Optional.empty();
        }

        private static Optional<RecordDef> body(SchemaMap.Declaration declaration) {
            if (declaration.typeDef() instanceof StructuralTypeDef structural) {
                return switch (structural.body()) {
                    case ConstructionDef construction -> construction.body();
                    case RefinedDef refined -> Optional.of(refined.body());
                    case RecordDef record -> Optional.of(record);
                };
            }
            return Optional.empty();
        }

        /** The names a declaration writes as its parents, an application by its head. */
        private static List<String> operands(SchemaMap.Declaration declaration) {
            if (declaration == null) {
                return List.of();
            }
            return switch (declaration.typeDef()) {
                case StructuralTypeDef structural -> switch (structural.body()) {
                    case ConstructionDef construction -> construction.removal().isPresent() ? List.of()
                            : construction.supertypes().stream().flatMap(type -> named(type).stream()).toList();
                    case RefinedDef refined -> named(refined.target()).map(List::of).orElse(List.of());
                    case RecordDef ignored -> List.of();
                };
                case TypeExprDef alias -> named(alias.ref()).map(List::of).orElse(List.of());
                case AtomRefinement refinement -> List.of(refinement.target());
                default -> List.of();
            };
        }

        private static Optional<String> named(TypeExpr type) {
            return switch (type) {
                case NameRef name -> Optional.of(name.name());
                case ApplicationRef application -> Optional.of(application.name());
                default -> Optional.empty();
            };
        }
    }
}
