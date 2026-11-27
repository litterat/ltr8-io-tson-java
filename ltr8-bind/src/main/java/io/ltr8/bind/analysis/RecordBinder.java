package io.ltr8.bind.analysis;

import io.ltr8.annotation.Annotations;
import io.ltr8.annotation.ToData;
import io.ltr8.annotation.Union;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassBridge;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataClassUnion;
import io.ltr8.bind.analysis.RecordFields.FieldBridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * In a code-first schema, the record binder creates the DataClassRecord and from that information
 * return the Record definition. The binder registers the DataClassRecord to the TypeContext, and
 * the caller adds the definition to the TypeLibrary.
 */
public class RecordBinder {

	public RecordBinder() {
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
		return resolveRecord(context, targetClass, targetClass);
	}

	/**
	 * {@link #resolveRecord(DataBindContext, Class)} at {@code parameterizedType}: a generic class's components are
	 * described with its type parameters replaced by the arguments given there, so {@code Box<T>(List<T> items)}
	 * described as {@code Box<String>} has a {@code List<String>} component. A raw or unparameterised class keeps
	 * its components as declared.
	 */
	public DataClassRecord resolveRecord(DataBindContext context, Class<?> targetClass, Type parameterizedType)
			throws DataBindException {
		try {

			DataClassBridge bridge = null;
			if (ToData.class.isAssignableFrom(targetClass)) {

				bridge = ToDataBridges.resolveBridge(targetClass);

				DataClassRecord descriptor = resolveClassRecord(context, bridge.dataClass());

				return new DataClassRecord(targetClass, bridge, descriptor.isMutable(), descriptor.creator(),
						descriptor.constructor(), descriptor.fields(),
						descriptor.annotationsCarrier().orElse(null));
			} else {

				DataClassRecord descriptor = resolveClassRecord(context, targetClass, parameterizedType);

				return new DataClassRecord(descriptor.typeClass(), bridge, descriptor.isMutable(), descriptor.creator(),
						descriptor.constructor(), descriptor.fields(),
						descriptor.annotationsCarrier().orElse(null));
			}
		} catch (IllegalAccessException | SecurityException e) {
			throw new DataBindException(String.format(
					"%s is bound through a member that is not accessible: %s",
					targetClass.getName(), e.getMessage()), e);
		} catch (NoSuchMethodException e) {
			throw new DataBindException(String.format(
					"%s is bound through %s, which is not a public constructor or method",
					targetClass.getName(), e.getMessage()), e);
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
		return resolveClassRecord(context, targetClass, targetClass);
	}

	private DataClassRecord resolveClassRecord(DataBindContext context, Class<?> targetClass, Type parameterizedType)
			throws IllegalAccessException, NoSuchMethodException, SecurityException, DataBindException {
		// get the constructor.
		Constructor<?> ctor = RecordConstructors.getConstructor(targetClass, context.profile().orElse(null));
		List<ComponentInfo> components = RecordComponents.find(targetClass, parameterizedType, ctor);
		int[] slots = RecordComponents.slots(components);

		DataClassField[] allComponents = new DataClassField[components.size()];
		FieldBridge[] bridges = new FieldBridge[components.size()];
		List<DataClassField> boundComponents = new ArrayList<>();
		DataClassField annotationsCarrier = null;
		int annotationsCarrierCount = 0;
		for (int x = 0; x < components.size(); x++) {
			ComponentInfo info = components.get(x);
			// Never read from a document nor written to one, so nothing needs its descriptor or its bridge.
			if (info.isUnbound()) {
				continue;
			}

			bridges[x] = RecordFields.fieldBridge(targetClass, info);
			allComponents[x] = RecordFields.resolveField(context, targetClass, info, bridges[x], slots[x]);
			boundComponents.add(allComponents[x]);
			if (info.getType() == Annotations.class) {
				annotationsCarrier = allComponents[x];
				annotationsCarrierCount++;
			}
		}
		DataClassField[] dataComponents = boundComponents.toArray(new DataClassField[0]);
		if (annotationsCarrierCount > 1) {
			throw new CodeAnalysisException("At most one component may have type " + Annotations.class.getName()
					+ ", the carrier for this value's own wire-format annotations -- found "
					+ annotationsCarrierCount + " on " + targetClass);
		}

		MethodHandle dataConstructor = MethodHandles.publicLookup().unreflectConstructor(ctor);

		// Build a MethodHandle that creates object and also calls setters with order of
		// fields as defined in components.
		MethodHandle constructor = RecordCreators.createTupleConstructor(targetClass, components, allComponents, bridges, slots,
				dataConstructor);

		// See if there's an empty constructor available.
		MethodHandle creator = null;
		try {
			Constructor<?> creatorCtor = targetClass.getConstructor();
			creator = MethodHandles.publicLookup().unreflectConstructor(creatorCtor);
		} catch (NoSuchMethodException e) {
			// ignore.
		}

		return new DataClassRecord(targetClass, null, false, creator, constructor, dataComponents,
				annotationsCarrier);
	}

	/**
	 * Adds the class {@code described} describes to each open union it implements or extends: an interface it
	 * declares, or an abstract super class, carrying {@code @Union} with no members listed and not sealed. Only a
	 * record described by its own components joins, not one bridged through {@code ToData} or {@code @Transparent}.
	 *
	 * <p>Called by {@link DataBindContext} once {@code described} is part of a component that resolved whole, and
	 * not by {@link #resolveRecord}: a class on a cycle is described only if every class on the cycle is, so a
	 * class joining while its own fields resolve stays listed after a class it reaches is refused.
	 */
	public static void joinUnions(DataBindContext context, DataClass described) {
		if (!(described instanceof DataClassRecord record) || record.bridge().isPresent()) {
			return;
		}
		Class<?> member = record.typeClass();
		for (Class<?> declared : member.getInterfaces()) {
			joinUnion(context, declared, member);
		}
		for (Class<?> superClass = member.getSuperclass(); superClass != null;
				superClass = superClass.getSuperclass()) {
			if (Modifier.isAbstract(superClass.getModifiers())) {
				joinUnion(context, superClass, member);
			}
		}
	}

	private static void joinUnion(DataBindContext context, Class<?> union, Class<?> member) {
		Union annotation = union.getAnnotation(Union.class);
		if (annotation == null || annotation.value().length > 0 || union.isSealed()) {
			return;
		}
		try {
			if (context.getDescriptor(union) instanceof DataClassUnion open) {
				open.addMemberType(member);
			}
		} catch (DataBindException e) {
			// A union this context cannot describe, or one that admits no additions, has no members for this
			// class to join.
		}
	}

}
