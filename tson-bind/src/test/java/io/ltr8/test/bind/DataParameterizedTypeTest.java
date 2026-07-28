package io.ltr8.test.bind;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.test.data.ListOfString;

public class DataParameterizedTypeTest {

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void constructorRejectsNullRawType() throws Throwable {
		Assertions.assertThrows(NullPointerException.class, () -> new DataParameterizedType(null, String.class));
	}

	@Test
	public void constructorRejectsNullTypeArguments() throws Throwable {
		Assertions.assertThrows(NullPointerException.class,
				() -> new DataParameterizedType(List.class, (java.lang.reflect.Type[]) null));
	}

	@Test
	public void accessorsReturnConstructorValues() throws Throwable {
		DataParameterizedType type = new DataParameterizedType(Map.class, String.class, Integer.class);

		Assertions.assertEquals(Map.class, type.getRawType());
		Assertions.assertArrayEquals(new Class<?>[] { String.class, Integer.class }, type.getActualTypeArguments());
		Assertions.assertNull(type.getOwnerType());
	}

	@Test
	public void getActualTypeArgumentsReturnsADefensiveCopy() throws Throwable {
		DataParameterizedType type = new DataParameterizedType(List.class, String.class);

		java.lang.reflect.Type[] first = type.getActualTypeArguments();
		first[0] = Integer.class;

		Assertions.assertEquals(String.class, type.getActualTypeArguments()[0]);
	}

	@Test
	public void nestedParameterizedTypesAreSupported() throws Throwable {
		DataParameterizedType inner = new DataParameterizedType(List.class, Integer.class);
		DataParameterizedType outer = new DataParameterizedType(List.class, inner);

		Assertions.assertEquals(1, outer.getActualTypeArguments().length);
		Assertions.assertEquals(inner, outer.getActualTypeArguments()[0]);
	}

	@Test
	public void equalsAndHashCodeAreConsistentBetweenEqualInstances() throws Throwable {
		DataParameterizedType first = new DataParameterizedType(List.class, String.class);
		DataParameterizedType second = new DataParameterizedType(List.class, String.class);

		Assertions.assertEquals(first, first);
		Assertions.assertEquals(first, second);
		Assertions.assertEquals(first.hashCode(), second.hashCode());
	}

	@Test
	public void equalsRejectsDifferentRawTypeOrTypeArgumentsOrNonParameterizedType() throws Throwable {
		DataParameterizedType listOfString = new DataParameterizedType(List.class, String.class);

		Assertions.assertNotEquals(listOfString, new DataParameterizedType(Map.class, String.class));
		Assertions.assertNotEquals(listOfString, new DataParameterizedType(List.class, Integer.class));
		Assertions.assertNotEquals(listOfString, "not a ParameterizedType");
		Assertions.assertNotEquals(listOfString, null);
	}

	// ListOfString#list is a real, compiler-generated ParameterizedType (List<String>) --
	// interoperating with one of these, not just with another DataParameterizedType, is the whole
	// reason this class exists (DataBindContext#descriptors is keyed by java.lang.reflect.Type).
	@Test
	public void equalsAndHashCodeMatchARealReflectedParameterizedType() throws Throwable {
		Field listField = ListOfString.class.getDeclaredField("list");
		ParameterizedType reflected = (ParameterizedType) listField.getGenericType();

		DataParameterizedType handBuilt = new DataParameterizedType(List.class, String.class);

		Assertions.assertEquals(handBuilt, reflected);
		Assertions.assertEquals(reflected.hashCode(), handBuilt.hashCode());
	}

	@Test
	public void toStringFormatsRawTypeAndTypeArguments() throws Throwable {
		Assertions.assertEquals("java.util.List<java.lang.String>",
				new DataParameterizedType(List.class, String.class).toString());
		Assertions.assertEquals("java.util.Map<java.lang.String, java.lang.Integer>",
				new DataParameterizedType(Map.class, String.class, Integer.class).toString());
		Assertions.assertEquals("java.util.List", new DataParameterizedType(List.class).toString());
	}

	// The actual point of this class: DataBindContext#getDescriptor(Class, Type) needs a real
	// ParameterizedType to recover element/key/value type info past erasure -- confirm it genuinely
	// drives DataClassArray/DataClassMap's own element/key/value descriptors, not just that it
	// satisfies the ParameterizedType interface in isolation.
	@Test
	public void drivesArrayElementTypeResolutionInDataBindContext() throws Throwable {
		DataClass descriptor = context.getDescriptor(List.class, new DataParameterizedType(List.class, String.class));

		Assertions.assertTrue(descriptor instanceof DataClassArray);
		Assertions.assertEquals(String.class, ((DataClassArray) descriptor).arrayDataClass().typeClass());
	}

	@Test
	public void drivesMapKeyAndValueTypeResolutionInDataBindContext() throws Throwable {
		DataClass descriptor = context.getDescriptor(Map.class,
				new DataParameterizedType(Map.class, String.class, Integer.class));

		Assertions.assertTrue(descriptor instanceof DataClassMap);
		DataClassMap mapDescriptor = (DataClassMap) descriptor;
		Assertions.assertEquals(String.class, mapDescriptor.keyDataClass().typeClass());
		Assertions.assertEquals(Integer.class, mapDescriptor.valueDataClass().typeClass());
	}
}
