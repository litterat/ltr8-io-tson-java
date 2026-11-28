package io.ltr8.bind;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.UndeclaredThrowableException;

public class DataClassBridge {

    // The embedded class type.
    private final Class<?> dataClass;

    // Method handle to convert object to data object.
    // Converts from typeClass -> dataClasss.
    private final MethodHandle toData;

    // Method handle to convert data object to target object.
    // Converts from dataClass -> typeClass.
    private final MethodHandle toObject;

    private final boolean transparent;

    public DataClassBridge(Class<?> serialType, MethodHandle toData,
                         MethodHandle toObject) {
        this(serialType, toData, toObject, false);
    }

    private DataClassBridge(Class<?> serialType, MethodHandle toData, MethodHandle toObject, boolean transparent) {
        this.dataClass = serialType;
        this.toData = toData;
        this.toObject = toObject;
        this.transparent = transparent;
    }

    /**
     * The bridge a {@code @Transparent} class crosses to its one component by, which makes the descriptor carrying
     * it {@link DataClass#isTransparent}.
     */
    public static DataClassBridge transparent(Class<?> component, MethodHandle toData, MethodHandle toObject) {
        return new DataClassBridge(component, toData, toObject, true);
    }

    boolean isTransparent() {
        return transparent;
    }

    /**
     * @return The embedded class. This may be equal to the target class.
     */
    public Class<?> dataClass() {
        return dataClass;
    }

    /**
     * @return A MethodHandle that has the signature T embed(Object[] values).
     */
    public MethodHandle toObject() {
        return toObject;
    }

    /**
     * @return A MethodHandle that has the signature Object[] project(T object)
     */
    public MethodHandle toData() {
        return toData;
    }

    /**
     * {@code object} converted to its data form through {@link #toData()}.
     *
     * @param object an instance of the bridged class
     * @return its data form, an instance of {@link #dataClass()}
     * @throws ClassCastException           where {@code object} is not an instance of the bridged class
     * @throws UndeclaredThrowableException carrying a checked throwable the conversion threw; an unchecked one
     *                                      passes through as itself
     */
    public Object toData(Object object) {
        try {
            return toData.invoke(object);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    /**
     * {@code data} converted to the bridged class through {@link #toObject()}.
     *
     * @param data an instance of {@link #dataClass()}
     * @return an instance of the bridged class
     * @throws ClassCastException           where {@code data} is not an instance of {@link #dataClass()}
     * @throws UndeclaredThrowableException carrying a checked throwable the conversion threw; an unchecked one
     *                                      passes through as itself
     */
    public Object toObject(Object data) {
        try {
            return toObject.invoke(data);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    @Override
    public String toString() {
        return "[ dataClass=" + dataClass.getName() + "]";
    }
}
