/*
 * Copyright (c) 2026, Litterat Pty Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.ltr8.bind.analysis;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.analysis.ComponentInfo.Role;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.IncrementInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Finds the components of a hand-written {@code @Record} class from the bytecode of the constructor that binds it,
 * since reflection gives neither a parameter's name nor the field it fills.
 *
 * <p>A parameter binds when the constructor stores it unchanged in exactly one field of the instance and never writes
 * its local slot. A {@code super(…)} or {@code this(…)} call passing only the constructor's own parameters, unchanged,
 * is followed into the constructor it calls, which must store each of them the same way; a super class constructor
 * so called must be public. A parameter the analysis cannot follow, computed or passed up beside any other argument,
 * is named with {@link Field @Field}.
 *
 * <p>A component is read back through a public instance method of the class or a super class that returns its field,
 * or a public method of its name carrying {@code @Field} or {@link Union @Union}; failing both, through the field
 * itself where that is public. A member a super class that is not public declares is reached as the JVM
 * reaches it, through a public subclass: the described class or one between the two.
 *
 * <p>The class is refused, with a {@link CodeAnalysisException} naming it, when a parameter binds no component or more
 * than one, when a bound component has no such read, when its read is reached through no public class or its field's
 * name reaches another field from each, or when two components take one name.
 */
public class ImmutableFinder implements ComponentFinder {

    public ImmutableFinder() {
    }

    @Override
    public void findComponents(Class<?> clss, Map<TypeVariable<?>, Type> bindings, Constructor<?> constructor,
            List<ComponentInfo> fields) throws CodeAnalysisException {

        try {
            ClassModel classModel = parseClass(clss);

            List<ComponentInfo> immutableFields = new ArrayList<>(constructor.getParameterCount());

            Parameter[] params = constructor.getParameters();
            Type[] types = new Type[params.length];
            for (int x = 0; x < params.length; x++) {
                types[x] = TypeArguments.resolve(params[x].getParameterizedType(), params[x].getType(), bindings);
            }

            // First try and identify the constructor arguments via bytecode inspection.
            String constructorDescriptor = MethodType.methodType(void.class, constructor.getParameterTypes())
                    .descriptorString();
            for (MethodModel method : classModel.methods()) {
                if (method.methodName().stringValue().equals("<init>")
                        && method.methodType().stringValue().equals(constructorDescriptor)) {
                    CodeModel code = method.code().orElse(null);
                    if (code != null) {
                        int[] own = new int[constructor.getParameterCount()];
                        Arrays.setAll(own, x -> x);
                        identifyArguments(types, constructor, own, immutableFields, code);
                    }
                    break;
                }
            }

            // If byte code analysis failed or if user supplies @Field annotations.
            for (int x = 0; x < params.length; x++) {
                final int paramIndex = x;
                List<ComponentInfo> storing = immutableFields.stream()
                        .filter(e -> e.getConstructorArgument() == paramIndex).toList();
                Field field = params[x].getAnnotation(Field.class);
                if (storing.isEmpty() && field != null) {
                    ComponentInfo component = new ComponentInfo(field.value(), params[x].getType());
                    component.setConstructorArgument(x);
                    immutableFields.add(component);
                    storing = List.of(component);
                }
                // A parameter @Field names already has its component, so one still without is unnamed.
                if (storing.isEmpty() && params[x].isAnnotationPresent(Union.class)) {
                    throw new CodeAnalysisException(String.format("%s's constructor does not assign its @Union "
                            + "parameter %d unchanged to a field, and no @Field names it", clss.getName(), x));
                }
                for (ComponentInfo component : storing) {
                    component.addMember(component == storing.getFirst() ? Role.PARAMETER : Role.SHARED_PARAMETER,
                            params[x]);
                }
            }

            // Find the matching accessor methods. Must have already found fields in constructor.
            Set<String> matched = new HashSet<>();
            examineAccessorMethods(clss, clss, immutableFields, classModel, matched);
            examineAccessorAnnotations(clss, clss, immutableFields, matched);

            Class<?> superClass = clss;
            while ((superClass = superClass.getSuperclass()) != null) {
                if (superClass == Object.class) {
                    continue;
                }
                ClassModel superClassModel = parseClass(superClass);

                examineAccessorMethods(clss, superClass, immutableFields, superClassModel, matched);
                examineAccessorAnnotations(clss, superClass, immutableFields, matched);
            }

            for (ComponentInfo component : immutableFields) {
                if (component.getReadMethod() == null) {
                    readPublicField(clss, component);
                }
            }

            // Counting components alone would let a parameter stored in two fields hide one stored in none.
            int[] reaching = new int[constructor.getParameterCount()];
            for (ComponentInfo component : immutableFields) {
                reaching[component.getConstructorArgument()]++;
            }
            for (int x = 0; x < reaching.length; x++) {
                if (reaching[x] > 1) {
                    throw new CodeAnalysisException(String.format(
                            "%s's constructor stores its parameter %d in more than one field", clss.getName(), x));
                }
            }
            if (immutableFields.size() != constructor.getParameterCount()) {
                throw new CodeAnalysisException(String.format(
                        "Failed to match immutable fields for class: %s. Add @Field annotations to assist.", clss));
            }

            // Nothing writes an unbound component, so it needs no read.
            for (ComponentInfo component : immutableFields) {
                if (component.getReadMethod() == null && !ComponentAnnotations.unbound(component)) {
                    throw new CodeAnalysisException(String.format(
                            "Failed to match immutable field accessor for class: %s. Add @Field annotations to assist field '%s'",
                            clss, component.getName()));
                }
            }

            // A super class and a subclass may each declare a field of one name.
            Set<String> names = new HashSet<>();
            for (ComponentInfo component : immutableFields) {
                Field field = ComponentAnnotations.field(component);
                String name = field != null && !field.value().isBlank() ? field.value() : component.getName();
                if (!names.add(name)) {
                    throw new CodeAnalysisException(String.format("%s has two fields named '%s'; @Field on an "
                            + "accessor names one apart", clss.getName(), name));
                }
            }

            fields.addAll(immutableFields);

        } catch (IllegalAccessException e) {
            // A public member the public lookup reaches through no class: none between it and the described class is
            // public, or exported.
            throw Members.notAccessible(clss, e);
        } catch (UncheckedIOException | NoSuchMethodException | NoSuchFieldException | SecurityException e) {
            throw new CodeAnalysisException("Failed to access class", e);
        }
    }

    /**
     * Reading a public field gives what an accessor returning it gives, the value it holds when read, whether or not
     * it is final: an accessor's field need not be final either. One that is not final is also found by
     * {@link GetSetFinder}, which adds its setter to this component rather than describing it again.
     */
    private static void readPublicField(Class<?> clss, ComponentInfo component)
            throws IllegalAccessException, NoSuchFieldException, CodeAnalysisException {
        String name = component.getName();
        // A parameter only @Field names has no declaring class; its name is taken as a field's, as for an accessor.
        Class<?> declarer = component.getDeclaringClass() != null ? component.getDeclaringClass()
                : fieldDeclarer(clss, clss.getName().replace('.', '/'), name);
        if (declarer == null) {
            return;
        }
        java.lang.reflect.Field field;
        try {
            field = declarer.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            return;
        }
        int modifiers = field.getModifiers();
        if (Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)) {
            component.setReadMethod(readAs(Members.getterDeclarerFirst(clss, field), component.getType()));
        }
    }

    private static ClassModel parseClass(Class<?> clss) {
        String resourceName = clss.getName().replace('.', '/') + ".class";
        ClassLoader loader = clss.getClassLoader();
        try (InputStream in = (loader != null) ? loader.getResourceAsStream(resourceName)
                : ClassLoader.getSystemResourceAsStream(resourceName)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("Could not locate class file for " + clss.getName()));
            }
            return ClassFile.of().parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * @param described the class whose components these are: {@code clss} or a subclass of it
     * @param matched as for {@link #examineAccessorAnnotations}
     */
    private void examineAccessorMethods(Class<?> described, Class<?> clss, List<ComponentInfo> immutableFields,
            ClassModel classModel, Set<String> matched)
            throws NoSuchMethodException, IllegalAccessException, SecurityException, CodeAnalysisException {

        for (MethodModel method : classModel.methods()) {
            // An accessor takes no argument but the instance; in a static method slot 0 is no instance, and the
            // public lookup reaches no method that is not public.
            if (!method.methodType().stringValue().startsWith("()") || method.flags().has(AccessFlag.STATIC)
                    || !method.flags().has(AccessFlag.PUBLIC) || matched.contains(method.methodName().stringValue())) {
                continue;
            }
            if (examineAccessor(described, clss, immutableFields, method)) {
                matched.add(method.methodName().stringValue());
            }
        }
    }

    /**
     * Looks through a zero-arg method's instructions for the ALOAD-GETFIELD-RETURN pattern that
     * identifies a simple accessor. The field used in the GETFIELD instruction must already
     * have been discovered via the constructor (in {@code fields}).
     *
     * @return whether the method is the accessor of a component
     */
    private boolean examineAccessor(Class<?> described, Class<?> clss, List<ComponentInfo> fields, MethodModel method)
            throws NoSuchMethodException, IllegalAccessException, SecurityException, CodeAnalysisException {
        boolean foundLoadThis = false;
        String lastField = null;
        Class<?> lastDeclarer = null;
        boolean accessor = false;

        CodeModel code = method.code().orElse(null);
        if (code == null) {
            return false;
        }

        for (CodeElement element : code) {
            if (element instanceof LoadInstruction load) {
                if (load.slot() == 0) {
                    foundLoadThis = true;
                }
            } else if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD) {
                if (foundLoadThis) {
                    lastField = field.name().stringValue();
                    lastDeclarer = fieldDeclarer(clss, field.owner().asInternalName(), lastField);
                }
            } else if (element instanceof ReturnInstruction) {
                if (foundLoadThis && lastField != null) {
                    final String fieldName = lastField;
                    final Class<?> declarer = lastDeclarer;
                    ComponentInfo info = declarer == null ? null : fields.stream()
                            .filter(e -> e.getName().equals(fieldName)
                                    && (e.getDeclaringClass() == null || e.getDeclaringClass() == declarer))
                            .findFirst().orElse(null);
                    if (info != null) {
                        Method accessorMethod = clss.getDeclaredMethod(method.methodName().stringValue());
                        info.addMember(Role.ACCESSOR, accessorMethod);
                        info.setReadMethod(
                                readAs(Members.virtualDeclarerFirst(described, accessorMethod), info.getType()));
                        info.addAccessor(accessorMethod);
                        accessor = true;
                    }
                }
                // Fall through to reset -- a return ends the accessor pattern either way.
                foundLoadThis = false;
                lastField = null;
            } else if (element instanceof Instruction) {
                foundLoadThis = false;
                lastField = null;
            }
            // Non-instruction elements (labels, line numbers, etc.) don't affect the invariant.
        }

        return accessor;
    }

    private static Class<?> fieldDeclarer(Class<?> clss, String owner, String name) {
        java.lang.reflect.Field field = resolveField(clss, owner, name);
        return field == null ? null : field.getDeclaringClass();
    }

    /**
     * Resolves a field reference as the JVM does, from the class it names upward. The name alone does not say which
     * field an accessor returns or a constructor stores: a super class may declare a field of the same name.
     *
     * @return the field, or null where {@code owner} is not {@code clss} or a super class of it
     */
    private static java.lang.reflect.Field resolveField(Class<?> clss, String owner, String name) {
        Class<?> from = clss;
        while (from != null && !from.getName().replace('.', '/').equals(owner)) {
            from = from.getSuperclass();
        }
        return Members.resolveField(from, name);
    }

    /**
     * A field may hold the constructor parameter converted to a wider, boxed or erased type, but the value it holds
     * is still that parameter's, so the accessor is read as the component's type: the conversion back is exact.
     */
    private static MethodHandle readAs(MethodHandle accessor, Class<?> type) {
        Class<?> returned = accessor.type().returnType();
        if (returned == type) {
            return accessor;
        }
        try {
            MethodHandles.identity(type).asType(MethodType.methodType(returned, type));
        } catch (WrongMethodTypeException e) {
            return accessor;
        }
        return MethodHandles.explicitCastArguments(accessor, accessor.type().changeReturnType(type));
    }

    /**
     * @param matched the accessor names matched so far, in subclasses of {@code clss}: a handle on a method a
     *        subclass overrides dispatches to the override, which reads the field the override returns
     */
    private void examineAccessorAnnotations(Class<?> described, Class<?> clss, List<ComponentInfo> immutableFields,
            Set<String> matched) throws IllegalAccessException, NoSuchMethodException, CodeAnalysisException {
        // Possibly failed to find accessor through invariant byte code analysis.
        // Fallback on @Field annotation or method name.
        for (Method method : clss.getDeclaredMethods()) {

            if (method.getParameterCount() > 0 || Modifier.isStatic(method.getModifiers())
                    || !Modifier.isPublic(method.getModifiers()) || matched.contains(method.getName())) {
                continue;
            }

            final String name = method.getName();
            // A method sees only fields its own class or a super class of it declares.
            ComponentInfo component = immutableFields.stream().filter(e -> e.getName().equals(name)
                    && e.getReadMethod() == null
                    && (e.getDeclaringClass() == null || e.getDeclaringClass().isAssignableFrom(clss)))
                    .findFirst().orElse(null);
            if (component != null
                    && (method.isAnnotationPresent(Field.class) || method.isAnnotationPresent(Union.class))) {
                component.setReadMethod(Members.virtualDeclarerFirst(described, method));
                component.addMember(Role.ACCESSOR, method);
                matched.add(name);
                component.addAccessor(method);
            }
        }
    }

    /**
     * Matches up constructor arguments with the relevant fields for a class. It requires that
     * each argument is not mutated before being assigned to the field. It relies on the fact that
     * the instructions required to assign a value to a field use the following operations:
     *
     * <pre>
     * ALOAD 0 // Load this object reference.
     * ILOAD x // Load the value for the parameter.
     * PUTFIELD y // Assign the value x to the field y on this object.
     * </pre>
     *
     * A {@code super(…)} or {@code this(…)} call passing only this constructor's parameters, unchanged, is analysed
     * recursively. A component is bound by the top constructor, so a field a called constructor stores takes the
     * index and type of the top parameter that reaches it, never the called constructor's own.
     *
     * @param types the type of each parameter of the top constructor
     * @param toTop for each parameter of {@code constructor}, the index of the top parameter it holds
     * @return for each parameter of {@code constructor}, whether a field stores it, here or further up
     */
    private boolean[] identifyArguments(Type[] types, Constructor<?> constructor, int[] toTop,
            List<ComponentInfo> fields, CodeModel code) throws CodeAnalysisException {
        boolean[] foundLoadThis = {false};
        boolean[] foundLoadArg = {false};
        boolean[] stored = new boolean[constructor.getParameterCount()];
        String ownName = constructor.getDeclaringClass().getName().replace('.', '/');
        String superName = constructor.getDeclaringClass().getSuperclass().getName().replace('.', '/');

        Map<Integer, Integer> loadIndexToParamMap = new HashMap<>();
        Class<?>[] paramClasses = constructor.getParameterTypes();
        int paramCounter = 0;
        for (int x = 0; x < paramClasses.length; x++) {
            // Slot 0 is `this`; parameters start at slot 1.
            paramCounter++;
            loadIndexToParamMap.put(paramCounter, x);
            if (paramClasses[x] == long.class || paramClasses[x] == double.class) {
                // Long/double locals occupy two slots.
                paramCounter++;
            }
        }
        int maxVarLoadSlot = paramCounter;
        boolean[] assigned = assignedParameters(code, paramClasses);

        List<Integer> args = new ArrayList<>();

        for (CodeElement element : code) {
            if (element instanceof LoadInstruction load) {
                if (load.opcode() == Opcode.ALOAD || load.opcode() == Opcode.ALOAD_0
                        || load.opcode() == Opcode.ALOAD_1 || load.opcode() == Opcode.ALOAD_2
                        || load.opcode() == Opcode.ALOAD_3) {
                    if (!foundLoadThis[0]) {
                        if (load.slot() == 0) {
                            foundLoadThis[0] = true;
                        }
                        continue;
                    }
                }
                if (foundLoadThis[0] && load.slot() > 0 && load.slot() <= maxVarLoadSlot) {
                    int param = loadIndexToParamMap.get(load.slot());
                    if (assigned[param]) {
                        foundLoadThis[0] = false;
                        foundLoadArg[0] = false;
                        args.clear();
                        continue;
                    }
                    foundLoadArg[0] = true;
                    args.add(param);
                }
            } else if (element instanceof FieldInstruction field && field.opcode() == Opcode.PUTFIELD) {
                if (foundLoadThis[0] && foundLoadArg[0] && args.size() == 1) {
                    int arg = args.get(0);
                    String name = field.name().stringValue();
                    String owner = field.owner().asInternalName();
                    java.lang.reflect.Field target = resolveField(constructor.getDeclaringClass(), owner, name);
                    if (target == null) {
                        throw new CodeAnalysisException(String.format("%s's constructor assigns field %s.%s, which "
                                + "resolves to no field of the class or a super class",
                                constructor.getDeclaringClass().getName(), owner.replace('/', '.'), name));
                    }
                    ComponentInfo component = new ComponentInfo(name, types[toTop[arg]]);
                    component.setConstructorArgument(toTop[arg]);
                    component.setDeclaringClass(target.getDeclaringClass());
                    component.addMember(Role.STORED_FIELD, target);

                    fields.add(component);
                    stored[arg] = true;
                }
                foundLoadThis[0] = false;
                foundLoadArg[0] = false;
                args.clear();
            } else if (element instanceof InvokeInstruction invoke && invoke.opcode() == Opcode.INVOKESPECIAL
                    && invoke.name().stringValue().equals("<init>")
                    && (invoke.owner().asInternalName().equals(superName)
                            || invoke.owner().asInternalName().equals(ownName))) {
                // A call passing anything but this constructor's own parameters, unchanged, binds nothing: a
                // constant fixes a field that is no component, and a parameter it leaves unstored is matched by
                // @Field or refused by findComponents' coverage check, as without the call.
                int parameterCount = invoke.typeSymbol().parameterCount();
                if (parameterCount > 0 && foundLoadThis[0] && args.size() == parameterCount) {
                    identifyCallArguments(types, constructor, toTop, invoke, args, stored, fields);
                }
                foundLoadThis[0] = false;
                foundLoadArg[0] = false;
                args.clear();
            } else if (element instanceof Instruction) {
                foundLoadThis[0] = false;
                foundLoadArg[0] = false;
                args.clear();
            }
            // Non-instruction elements (labels, line numbers, etc.) don't affect the invariant.
        }

        return stored;
    }

    /**
     * A parameter the constructor assigns anywhere is read as its argument nowhere: bytecode order is not execution
     * order once the code branches, so a load ahead of the assignment is not known to see the argument either.
     *
     * @return for each parameter, whether the code stores into or increments one of its local slots
     */
    private static boolean[] assignedParameters(CodeModel code, Class<?>[] parameters) {
        int[] firstSlot = new int[parameters.length + 1];
        firstSlot[0] = 1;
        for (int x = 0; x < parameters.length; x++) {
            firstSlot[x + 1] = firstSlot[x] + TypeKind.from(parameters[x]).slotSize();
        }
        boolean[] assigned = new boolean[parameters.length];
        for (CodeElement element : code) {
            int slot;
            int width;
            if (element instanceof StoreInstruction store) {
                slot = store.slot();
                width = store.typeKind().slotSize();
            } else if (element instanceof IncrementInstruction increment) {
                slot = increment.slot();
                width = 1;
            } else {
                continue;
            }
            for (int x = 0; x < parameters.length; x++) {
                if (slot < firstSlot[x + 1] && firstSlot[x] < slot + width) {
                    assigned[x] = true;
                }
            }
        }
        return assigned;
    }

    /**
     * A {@code super(…)} call reaches a public constructor of the super class; a {@code this(…)} call reaches a
     * constructor of the class itself, whatever its access, since the class's own constructor calls it.
     *
     * @param args the parameter of {@code constructor} passed as each argument of the call
     */
    private void identifyCallArguments(Type[] types, Constructor<?> constructor, int[] toTop,
            InvokeInstruction invoke, List<Integer> args, boolean[] stored, List<ComponentInfo> fields)
            throws CodeAnalysisException {
        Class<?> ownClass = constructor.getDeclaringClass();
        boolean delegates = invoke.owner().asInternalName().equals(ownClass.getName().replace('.', '/'));
        Class<?> calledClass = delegates ? ownClass : ownClass.getSuperclass();
        String descriptor = invoke.type().stringValue();
        Class<?>[] calledParameters;
        try {
            calledParameters = MethodType.fromMethodDescriptorString(descriptor, calledClass.getClassLoader())
                    .parameterArray();
        } catch (TypeNotPresentException e) {
            throw new CodeAnalysisException(String.format("%s's constructor calls %s.<init>%s, naming a type that "
                    + "cannot be loaded", ownClass.getName(), calledClass.getName(), descriptor), e);
        }
        String call = String.format("%s's constructor calls %s.<init>(%s)", ownClass.getName(),
                calledClass.getName(),
                Arrays.stream(calledParameters).map(Class::getTypeName).collect(Collectors.joining(", ")));

        Constructor<?> called;
        try {
            called = delegates ? ownClass.getDeclaredConstructor(calledParameters)
                    : calledClass.getConstructor(calledParameters);
        } catch (NoSuchMethodException e) {
            throw new CodeAnalysisException(call + (delegates ? ", which the class does not declare"
                    : ", which is not a public constructor of its super class"), e);
        }

        int[] calledToTop = new int[args.size()];
        for (int x = 0; x < args.size(); x++) {
            calledToTop[x] = toTop[args.get(x)];
        }

        List<ComponentInfo> calledFields = new ArrayList<>();
        boolean[] calledStored = new boolean[calledParameters.length];
        for (MethodModel calledMethod : parseClass(calledClass).methods()) {
            if (calledMethod.methodName().stringValue().equals("<init>")
                    && calledMethod.methodType().stringValue().equals(descriptor)) {
                calledStored = identifyArguments(types, called, calledToTop, calledFields,
                        calledMethod.code().orElseThrow());
                break;
            }
        }

        for (int x = 0; x < calledStored.length; x++) {
            if (!calledStored[x]) {
                throw new CodeAnalysisException(String.format("%s, which does not store its parameter %d unchanged "
                        + "in a field", call, x));
            }
        }

        for (int arg : args) {
            stored[arg] = true;
        }
        fields.addAll(calledFields);
    }
}
