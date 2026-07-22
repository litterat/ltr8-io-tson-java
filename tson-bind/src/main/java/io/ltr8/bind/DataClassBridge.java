package io.ltr8.bind;

import java.lang.invoke.MethodHandle;

public class DataClassBridge {

    // The embedded class type.
    private final Class<?> dataClass;

    // Method handle to convert object to data object.
    // Converts from typeClass -> dataClasss.
    private final MethodHandle toData;

    // Method handle to convert data object to target object.
    // Converts from dataClass -> typeClass.
    private final MethodHandle toObject;

    public DataClassBridge(Class<?> serialType, MethodHandle toData,
                         MethodHandle toObject) {

        this.dataClass = serialType;
        this.toData = toData;
        this.toObject = toObject;
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

    @Override
    public String toString() {
        return "[ dataClass=" + dataClass.getName() + "]";
    }
}
