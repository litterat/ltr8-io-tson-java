package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.UnmodifiableLinkedList;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class UnmodifiableLinkedListTest {

	final static String TEST_ONE = "one";
	final static String TEST_TWO = "two";
	final static String TEST_THREE = "three";

	List<String> testList = List.of(TEST_ONE, TEST_TWO, TEST_THREE);

	UnmodifiableLinkedList test = new UnmodifiableLinkedList(List.of(TEST_ONE, TEST_TWO, TEST_THREE));

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {

		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(UnmodifiableLinkedList.class);
		Assertions.assertNotNull(descriptor);

		Assertions.assertEquals(UnmodifiableLinkedList.class, descriptor.typeClass());
		//Assertions.assertEquals(UnmodifiableLinkedList.class, descriptor.dataClass());

		DataClassField[] fields = descriptor.fields();
		Assertions.assertNotNull(fields);
		Assertions.assertEquals(1, fields.length);

		DataClassField fieldList = fields[0];
		Assertions.assertEquals("list", fieldList.name());
		Assertions.assertEquals(String[].class, fieldList.type());
        Assertions.assertFalse(fieldList.isRequired());

	}

	@Test
	public void testToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);

		// write to array.
		Object[] values = arrayMap.toArray(test);

		// convert to object.
		UnmodifiableLinkedList object = arrayMap.toObject(UnmodifiableLinkedList.class, values);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof UnmodifiableLinkedList);

		System.out.println(object.list().getClass().getName());

		// No better way to check this.
		Assertions.assertEquals("java.util.Collections$UnmodifiableList", object.list().getClass().getName());

		Assertions.assertEquals(TEST_ONE, object.list().get(0));
		Assertions.assertEquals(TEST_TWO, object.list().get(1));
		Assertions.assertEquals(TEST_THREE, object.list().get(2));

	}

	@Test
	public void testToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		Assertions.assertEquals(true, map.containsKey("list"));
		Object[] listArray = (Object[]) map.get("list");
		System.out.println(Arrays.toString(listArray));

		UnmodifiableLinkedList object = (UnmodifiableLinkedList) mapMapper.toObject(UnmodifiableLinkedList.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof UnmodifiableLinkedList);

		// No better way to check this.
		Assertions.assertEquals("java.util.Collections$UnmodifiableList", object.list().getClass().getName());

		Assertions.assertEquals(TEST_ONE, object.list().get(0));
		Assertions.assertEquals(TEST_TWO, object.list().get(1));
		Assertions.assertEquals(TEST_THREE, object.list().get(2));
	}

	@Test
	public void testMapToObjectException() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		// corrupting the map by putting an invalid value for x.
		map.put("list", "error");

		Assertions.assertThrows(DataBindException.class, () -> {
			mapMapper.toObject(UnmodifiableLinkedList.class, map);
		});

	}
}
