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
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.WrongMethodTypeException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Matches up the arguments of a hand-written immutable class's constructor with its accessors --
 * for classes that predate (or simply aren't) real Java records, whose parameter names Java
 * erases and whose accessor-to-field correspondence reflection alone can't recover. It relies on
 * both the accessors and constructor using simple, unmodified set/get of a field (documented as a
 * requirement on {@link ComponentFinder}; the {@code @Field} annotation is the escape hatch when a
 * constructor does more than plain assignment).
 *
 * <p>Reads the compiled bytecode of the constructor and each zero-arg method using the JDK's own
 * {@link java.lang.classfile Class-File API} (stable since JDK 24, part of {@code java.base}) --
 * not a third-party bytecode library. This replaced an ObjectWeb ASM-based implementation doing
 * the exact same analysis: {@link LoadInstruction} is ASM's {@code VarInsnNode} (ALOAD/ILOAD/etc,
 * with {@link LoadInstruction#slot()} where ASM had {@code VarInsnNode.var}), {@link FieldInstruction}
 * is ASM's {@code FieldInsnNode}, {@link InvokeInstruction} is ASM's {@code MethodInsnNode} (used
 * here for the {@code super()} call). The instruction-walking algorithm itself is unchanged from
 * the ASM version; only the API reading the bytecode changed.
 */
public class ImmutableFinder implements ComponentFinder {

    public ImmutableFinder() {
    }

    @Override
    public void findComponents(Class<?> clss, Constructor<?> constructor, List<ComponentInfo> fields)
            throws CodeAnalysisException {

        try {
            Lookup lookup = MethodHandles.publicLookup();

            ClassModel classModel = parseClass(clss);

            List<ComponentInfo> immutableFields = new ArrayList<>(constructor.getParameterCount());

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
                        identifyArguments(constructor, constructor, own, immutableFields, code);
                    }
                    break;
                }
            }

            // If byte code analysis failed or if user supplies @Field annotations.
            Parameter[] params = constructor.getParameters();
            for (int x = 0; x < params.length; x++) {
                Field field = params[x].getAnnotation(Field.class);
                if (field != null) {
                    final int paramIndex = x;
                    ComponentInfo component = immutableFields.stream()
                            .filter(e -> e.getConstructorArgument() == paramIndex).findFirst().orElse(null);
                    if (component != null) {
                        component.setField(field);
                    } else {
                        component = new ComponentInfo(field.value(), params[x].getType());
                        component.setConstructorArgument(x);
                        component.setField(field);
                        immutableFields.add(component);
                    }
                }

                Union union = params[x].getAnnotation(Union.class);
                if (union != null) {
                    final int paramIndex = x;
                    ComponentInfo component = immutableFields.stream()
                            .filter(e -> e.getConstructorArgument() == paramIndex).findFirst().orElse(null);
                    // A parameter @Field names already has its component, so one still without is unnamed.
                    if (component == null) {
                        throw new CodeAnalysisException(String.format("%s's constructor does not assign its @Union "
                                + "parameter %d unchanged to a field, and no @Field names it", clss.getName(), x));
                    }
                    component.setUnion(union);
                }
            }

            // Find the matching accessor methods. Must have already found fields in constructor.
            examineAccessorMethods(clss, immutableFields, classModel);
            examineAccessorAnnotations(clss, immutableFields, lookup);

            Class<?> superClass = clss;
            while ((superClass = superClass.getSuperclass()) != null) {
                if (superClass == Object.class) {
                    continue;
                }
                ClassModel superClassModel = parseClass(superClass);

                examineAccessorMethods(superClass, immutableFields, superClassModel);
                examineAccessorAnnotations(superClass, immutableFields, lookup);
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

            // Check all params have valid information.
            for (ComponentInfo component : immutableFields) {
                if (component.getReadMethod() == null) {
                    throw new CodeAnalysisException(String.format(
                            "Failed to match immutable field accessor for class: %s. Add @Field annotations to assist field '%s'",
                            clss, component.getName()));
                }
            }

            fields.addAll(immutableFields);

        } catch (UncheckedIOException | NoSuchMethodException | SecurityException | IllegalAccessException e) {
            throw new CodeAnalysisException("Failed to access class", e);
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

    private void examineAccessorMethods(Class<?> clss, List<ComponentInfo> immutableFields, ClassModel classModel)
            throws NoSuchMethodException, IllegalAccessException, SecurityException, CodeAnalysisException {

        for (MethodModel method : classModel.methods()) {
            // Only interested in accessors: zero-arg methods.
            if (!method.methodType().stringValue().startsWith("()")) {
                continue;
            }
            examineAccessor(clss, immutableFields, method);
        }
    }

    /**
     * Looks through a zero-arg method's instructions for the ALOAD-GETFIELD-RETURN pattern that
     * identifies a simple accessor. The field name used in the GETFIELD instruction must already
     * have been discovered via the constructor (in {@code fields}).
     */
    private String examineAccessor(Class<?> clss, List<ComponentInfo> fields, MethodModel method)
            throws NoSuchMethodException, IllegalAccessException, SecurityException, CodeAnalysisException {
        boolean foundLoadThis = false;
        String lastField = null;

        CodeModel code = method.code().orElse(null);
        if (code == null) {
            return null;
        }

        for (CodeElement element : code) {
            if (element instanceof LoadInstruction load) {
                if (load.slot() == 0) {
                    foundLoadThis = true;
                }
            } else if (element instanceof FieldInstruction field && field.opcode() == Opcode.GETFIELD) {
                if (foundLoadThis) {
                    lastField = field.name().stringValue();
                }
            } else if (element instanceof ReturnInstruction) {
                if (foundLoadThis && lastField != null) {
                    final String fieldName = lastField;
                    ComponentInfo info = fields.stream().filter(e -> e.getName().equals(fieldName)).findFirst()
                            .orElse(null);
                    if (info != null) {
                        Method accessorMethod = clss.getDeclaredMethod(method.methodName().stringValue());

                        Field field = accessorMethod.getAnnotation(Field.class);
                        if (field != null) {
                            info.setField(field);
                        }

                        Union union = accessorMethod.getAnnotation(Union.class);
                        if (union != null) {
                            info.setUnion(union);
                        }

                        info.setReadMethod(readAs(MethodHandles.publicLookup().unreflect(accessorMethod),
                                info.getType()));
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

        return lastField;
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

    private void examineAccessorAnnotations(Class<?> clss, List<ComponentInfo> immutableFields, Lookup lookup)
            throws IllegalAccessException, CodeAnalysisException {
        // Possibly failed to find accessor through invariant byte code analysis.
        // Fallback on @Field annotation or method name.
        for (Method method : clss.getDeclaredMethods()) {

            if (method.getParameterCount() > 0) {
                continue;
            }

            final String name = method.getName();
            ComponentInfo component = immutableFields.stream().filter(e -> e.getName().equals(name)).findFirst()
                    .orElse(null);
            if (component != null && component.getReadMethod() == null) {
                Field field = method.getAnnotation(Field.class);
                if (field != null) {
                    component.setReadMethod(lookup.unreflect(method));
                    component.setField(field);
                }

                Union union = method.getAnnotation(Union.class);
                if (union != null) {
                    component.setReadMethod(lookup.unreflect(method));
                    component.setField(field);
                }
            }
        }
    }

    private void checkFieldAnnotation(ComponentInfo info, Class<?> clss, String fieldName)
            throws CodeAnalysisException {
        try {
            java.lang.reflect.Field clssField = clss.getDeclaredField(fieldName);
            Field field = clssField.getAnnotation(Field.class);
            if (field != null) {
                info.setField(field);
            }

            Union union = clssField.getAnnotation(Union.class);
            if (union != null) {
                info.setUnion(union);
            }

        } catch (NoSuchFieldException e) {
            throw new CodeAnalysisException(String.format(
                    "%s's constructor assigns field '%s', which the class does not declare",
                    clss.getName(), fieldName), e);
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
     * recursively. A component is bound by the {@code top} constructor, so a field a called constructor stores takes
     * the index and type of the {@code top} parameter that reaches it, never the called constructor's own.
     *
     * @param toTop for each parameter of {@code constructor}, the index of the {@code top} parameter it holds
     * @return for each parameter of {@code constructor}, whether a field stores it, here or further up
     */
    private boolean[] identifyArguments(Constructor<?> top, Constructor<?> constructor, int[] toTop,
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
                    Parameter param = top.getParameters()[toTop[arg]];
                    ComponentInfo component = new ComponentInfo(field.name().stringValue(), param.getType());
                    component.setConstructorArgument(toTop[arg]);

                    java.lang.reflect.Type paramType = param.getParameterizedType();
                    if (paramType instanceof ParameterizedType pt) {
                        component.setParamType(pt);
                    }

                    checkFieldAnnotation(component, constructor.getDeclaringClass(), field.name().stringValue());

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
                    identifyCallArguments(top, constructor, toTop, invoke, args, stored, fields);
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
    private void identifyCallArguments(Constructor<?> top, Constructor<?> constructor, int[] toTop,
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
                calledStored = identifyArguments(top, called, calledToTop, calledFields,
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
