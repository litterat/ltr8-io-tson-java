package io.ltr8.bind.analysis;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassMap;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Resolves a {@link Map} target into a {@link DataClassMap}, the same role {@link
 * DefaultArrayBinder} plays for arrays/collections. Kept as a separate binder rather than folded
 * into {@link DefaultArrayBinder} because a map's shape is genuinely different -- keyed entries,
 * not a single sequential element type -- not because the resolution steps (parameterized-type
 * extraction, MethodHandle assembly) happen to look similar.
 */
public class DefaultMapBinder {

	public DefaultMapBinder() {

	}

	public DataClassMap resolveMap(DataBindContext context, Class<?> targetClass, Type parameterizedType)
			throws DataBindException {
		DataClassMap descriptor;

		try {

			// Type erasure means the key/value types are only available via the parameterized
			// type, the same reason DefaultArrayBinder needs it for a Collection's element type.
			if (!(parameterizedType instanceof ParameterizedType)) {
				throw new CodeAnalysisException("Map must provide parameterized type information");
			}

			Type[] typeArguments = ((ParameterizedType) parameterizedType).getActualTypeArguments();
			DataClass keyDataClass = resolveTypeArgument(context, typeArguments[0]);
			DataClass valueDataClass = resolveTypeArgument(context, typeArguments[1]);

			// Produces the MethodHandles for the DataClassMap.
			MapAccessBridge mapBridge = new MapAccessBridge(targetClass);

			descriptor = new DataClassMap(targetClass, keyDataClass, valueDataClass, mapBridge.constructor(),
					mapBridge.size(), mapBridge.iterator(), mapBridge.next(), mapBridge.key(), mapBridge.value(),
					mapBridge.put());

		} catch (IllegalAccessException | NoSuchMethodException | SecurityException e) {
			throw new CodeAnalysisException("Failed to get map descriptor", e);
		}

		return descriptor;
	}

	private static DataClass resolveTypeArgument(DataBindContext context, Type typeArgument)
			throws DataBindException {
		if (typeArgument instanceof Class) {
			return context.getDescriptor((Class<?>) typeArgument);
		} else if (typeArgument instanceof ParameterizedType) {
			ParameterizedType parameterizedTypeArgument = (ParameterizedType) typeArgument;
			return context.getDescriptor((Class<?>) parameterizedTypeArgument.getRawType(), parameterizedTypeArgument);
		}
		throw new CodeAnalysisException("Unrecognized parameterized type for map key/value");
	}

	/**
	 *
	 * This class is used to generate the MethodHandle collection for the DataClassMap type. See
	 * DataClassMap for how to interact using the MethodHandles.
	 *
	 */
	private static class MapAccessBridge {

		private static final Map<Class<?>, Class<?>> mapInterfaces = new HashMap<>();

		static {
			mapInterfaces.put(Map.class, HashMap.class);
		}

		private final Class<?> targetClass;

		public MapAccessBridge(Class<?> targetClass) {
			this.targetClass = targetClass;
		}

		/**
		 * @return A MethodHandle that accepts an integer capacity hint and returns a new empty
		 *         targetClass instance. constructor( int ):Map;
		 */
		public MethodHandle constructor() throws IllegalAccessException, NoSuchMethodException, CodeAnalysisException {
			// If a field uses a non-specific interface type such as Map there's no way of
			// knowing the implementation expected -- same limitation DefaultArrayBinder has for
			// a bare List/Set field, and the same fallback: a default concrete type.
			Class<?> implementationClass = mapInterfaces.getOrDefault(targetClass, targetClass);
			return MethodHandles.lookup().unreflectConstructor(implementationClass.getConstructor(int.class));
		}

		/**
		 * @return a MethodHandle that returns the number of entries in the map. size( Map ):int;
		 */
		public MethodHandle size() throws NoSuchMethodException, IllegalAccessException {
			return MethodHandles.publicLookup().findVirtual(targetClass, "size", MethodType.methodType(int.class));
		}

		/**
		 * @return a MethodHandle that returns {@code map.entrySet().iterator()}. iterator( Map
		 *         ):Iterator;
		 */
		public MethodHandle iterator() throws NoSuchMethodException, IllegalAccessException {
			// (Map):Set
			MethodHandle entrySet = MethodHandles.publicLookup().findVirtual(targetClass, "entrySet",
					MethodType.methodType(Set.class));

			// (Set):Iterator
			MethodHandle setIterator = MethodHandles.publicLookup().findVirtual(Set.class, "iterator",
					MethodType.methodType(Iterator.class));

			// (Map):Iterator
			return MethodHandles.filterReturnValue(entrySet, setIterator);
		}

		/**
		 * Returns a MethodHandle that advances the given entry-set iterator and returns the next
		 * {@link Map.Entry}, or {@code null} once exhausted -- equivalent to
		 * {@code iterator.hasNext() ? iterator.next() : null}. Two entries are never returned for
		 * one advance the way {@link DefaultArrayBinder}'s single {@code get} does for an element;
		 * a map entry carries a key *and* a value, so the caller extracts both from the one
		 * returned entry via {@link #key()}/{@link #value()} instead.
		 *
		 * @return next( Iterator ):Entry;
		 */
		public MethodHandle next() throws NoSuchMethodException, IllegalAccessException {
			// (Iterator):boolean
			MethodHandle hasNext = MethodHandles.publicLookup().findVirtual(Iterator.class, "hasNext",
					MethodType.methodType(boolean.class));

			// (Iterator):Object -- the next Map.Entry.
			MethodHandle next = MethodHandles.publicLookup().findVirtual(Iterator.class, "next",
					MethodType.methodType(Object.class));

			// ():null
			MethodHandle noResult = MethodHandles.constant(Object.class, null);

			// (Iterator):null
			MethodHandle returnNull = MethodHandles.dropArguments(noResult, 0, Iterator.class);

			// (Iterator) -> if (iterator.hasNext()) return iterator.next() else return null.
			return MethodHandles.guardWithTest(hasNext, next, returnNull);
		}

		/**
		 * @return a MethodHandle for extracting an entry's key. key( Entry ):key;
		 */
		public MethodHandle key() throws NoSuchMethodException, IllegalAccessException {
			return MethodHandles.publicLookup().findVirtual(Map.Entry.class, "getKey",
					MethodType.methodType(Object.class));
		}

		/**
		 * @return a MethodHandle for extracting an entry's value. value( Entry ):value;
		 */
		public MethodHandle value() throws NoSuchMethodException, IllegalAccessException {
			return MethodHandles.publicLookup().findVirtual(Map.Entry.class, "getValue",
					MethodType.methodType(Object.class));
		}

		/**
		 * @return a MethodHandle for adding an entry to the map. put( Map, key, value ):Object
		 *         (the previous value, if any -- Map.put's own return, not forced to void).
		 */
		public MethodHandle put() throws NoSuchMethodException, IllegalAccessException {
			return MethodHandles.publicLookup().findVirtual(targetClass, "put",
					MethodType.methodType(Object.class, Object.class, Object.class));
		}
	}

}
