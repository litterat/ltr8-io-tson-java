package io.ltr8.bind.analysis;

import io.ltr8.annotation.DataBridge;
import io.ltr8.annotation.Field;
import io.ltr8.annotation.FieldOrder;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/**
 * In a code-first schema, the record binder creates the DataClassRecord and from that information
 * return the Record definition. The binder registers the DataClassRecord to the TypeContext, and
 * the caller adds the definition to the TypeLibrary.
 */
public class DefaultRecordBinder {

	private static final String TODATA_METHOD = "toData";
	private static final String TOOBJECT_METHOD = "toObject";

	public DefaultRecordBinder() {
	}


	private DataClassBridge resolveBridge(DataBindContext context, Class<?> targetClass) throws DataBindException {
		// If the class if exporting/importing a dataclass.
		if (!ToData.class.isAssignableFrom(targetClass)) {
			throw new DataBindException("Unexpected");
		}

		try {
			Class<?> dataType = findToDataClass(targetClass);

			// We require that a constructor be present that takes the data class as input.
			MethodHandle toObject = MethodHandles.publicLookup()
					.unreflectConstructor(targetClass.getConstructor(dataType));

			// The class implements ToData so get the method.
			MethodHandle toData = MethodHandles.publicLookup()
					.unreflect(targetClass.getMethod(TODATA_METHOD));

			// The constructor and data components are copied from the data class.
			return new DataClassBridge(dataType, toData, toObject);

		} catch (NoSuchMethodException | IllegalAccessException ex) {
			throw new DataBindException(String.format(
					"Failed to get data descriptor. Failed to find constructor for %s", targetClass.getName()));
		}
	}

	private Class<?> findToDataClass(Class<?> targetClass) throws DataBindException {
		if (!ToData.class.isAssignableFrom(targetClass)) {
			throw new DataBindException("Unexpected");
		}

		for (Type genericInterface : targetClass.getGenericInterfaces()) {

			if (genericInterface instanceof ParameterizedType
					&& ((ParameterizedType) genericInterface).getRawType() == ToData.class) {

				// ToData has one parameter so this should be safe.
				return (Class<?>) ((ParameterizedType) genericInterface).getActualTypeArguments()[0];
			}
		}

		throw new DataBindException(String.format(
				"Failed to get data descriptor. Failed to find constructor for %s", targetClass.getName()));
	}

	/**
	 * Return a DataClassRecord for the specified targetClass in the provided DataBindContext.
	 *
	 * @param context DataBindContext the that targetClass will be resolved to a DataClassRecord
	 * @param targetClass the target class to create the DataClassRecord
	 * @return DataClassRecord for the targetClass
	 * @throws DataBindException where the DataClassRecord can't be successfully created.
	 */
	public DataClassRecord resolveRecord(DataBindContext context, Class<?> targetClass)
			throws DataBindException {
		try {

			DataClassBridge bridge = null;
			if (ToData.class.isAssignableFrom(targetClass)) {

				bridge = resolveBridge(context, targetClass);

				DataClassRecord descriptor = resolveClassRecord(context, bridge.dataClass());

				return new DataClassRecord(targetClass, bridge, descriptor.isMutable(), descriptor.creator(),
						descriptor.constructor(), descriptor.fields());
			} else {

				DataClassRecord descriptor = resolveClassRecord(context, targetClass);

				// At this point descriptor is not null but hasn't been added to context.
				// Loop through know interfaces and if we find a union type add this descriptor to it.
				registerUnionInterfaces(context, targetClass);

				// Same applies to super classes that are abstract.
				registerUnionSubclasses(context, targetClass);

				return new DataClassRecord(descriptor.typeClass(), bridge, descriptor.isMutable(), descriptor.creator(),
						descriptor.constructor(), descriptor.fields());
			}
		} catch (IllegalAccessException | NoSuchMethodException | SecurityException | DataBindException e) {
			throw new DataBindException("Failed to resolve", e);
		}
	}

	/**
	 * Performs the main work of collecting the information for the fields for the DataClassRecord
	 *
	 * @param context DataBindContext that the DataClassRecord will be added.
	 * @param targetClass targetClass that must be a Record/Pojo/class that has multiple fields.
	 * @return DataClassRecord for the targetClass
	 */
	private DataClassRecord resolveClassRecord(DataBindContext context, Class<?> targetClass)
			throws IllegalAccessException, NoSuchMethodException, SecurityException, DataBindException {
		// This is a data class so need to decide what is/isn't data.
		List<ComponentInfo> components = new ArrayList<>();

		// get the constructor.
		Constructor<?> ctor = getConstructor(targetClass);

		if (targetClass.isRecord()) {
			// A genuine Java record's components come straight from the JDK's own
			// RecordComponent API -- authoritative, no guessing needed, and a record can't have
			// setters, so there's nothing for GetSetFinder to add.
			new RecordComponentFinder().findComponents(targetClass, ctor, components);
		} else {
			// A hand-written tuple class (identified by @Record, DefaultClassBinder.isRecord()).
			// ImmutableFinder recovers constructor-set (immutable) fields via bytecode analysis;
			// GetSetFinder then adds any additional mutable getter/setter fields on top -- a
			// single class can mix both (see MixedImmutable in tson-bind's own tests).
			new ImmutableFinder().findComponents(targetClass, ctor, components);
			new GetSetFinder().findComponents(targetClass, ctor, components);
		}

		// Modify the order of fields if order annotation supplied.
		FieldOrder dataOrder = targetClass.getAnnotation(FieldOrder.class);
		if (dataOrder != null) {
			reorderFields(dataOrder, components);
		}

		// Prepare the field descriptors.
		DataClassField[] dataComponents = new DataClassField[components.size()];
		for (int x = 0; x < components.size(); x++) {
			ComponentInfo info = components.get(x);

			dataComponents[x] = resolveField(context, targetClass, info, x);
		}

		// get the correct data constructor method handle. Either a constructor or a
		// static method tagged with @Data.
		MethodHandle dataConstructor = getDataConstructor(targetClass);

		// Build a MethodHandle that creates object and also calls setters with order of
		// fields as defined in components.
		MethodHandle constructor = createTupleConstructor(targetClass, components, dataComponents, dataConstructor);

		// See if there's an empty constructor available.
		MethodHandle creator = null;
		try {
			Constructor<?> creatorCtor = targetClass.getConstructor();
			creator = MethodHandles.publicLookup().unreflectConstructor(creatorCtor);
		} catch (NoSuchMethodException e) {
			// ignore.
		}

		return new DataClassRecord(targetClass, null, false, creator, constructor, dataComponents);
	}

	/**
	 * Search through any super-classes of the target class and add this class any Union parent classes
	 *
	 * @param context DataBindContext for that the target class is being registered.
	 * @param targetClass target class
	 */
	private void registerUnionSubclasses(DataBindContext context, Class<?> targetClass) {
		Class<?> superClass = targetClass;
		while ((superClass = superClass.getSuperclass()) != null) {

			// An abstract type is a union type.
			if (Modifier.isAbstract(superClass.getModifiers())) {
				try {

					// Only attempt to add children if the union doesn't have child types specified.
					Union union = superClass
							.getAnnotation(Union.class);
					if (union != null && (union.value() == null || union.value().length == 0)
							&& !superClass.isSealed()) {
						DataClassUnion targetUnion = (DataClassUnion) context.getDescriptor(superClass);

						if (!targetUnion.isMemberType(targetClass)) {
							targetUnion.addMemberType(targetClass);
						}
					}
				} catch (Throwable t) {
					// ignore.
				}
			}
		}
	}

	/**
	 * Search through any implemented interfaces for the target class and if a Union has been declared
	 * add this to the member list.
	 *
	 * @param context DataBindContext for that the target class is being registered.
	 * @param targetClass target class
	 */
	private void registerUnionInterfaces(DataBindContext context, Class<?> targetClass) {
		Class<?>[] interfaces = targetClass.getInterfaces();
		for (Class<?> targetInterface : interfaces) {
			try {

				// Only attempt to add children if the union doesn't have child types specified.
				Union union = targetInterface
						.getAnnotation(Union.class);
				if (union != null && (union.value() == null || union.value().length == 0)
						&& !targetInterface.isSealed()) {
					DataClassUnion targetUnion = (DataClassUnion) context.getDescriptor(targetInterface);

					if (!targetUnion.isMemberType(targetClass)) {
						targetUnion.addMemberType(targetClass);
					}
				}
			} catch (Throwable t) {
				// ignore.
			}
		}
	}

	private MethodHandle accessor(ComponentInfo info) {
		return info.getReadMethod();
	}

	private MethodHandle setter(ComponentInfo info) {
		MethodHandle setter = null;
		if (info.getWriteMethod() != null) {
			setter = info.getWriteMethod();
		}
		return setter;
	}

	private boolean isRequired(ComponentInfo info) {
		Field fieldAnnotation = info.getField();
		boolean isRequired = false;
		if (fieldAnnotation != null) {
			isRequired = fieldAnnotation.required();
		}
		return isRequired;
	}

	private String fieldName(ComponentInfo info) {
		Field fieldAnnotation = info.getField();

		String fieldName = info.getName();
		if (fieldAnnotation != null) {

			if (fieldAnnotation.value() != null && !fieldAnnotation.value().isBlank()) {
				fieldName = fieldAnnotation.value();
			}
		}

		return fieldName;
	}

	/**
	 * Returns the DataClassField for the targetClass. Depending on the type of field build the DataClassField:
	 * - DataBridge - use the DataBridge as part of toData/toObject
	 * - Optional - use isPresent and unwrap/wrap for toData/toObject
	 * - OptionalInt, OptionalLong, OptionalDouble - use isPresent and unwrap/wrap.
	 * - Union -
	 * @param context DataBindContext that the DataClassRecord is being resoled. Recursively find member DataClass.
	 * @param targetClass target class for that the field is being found.
	 * @param info The ComponentInfo for the field that is being created.
	 * @param index The field index
	 * @return DataClassField for the ComponentField of the record.
	 */
	private DataClassField resolveField(DataBindContext context, Class<?> targetClass, ComponentInfo info, int index)
			throws NoSuchMethodException, IllegalAccessException, DataBindException {

		DataClassField component;

		Field fieldAnnotation = info.getField();

		if (fieldAnnotation != null && fieldAnnotation.bridge() != null
				&& fieldAnnotation.bridge() != DataBridge.class) {
			// The field annotation has a DataBridge
			component = resolveBridgeField(context, targetClass, info, index);
		} else if (info.getType() == Optional.class && info.getParamType() != null) {
			// If the input is Optional wrap the isPresent
			component = resolveOptionalField(context, targetClass, info, index);
		} else if (info.getType() == OptionalInt.class
				|| info.getType() == OptionalLong.class | info.getType() == OptionalDouble.class) {
			// Similar to Optional, use specific methods for isPresent
			component = resolveOptionalNumberField(context, targetClass, info, index);
		} else if (info.getUnion() != null) {
			//
			component = resolveUnionField(context, targetClass, info.getUnion(), info, index);
		} else {
			// Use basic information to build the DataClassField
			component = resolveSimpleField(context, targetClass, info, index);
		}

		return component;
	}

	/**
	 * A simple field is the last option for resolveField and assumes a known atomic or compound type.
	 *
	 * @param context The TypeContext that maps the TypeLibrary to the DataClass.
	 * @param targetClass The target record class that the field is a member.
	 * @param info ComponentInfo is the information collected about the field.
	 * @param index The field number in the record.
	 * @return the DataClassField object for the field.
	 */
	private DataClassField resolveSimpleField(DataBindContext context, Class<?> targetClass, ComponentInfo info, int index)
			throws DataBindException, NoSuchMethodException, IllegalAccessException {
		DataClass dataClass = context.getDescriptor(info.getType(),
				(info.getParamType() != null ? info.getParamType() : info.getType()));

		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);
		boolean isRequired = isRequired(info);
		String fieldName = fieldName(info);

		if (dataClass.typeClass().isPrimitive()) {
			isRequired = true;
		}

		// Make isPresent return true for primitives/isRequired or call Objects.nonNull(value)
		MethodHandle isPresent = null;
		if (info.getType().isPrimitive()) {
			isPresent = MethodHandles.constant(boolean.class, true);

			isPresent = MethodHandles.dropArguments(isPresent, 0, targetClass);
		} else {
			isPresent = MethodHandles.publicLookup()
					.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
					.asType(MethodType.methodType(boolean.class, info.getType()));

			isPresent = MethodHandles.filterArguments(isPresent, 0, accessor);
		}

		return new DataClassField(index, fieldName, info.getType(), dataClass, isRequired, isPresent, accessor, setter);
	}

	private DataClassField resolveUnionField(DataBindContext context, Class<?> targetClass,
											 Union union, ComponentInfo info, int index)
			throws NoSuchMethodException, IllegalAccessException, DataBindException {
		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);
		boolean isRequired = isRequired(info);
		String fieldName = fieldName(info);

		Class<?> unionClass = info.getType();

		if (union.value() == null || union.value().length == 0) {
			throw new CodeAnalysisException("Union annotation must have one or more classes");
		}


		Class<?>[] unionTypes = new Class[union.value().length];
		for (int z = 0; z < union.value().length; z++) {
			Class<?> clss = union.value()[z];
			if (!unionClass.isAssignableFrom(clss)) {
				throw new CodeAnalysisException("Union types not assignable from class type");
			}

			unionTypes[z] = clss;

			/*
			 * if (unionTypes[z] instanceof DataClassUnion || unionTypes[z] instanceof DataClassArray) {
			 * Embedded unions will require more work to decide on how valid other unions or arrays would be.
			 * Unions with unknown type sets is problematic. If another union member set is known it might be
			 * ok to add all children to the parent. Something for future. throw new DataBindException(
			 * "Embedded union types can not include other unions or arrays"); }
			 */
		}

		DataClassUnion dataUnion = new DataClassUnion(unionClass, unionTypes,
				union.sealed());

		MethodHandle isPresent = MethodHandles.publicLookup()
				.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
				.asType(MethodType.methodType(boolean.class, info.getType()));

		MethodHandle unionCheck = MethodHandles.publicLookup()
				.findVirtual(dataUnion.getClass(), "checkIsMember", MethodType.methodType(Object.class, Object.class))
				.bindTo(dataUnion);

		MethodHandle accessorFilter = MethodHandles.filterReturnValue(accessor, unionCheck);

		isPresent = MethodHandles.filterArguments(isPresent, 0, accessor);

		return new DataClassField(index, fieldName, unionClass, dataUnion, isRequired, isPresent, accessorFilter,
				setter);
	}

	private DataClassField resolveBridgeField(DataBindContext context, Class<?> targetClass, ComponentInfo info, int index)
			throws DataBindException, IllegalAccessException, NoSuchMethodException, SecurityException {

		Field fieldAnnotation = info.getField();
		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);
		boolean isRequired = isRequired(info);
		String fieldName = fieldName(info);

		@SuppressWarnings("rawtypes")
		Class<? extends DataBridge> bridgeClass = fieldAnnotation.bridge();

		ParameterizedType bridgeTypes = (ParameterizedType) bridgeClass.getGenericInterfaces()[0];

		// ToData has one parameter so this should be safe.
		Type bridgeDataType = bridgeTypes.getActualTypeArguments()[0];
		Type bridgeObjectType = bridgeTypes.getActualTypeArguments()[1];

		Class<?> bridgeDataClass;
		Class<?> bridgeObjectClass;

		if (bridgeDataType instanceof Class) {
			bridgeDataClass = (Class<?>) bridgeDataType;
		} else if (bridgeDataType instanceof ParameterizedType) {
			bridgeDataClass = (Class<?>) ((ParameterizedType) bridgeDataType).getRawType();
		} else {
			throw new CodeAnalysisException("Unrecognised Type");
		}

		if (bridgeObjectType instanceof Class) {
			bridgeObjectClass = (Class<?>) bridgeObjectType;
		} else if (bridgeObjectType instanceof ParameterizedType) {
			bridgeObjectClass = (Class<?>) ((ParameterizedType) bridgeObjectType).getRawType();
		} else {
			throw new CodeAnalysisException("Unrecognised Type");
		}

		// Recursively get hold of the data class descriptor.
		DataClass tupleData = context.getDescriptor(bridgeDataClass, bridgeDataType);

		if (!bridgeObjectClass.isAssignableFrom(info.getType())) {
			throw new CodeAnalysisException("Bridge object type not assignable from field type");
		}

		// TODO Currently assume Bridge classes have no state. This might need to change
		// if a bridge uses IOC framework.

		@SuppressWarnings("rawtypes")
		DataBridge bridge;
		try {
			bridge = bridgeClass.getConstructor().newInstance();
		} catch (InstantiationException | IllegalArgumentException | InvocationTargetException e) {
			throw new CodeAnalysisException("Failed to instantiate bridge", e);
		}

		// bridge.toData(dataType):type
		MethodHandle bridgeToData = MethodHandles.publicLookup()
				.findVirtual(bridgeClass, TODATA_METHOD, MethodType.methodType(bridgeDataClass, bridgeObjectClass))
				.bindTo(bridge);

		accessor = accessor.asType(accessor.type().changeReturnType(bridgeObjectClass));

		// bridge.toData( targetClass.getOptional() ): optionalType
		accessor = MethodHandles.collectArguments(bridgeToData, 0, accessor);

		// bridge.toObject(type):dataType
		MethodHandle bridgeToObject = MethodHandles.publicLookup()
				.findVirtual(bridgeClass, TOOBJECT_METHOD, MethodType.methodType(bridgeObjectClass, bridgeDataClass))
				.bindTo(bridge);

		if (setter != null) {
			setter = MethodHandles.collectArguments(setter, 1, bridgeToObject)
					.asType(MethodType.methodType(info.getType(), bridgeDataClass));
		}

		// Use Objects.nonNull(accessor()) to check if value is present.
		// Probably not hugely efficient.
		MethodHandle isPresent = MethodHandles.publicLookup()
				.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
				.asType(MethodType.methodType(boolean.class, bridgeDataClass));

		isPresent = MethodHandles.filterArguments(isPresent, 0, accessor);

		return new DataClassField(index, fieldName, bridgeDataClass, tupleData, isRequired, isPresent, accessor,
				setter);
	}

	private DataClassField resolveOptionalField(DataBindContext context, Class<?> targetClass, ComponentInfo info,
			int index) throws DataBindException, NoSuchMethodException, IllegalAccessException {

		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);
		boolean isRequired = isRequired(info);
		String fieldName = fieldName(info);

		Class<?> optionalType = (Class<?>) info.getParamType().getActualTypeArguments()[0];

		DataClass dataClass = context.getDescriptor(optionalType);

		Lookup lookup = MethodHandles.publicLookup();

		// Optional.ofNullable(value);
		MethodHandle optionalToObject = lookup.findStatic(Optional.class, "ofNullable",
				MethodType.methodType(Optional.class, Object.class));

		// (value, elseValue ) -> value.orElse(elseValue);
		MethodHandle optionalToData = lookup.findVirtual(Optional.class, "get", MethodType.methodType(Object.class));

		// bridge.toData( targetClass.getOptional() ): optionalType
		MethodHandle optionalObject = MethodHandles.collectArguments(optionalToData, 0, accessor)
				.asType(MethodType.methodType(optionalType, targetClass));

		if (setter != null) {
			setter = MethodHandles.collectArguments(setter, 1, optionalToObject)
					.asType(MethodType.methodType(optionalType, Optional.class));
		}

		// (optional) -> optional.isPresent();
		MethodHandle isPresent = lookup.findVirtual(Optional.class, "isPresent", MethodType.methodType(boolean.class));

		isPresent = MethodHandles.collectArguments(isPresent, 0, accessor)
				.asType(MethodType.methodType(boolean.class, targetClass));

		// By definition optional is not required.
		isRequired = false;

		// TODO the constructor parameter will need to pass through the bridge.
		return new DataClassField(index, fieldName, optionalType, dataClass, isRequired, isPresent, optionalObject,
				setter);

	}

	private DataClassField resolveOptionalNumberField(DataBindContext context, Class<?> targetClass, ComponentInfo info,
			int index) throws NoSuchMethodException, IllegalAccessException, DataBindException {

		MethodHandle accessor = accessor(info);
		MethodHandle setter = setter(info);

		// Optional values by definition are not required.
		boolean isRequired = false;
		String fieldName = fieldName(info);

		// This will be one of OptionalInt, OptionalLong or OptionalDouble.
		Class<?> optionalClass = info.getType();

		// work out which Optional class this is by getting return type of orElseThrow.
		Class<?> optionalType = optionalClass.getDeclaredMethod("orElseThrow").getReturnType();
		Class<?> optionalNullableType = null;
		if (optionalType == int.class) {
			optionalNullableType = Integer.class;
		} else if (optionalType == long.class) {
			optionalNullableType = Long.class;
		} else if (optionalType == double.class) {
			optionalNullableType = Double.class;
		} else {
			throw new DataBindException("Optional number field of unknown type");
		}

		// Get data class for int, long or double.
		DataClass dataClass = context.getDescriptor(optionalNullableType);

		Lookup lookup = MethodHandles.publicLookup();

		// Construct the correct getAs method name.
		String getAsMethod = "getAs" + optionalType.getSimpleName().substring(0, 1).toUpperCase()
				+ optionalType.getSimpleName().substring(1);

		// (optionalx) -> optionalx.get();
		MethodHandle optionalToData = lookup.findVirtual(optionalClass, getAsMethod,
				MethodType.methodType(optionalType));

		// bridge.toData( targetClass.getOptional() ): optionalType
		MethodHandle fieldAccessor = MethodHandles.collectArguments(optionalToData, 0, accessor)
				.asType(MethodType.methodType(optionalType, targetClass));

		// Integer.valueOf( int );
		MethodHandle wrapInteger = lookup.findStatic(optionalNullableType, "valueOf",
				MethodType.methodType(optionalNullableType, optionalType));

		fieldAccessor = MethodHandles.filterArguments(wrapInteger, 0, fieldAccessor);

		if (setter != null) {

			// (value):boolean -> Objects.nonNull(value)
			MethodHandle checkNonNull = MethodHandles.publicLookup()
					.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
					.asType(MethodType.methodType(boolean.class, optionalNullableType));

			// (int):OptionalInt -> OptionalInt.of(int);
			MethodHandle optionalIntOf = MethodHandles.publicLookup().findStatic(optionalClass, "of",
					MethodType.methodType(optionalClass, optionalType));

			// (Integer):int -> Integer.intValue()
			MethodHandle integerIntValue = MethodHandles.publicLookup().findVirtual(optionalNullableType,
					optionalType.getSimpleName() + "Value", MethodType.methodType(optionalType));

			// (Integer):OptionalInt -> OptionalInt.of( Integer.intValue(i) )
			optionalIntOf = MethodHandles.collectArguments(optionalIntOf, 0, integerIntValue);

			// ():OptionalInt -> OptionalInt.empty()
			MethodHandle optionalIntEmpty = MethodHandles.publicLookup().findStatic(optionalClass, "empty",
					MethodType.methodType(optionalClass));

			// (Object[]):OptionalInt -> OptionalInt.empty()
			optionalIntEmpty = MethodHandles.dropArguments(optionalIntEmpty, 0, optionalNullableType);

			// (values[]):optionalType -> if (Object.nonNull(Integer)) { return OptionalInt.of(
			// Integer.intValue(i) ) } else { return OptionalInt.empty() }
			setter = MethodHandles.guardWithTest(checkNonNull, optionalIntOf, optionalIntEmpty);

		}

		// (optional) -> optional.isPresent();
		MethodHandle isPresent = lookup.findVirtual(optionalClass, "isPresent", MethodType.methodType(boolean.class));

		isPresent = MethodHandles.collectArguments(isPresent, 0, accessor)
				.asType(MethodType.methodType(boolean.class, targetClass));

		return new DataClassField(index, fieldName, optionalNullableType, dataClass, isRequired, isPresent,
				fieldAccessor, setter);
	}

	private void reorderFields(FieldOrder dataOrder, List<ComponentInfo> components) throws CodeAnalysisException {

		// Check lengths are ok.
		String[] fieldOrder = dataOrder.value();
		if (fieldOrder == null || fieldOrder.length != components.size()) {
			throw new CodeAnalysisException(
					"DataOrder annotation has different field count to components found in data");
		}

		// build map of components to select from.
		Map<String, ComponentInfo> componentMap = components.stream()
				.collect(Collectors.toMap(ComponentInfo::getName, e -> e));

		// check all fields are present.
		List<String> fieldOrderList = List.of(dataOrder.value());
		for (String field : fieldOrderList) {
			if (!componentMap.containsKey(field)) {
				throw new CodeAnalysisException(
						String.format("DataOrder annotation had no corresponding field '%s'", field));
			}
		}

		// sort the list.
		components.sort(Comparator.comparing((ComponentInfo v) -> {
			return fieldOrderList.indexOf(v.getName());
		}));

	}

	/**
	 * Returns the Constructor for the dataClass. This may be different to what is used in creating the
	 * dataClass as a static constructor might be used instead. getDataConstructor is used to retrieve
	 * the MethodHandle for the correct data constructor.
	 */
	private Constructor<?> getConstructor(Class<?> dataClass)
			throws CodeAnalysisException, NoSuchMethodException, SecurityException {
		Constructor<?>[] constructors = dataClass.getConstructors();

		// only one custructor. this must be it.
		if (constructors.length == 1) {
			return constructors[0];
		}

		// Does it have an annotation?
		for (Constructor<?> constructor : constructors) {
			Record dataAnnotation = constructor
					.getAnnotation(Record.class);
			if (dataAnnotation != null) {
				return constructor;
			}
		}

		// look for a static constructor.
		Method[] methods = dataClass.getMethods();
		for (Method method : methods) {
			if (Modifier.isStatic(method.getModifiers())) {
				Record dataAnnotation = method
						.getAnnotation(Record.class);
				if (dataAnnotation != null) {
					// There must be a matching constructor that matches the parameters of the
					// static constructor.
					return dataClass.getConstructor(method.getParameterTypes());
				}
			}
		}

		throw new CodeAnalysisException("Could not find constructor: " + dataClass);

	}

	private MethodHandle getDataConstructor(Class<?> dataClass)
			throws CodeAnalysisException, NoSuchMethodException, SecurityException, IllegalAccessException {
		Constructor<?>[] constructors = dataClass.getConstructors();

		// only one custructor. this must be it.
		if (constructors.length == 1) {
			return MethodHandles.publicLookup().unreflectConstructor(constructors[0]);
		}

		// Does it have an annotation?
		for (Constructor<?> constructor : constructors) {
			Record dataAnnotation = constructor
					.getAnnotation(Record.class);
			if (dataAnnotation != null) {
				return MethodHandles.publicLookup().unreflectConstructor(constructor);
			}
		}

		// look for a static constructor.
		Method[] methods = dataClass.getMethods();
		for (Method method : methods) {
			if (Modifier.isStatic(method.getModifiers())) {
				Record dataAnnotation = method
						.getAnnotation(Record.class);
				if (dataAnnotation != null) {
					return MethodHandles.publicLookup().unreflect(method);
				}
			}
		}

		throw new CodeAnalysisException("Could not find constructor");

	}

	/**
	 * Creates a single MethodHandle that both constructs an object and sets any setters using a single
	 * Object[] as input. Fields are passed in through the real constructor or through the setters if
	 * present.
	 */
	private MethodHandle createTupleConstructor(Class<?> dataClass, List<ComponentInfo> fields,
			DataClassField[] dataFields, MethodHandle dataConstructor)
			throws IllegalAccessException, NoSuchMethodException, CodeAnalysisException {

		// (Object[]):serialClass -> ctor(Object[])
		MethodHandle create = createEmbedConstructor(dataClass, dataConstructor, fields, dataFields);

		// (serialClass, Object[]) -> serialClass.setValues(Object[x]);
		MethodHandle setters = createEmbedSetters(dataClass, fields, dataFields);

		// (Object[], Object[]) -> ctor(Object[]).setvalues(Object[])
		MethodHandle createAndSet = MethodHandles.collectArguments(setters, 0, create);

		// (Object[]):dataObject
		int[] permuteInput = new int[2];

        return MethodHandles.permuteArguments(createAndSet,
                MethodType.methodType(dataClass, Object[].class), permuteInput);
	}

	/**
	 * Builds a constructor that takes Object[] as constructor arguments and return an object instance.
	 * Passes relevant fields into the data class constructor.
	 */
	private MethodHandle createEmbedConstructor(Class<?> dataClass, MethodHandle dataConstructor,
			List<ComponentInfo> fields, DataClassField[] dataFields)
			throws NoSuchMethodException, IllegalAccessException, CodeAnalysisException {
		MethodHandle result = dataConstructor;

		for (int x = 0; x < fields.size(); x++) {
			ComponentInfo field = fields.get(x);

			if (field.getConstructorArgument() >= 0) {

				int arg = field.getConstructorArgument();

                // (values[],int) -> values[int]
				MethodHandle arrayGetter = MethodHandles.arrayElementGetter(Object[].class);

				// () -> inputIndex
				MethodHandle index = MethodHandles.constant(int.class, x);

				// (values[]) -> values[inputIndex] and maybe additional modifications to the value.
				MethodHandle arrayIndexGetter = dataArrayToObject(field, dataFields[x], arrayGetter, index);

				// ()-> constructor( ..., values[inputIndex] , ... )
				result = MethodHandles.collectArguments(result, arg, arrayIndexGetter);
			}
		}

		// spread the arguments so ctor(Object[],Object[]...) becomes ctor(Object[])
		int paramCount = dataConstructor.type().parameterCount();
		if (paramCount > 0) {
			int[] permuteInput = new int[paramCount];
			result = MethodHandles.permuteArguments(result, MethodType.methodType(dataClass, Object[].class),
					permuteInput);
		} else {

			result = MethodHandles.dropArguments(result, 0, Object[].class);
		}
		return result;
	}

	/**
	 * Creates a MethodHandle that takes an Object[] as input and calls any field setters for mutable objects.
	 */
	private MethodHandle createEmbedSetters(Class<?> dataClass, List<ComponentInfo> fields,
			DataClassField[] dataFields)
			throws IllegalAccessException, NoSuchMethodException, SecurityException, CodeAnalysisException {

		// (dataClass,Object[]):dataClass -> return object;
		MethodHandle identity = MethodHandles.identity(dataClass);

		// (dataClass, Object[]):dataClass -> return dataClass;
		MethodHandle result = MethodHandles.dropArguments(identity, 1, Object[].class);

		for (int x = 0; x < fields.size(); x++) {

			ComponentInfo field = fields.get(x);

            if (field.getWriteMethod() != null && field.getConstructorArgument() < 0) {

				// (obj, value):void -> obj.setField( value );
				MethodHandle fieldSetter = field.getWriteMethod();

				// (value[],x):Object -> value[x]
				MethodHandle arrayGetter = MethodHandles.arrayElementGetter(Object[].class);

				// ():int -> inputIndex
				MethodHandle index = MethodHandles.constant(int.class, x);

				// (values[]) -> values[inputIndex] and maybe additional modifications to the value.
				MethodHandle arrayIndexGetter = dataArrayToObject(field, dataFields[x], arrayGetter, index);

				// (obj, value[]):void -> obj.setField( value[inputIndex] );
				MethodHandle arrayFieldSetter = MethodHandles.collectArguments(fieldSetter, 1, arrayIndexGetter);

				// add to list of setters.
				result = MethodHandles.foldArguments(result, arrayFieldSetter);
			}
		}

		return result;
	}

	/**
	 * The constructor uses an Object[] as the carrier to either call a constructor and/or call the
	 * required setters of an object. Some field types such as bridges, Optional, OptionalInt requires
	 * special handling to prepare the value prior to calling the constructor or setter. This makes the
	 * required adaptations.
	 *
	 * @param field The field information that needs to be adapted. Includes reflection information
	 * @param dataField The DataClassField that has the data field information.
	 * @param arrayGetter MethodHandle with (value[],x):Object -> value[x]
	 * @param index index of the field
	 * @return MethodHandle with (values[]) -> values[inputIndex]) and can perform additional modifications.
	 */
	private MethodHandle dataArrayToObject(ComponentInfo field, DataClassField dataField, MethodHandle arrayGetter,
			MethodHandle index)
			throws CodeAnalysisException, IllegalAccessException, NoSuchMethodException, SecurityException {

		MethodHandle arrayIndexGetter;

		Field fieldAnnotation = field.getField();

		if (fieldAnnotation != null && fieldAnnotation.bridge() != null
				&& fieldAnnotation.bridge() != DataBridge.class) {

			@SuppressWarnings("rawtypes")
			Class<? extends DataBridge> bridgeClass = fieldAnnotation.bridge();

			ParameterizedType bridgeTypes = (ParameterizedType) bridgeClass.getGenericInterfaces()[0];

			// ToData has one parameter so this should be safe.
			Type bridgeDataType = bridgeTypes.getActualTypeArguments()[0];
			Type bridgeObjectType = bridgeTypes.getActualTypeArguments()[1];

			Class<?> bridgeDataClass;
			Class<?> bridgeObjectClass;

			if (bridgeDataType instanceof Class) {
				bridgeDataClass = (Class<?>) bridgeDataType;
			} else if (bridgeDataType instanceof ParameterizedType) {
				bridgeDataClass = (Class<?>) ((ParameterizedType) bridgeDataType).getRawType();
			} else {
				throw new CodeAnalysisException("Unrecognised Type");
			}

			if (bridgeObjectType instanceof Class) {
				bridgeObjectClass = (Class<?>) bridgeObjectType;
			} else if (bridgeObjectType instanceof ParameterizedType) {
				bridgeObjectClass = (Class<?>) ((ParameterizedType) bridgeObjectType).getRawType();
			} else {
				throw new CodeAnalysisException("Unrecognised Type");
			}

			@SuppressWarnings("rawtypes")
			DataBridge bridge;
			try {
				bridge = bridgeClass.getConstructor().newInstance();
			} catch (InstantiationException | IllegalArgumentException | InvocationTargetException e) {
				throw new CodeAnalysisException("Failed to instantiate bridge", e);
			}

			// (values[]) -> values[inputIndex]:optionalType
			arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(bridgeDataClass, Object[].class));

			// bridge.toObject(type):dataType
			MethodHandle bridgeToObject = MethodHandles.publicLookup().findVirtual(bridgeClass, TOOBJECT_METHOD,
					MethodType.methodType(bridgeObjectClass, bridgeDataClass)).bindTo(bridge);

			bridgeToObject = bridgeToObject.asType(bridgeToObject.type().changeReturnType(field.getType()));

			// (values[]):bridgeType -> bridge.toObject( (bridgeData) values[inputIndex] ):bridgeObject
			arrayIndexGetter = MethodHandles.collectArguments(bridgeToObject, 0, arrayIndexGetter);

		} else if (field.getType() == Optional.class && field.getParamType() != null) {

			Class<?> optionalType = (Class<?>) field.getParamType().getActualTypeArguments()[0];

			// (values[]) -> values[inputIndex]:optionalType
			arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(optionalType, Object[].class));

			// (value) -> Optional.ofNullable(value);
			MethodHandle optionalToObject = MethodHandles.publicLookup()
					.findStatic(Optional.class, "ofNullable", MethodType.methodType(Optional.class, Object.class))
					.asType(MethodType.methodType(Optional.class, optionalType));

			// (values[]):Optional<fieldType> -> bridge.toObject( values[inputIndex] ):Optional
			arrayIndexGetter = MethodHandles.collectArguments(optionalToObject, 0, arrayIndexGetter);
			// .asType(MethodType.methodType(field.getType(), Object[].class));
		} else if (field.getType() == OptionalInt.class
				|| field.getType() == OptionalLong.class | field.getType() == OptionalDouble.class) {

			// This is one of those ugly edge cases that is not supported by MethodHandles. OptionalInt type
			// optional primitives need to be unboxed from Integer for the constructor.
			// This requires a MethodHandle that looks as follows:
			// if (Object.nonNull(Integer)) { return OptionalInt.of( Integer.intValue(i) ) } else { return
			// OptionalInt.empty() }

			// This will be one of OptionalInt, OptionalLong or OptionalDouble.
			Class<?> optionalClass = field.getType();

			// work out which Optional class this is by getting return type of orElseThrow.
			Class<?> optionalType = optionalClass.getDeclaredMethod("orElseThrow").getReturnType();

			Class<?> optionalNullableType = null;
			if (optionalType == int.class) {
				optionalNullableType = Integer.class;
			} else if (optionalType == long.class) {
				optionalNullableType = Long.class;
			} else if (optionalType == double.class) {
				optionalNullableType = Double.class;
			}

			// Get data class for int, long or double.
			// DataClass optionalDataClass = context.getDescriptor(optionalType);

			// (values[]):optionalType -> values[inputIndex]:optionalType
			MethodHandle arrayGet = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(optionalNullableType, Object[].class));

			// (value):boolean -> Objects.nonNull(value)
			MethodHandle checkNonNull = MethodHandles.publicLookup()
					.findStatic(Objects.class, "nonNull", MethodType.methodType(boolean.class, Object.class))
					.asType(MethodType.methodType(boolean.class, optionalNullableType));

			// (values[]):boolean -> Objects.nonNull(values[index])
			checkNonNull = MethodHandles.collectArguments(checkNonNull, 0, arrayGet);

			// (int):OptionalInt -> OptionalInt.of(int);
			MethodHandle optionalIntOf = MethodHandles.publicLookup().findStatic(optionalClass, "of",
					MethodType.methodType(optionalClass, optionalType));

			// (Integer):int -> Integer.intValue()
			MethodHandle integerIntValue = MethodHandles.publicLookup().findVirtual(optionalNullableType,
					optionalType.getSimpleName() + "Value", MethodType.methodType(optionalType));

			// (Integer):OptionalInt -> OptionalInt.of( Integer.intValue(i) )
			optionalIntOf = MethodHandles.collectArguments(optionalIntOf, 0, integerIntValue);

			// (values[]):OptionalInt -> OptionalInt.of( Integer.intValue(values[inputIndex]) )
			optionalIntOf = MethodHandles.collectArguments(optionalIntOf, 0, arrayGet);

			// ():OptionalInt -> OptionalInt.empty()
			MethodHandle optionalIntEmpty = MethodHandles.publicLookup().findStatic(optionalClass, "empty",
					MethodType.methodType(optionalClass));

			// (Object[]):OptionalInt -> OptionalInt.empty()
			optionalIntEmpty = MethodHandles.dropArguments(optionalIntEmpty, 0, Object[].class);

			// (values[]):optionalType -> if (Object.nonNull(Integer)) { return OptionalInt.of(
			// Integer.intValue(i) ) } else { return OptionalInt.empty() }
			arrayIndexGetter = MethodHandles.guardWithTest(checkNonNull, optionalIntOf, optionalIntEmpty);

		} else if (field.getUnion() != null) {
			// (values[]):fieldType -> values[inputIndex]
			arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(field.getType(), Object[].class));

			DataClassUnion dataUnion = (DataClassUnion) dataField.dataClass();

			// (values[]):fieldType -> dataUnion.checkIsMember( values[inputIndex] )
			MethodHandle unionCheck = MethodHandles.publicLookup().findVirtual(dataUnion.getClass(), "checkIsMember",
					MethodType.methodType(Object.class, Object.class)).bindTo(dataUnion);

			arrayIndexGetter = MethodHandles.filterReturnValue(arrayIndexGetter, unionCheck);

		} else {
			// (values[]):fieldType -> values[inputIndex]
			arrayIndexGetter = MethodHandles.collectArguments(arrayGetter, 1, index)
					.asType(MethodType.methodType(field.getType(), Object[].class));
		}

		return arrayIndexGetter;

	}

}
