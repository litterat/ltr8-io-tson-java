/*
 * Copyright (c) 2020, Litterat Pty Ltd. All Rights Reserved.
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
package io.ltr8.test.bind;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataBindException;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.mapper.ArrayMapper;
import io.ltr8.bind.mapper.MapMapper;
import io.ltr8.test.data.ArrayPrimitiveTypeTestPojo;

public class ArrayPrimitiveTypeTestPojoTest {

	private final static boolean[] pBoolean = new boolean[] { false };
	private final static Boolean[] oBoolean = new Boolean[] { Boolean.TRUE };
	private final static byte[] pByte = new byte[] { 1 };
	private final static Byte[] oByte = new Byte[] { Byte.valueOf((byte) 2) };
	private final static char[] pChar = new char[] { 3 };
	private final static Character[] oChar = new Character[] { Character.valueOf((char) 103) };
	private final static short[] pShort = new short[] { 4 };
	private final static Short[] oShort = new Short[] { Short.valueOf((short) 5) };
	private final static int[] pInteger = new int[] { 42 };
	private final static Integer[] oInteger = new Integer[] { Integer.valueOf(6) };
	private final static long[] pLong = new long[] { 7 };
	private final static Long[] oLong = new Long[] { Long.valueOf(10l) };
	private final static float[] pFloat = new float[] { 12.42f };
	private final static Float[] oFloat = new Float[] { Float.valueOf(13.54f) };
	private final static double[] pDouble = new double[] { 56.34d };
	private final static Double[] oDouble = new Double[] { Double.valueOf(123.456d) };
	private final static String[] string = new String[] { "test" };

	final static ArrayPrimitiveTypeTestPojo test = new ArrayPrimitiveTypeTestPojo();

	static {
		test.setpBoolean(pBoolean);
		test.setoBoolean(oBoolean);
		test.setpByte(pByte);
		test.setoByte(oByte);
		test.setpChar(pChar);
		test.setoChar(oChar);
		test.setpShort(pShort);
		test.setoShort(oShort);
		test.setpInteger(pInteger);
		test.setoInteger(oInteger);
		test.setpLong(pLong);
		test.setoLong(oLong);
		test.setpFloat(pFloat);
		test.setoFloat(oFloat);
		test.setpDouble(pDouble);
		test.setoDouble(oDouble);
		test.setString(string);
	}

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {

		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(ArrayPrimitiveTypeTestPojo.class);
		Assertions.assertNotNull(descriptor);

		Assertions.assertEquals(ArrayPrimitiveTypeTestPojo.class, descriptor.typeClass());
		//Assertions.assertEquals(ArrayPrimitiveTypeTestPojo.class, descriptor.dataClass());

		DataClassField[] fields = descriptor.fields();
		Assertions.assertNotNull(fields);
		Assertions.assertEquals(17, fields.length);

		// For POJO these fields are in alpha order.

		DataClassField fieldOBoolean = fields[0];
		Assertions.assertEquals("oBoolean", fieldOBoolean.name());
		Assertions.assertEquals(Boolean[].class, fieldOBoolean.type());
		Assertions.assertEquals(false, fieldOBoolean.isRequired());

		DataClassField fieldOByte = fields[1];
		Assertions.assertEquals("oByte", fieldOByte.name());
		Assertions.assertEquals(Byte[].class, fieldOByte.type());
		Assertions.assertEquals(false, fieldOByte.isRequired());

		DataClassField fieldOChar = fields[2];
		Assertions.assertEquals("oChar", fieldOChar.name());
		Assertions.assertEquals(Character[].class, fieldOChar.type());
		Assertions.assertEquals(false, fieldOChar.isRequired());

		DataClassField fieldODouble = fields[3];
		Assertions.assertEquals("oDouble", fieldODouble.name());
		Assertions.assertEquals(Double[].class, fieldODouble.type());
		Assertions.assertEquals(false, fieldODouble.isRequired());

		DataClassField fieldOFloat = fields[4];
		Assertions.assertEquals("oFloat", fieldOFloat.name());
		Assertions.assertEquals(Float[].class, fieldOFloat.type());
		Assertions.assertEquals(false, fieldOFloat.isRequired());

		DataClassField fieldOInteger = fields[5];
		Assertions.assertEquals("oInteger", fieldOInteger.name());
		Assertions.assertEquals(Integer[].class, fieldOInteger.type());
		Assertions.assertEquals(false, fieldOInteger.isRequired());

		DataClassField fieldOLong = fields[6];
		Assertions.assertEquals("oLong", fieldOLong.name());
		Assertions.assertEquals(Long[].class, fieldOLong.type());
		Assertions.assertEquals(false, fieldOLong.isRequired());

		DataClassField fieldOShort = fields[7];
		Assertions.assertEquals("oShort", fieldOShort.name());
		Assertions.assertEquals(Short[].class, fieldOShort.type());
		Assertions.assertEquals(false, fieldOShort.isRequired());

		DataClassField fieldPBoolean = fields[8];
		Assertions.assertEquals("pBoolean", fieldPBoolean.name());
		Assertions.assertEquals(boolean[].class, fieldPBoolean.type());
		Assertions.assertEquals(false, fieldOBoolean.isRequired());

		DataClassField fieldPByte = fields[9];
		Assertions.assertEquals("pByte", fieldPByte.name());
		Assertions.assertEquals(byte[].class, fieldPByte.type());
		Assertions.assertEquals(false, fieldPByte.isRequired());

		DataClassField fieldPChar = fields[10];
		Assertions.assertEquals("pChar", fieldPChar.name());
		Assertions.assertEquals(char[].class, fieldPChar.type());
		Assertions.assertEquals(false, fieldPChar.isRequired());

		DataClassField fieldPDouble = fields[11];
		Assertions.assertEquals("pDouble", fieldPDouble.name());
		Assertions.assertEquals(double[].class, fieldPDouble.type());
		Assertions.assertEquals(false, fieldPDouble.isRequired());

		DataClassField fieldPFloat = fields[12];
		Assertions.assertEquals("pFloat", fieldPFloat.name());
		Assertions.assertEquals(float[].class, fieldPFloat.type());
		Assertions.assertEquals(false, fieldPFloat.isRequired());

		DataClassField fieldPInteger = fields[13];
		Assertions.assertEquals("pInteger", fieldPInteger.name());
		Assertions.assertEquals(int[].class, fieldPInteger.type());
		Assertions.assertEquals(false, fieldPInteger.isRequired());

		DataClassField fieldPLong = fields[14];
		Assertions.assertEquals("pLong", fieldPLong.name());
		Assertions.assertEquals(long[].class, fieldPLong.type());
		Assertions.assertEquals(false, fieldPLong.isRequired());

		DataClassField fieldPShort = fields[15];
		Assertions.assertEquals("pShort", fieldPShort.name());
		Assertions.assertEquals(short[].class, fieldPShort.type());
		Assertions.assertEquals(false, fieldPShort.isRequired());

		DataClassField fieldString = fields[16];
		Assertions.assertEquals("string", fieldString.name());
		Assertions.assertEquals(String[].class, fieldString.type());
		Assertions.assertEquals(false, fieldString.isRequired());

	}

	@Test
	public void testToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);

		// write to array.
		Object[] values = arrayMap.toArray(test);

		// convert to object.
		ArrayPrimitiveTypeTestPojo object = arrayMap.toObject(ArrayPrimitiveTypeTestPojo.class, values);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof ArrayPrimitiveTypeTestPojo);

		Assertions.assertTrue(Arrays.equals(pBoolean, object.getpBoolean()));
		Assertions.assertTrue(Arrays.equals(oBoolean, object.getoBoolean()));
		Assertions.assertTrue(Arrays.equals(pByte, object.getpByte()));
		Assertions.assertTrue(Arrays.equals(oByte, object.getoByte()));
		Assertions.assertTrue(Arrays.equals(pChar, object.getpChar()));
		Assertions.assertTrue(Arrays.equals(oChar, object.getoChar()));
		Assertions.assertTrue(Arrays.equals(pShort, object.getpShort()));
		Assertions.assertTrue(Arrays.equals(oShort, object.getoShort()));
		Assertions.assertTrue(Arrays.equals(pInteger, object.getpInteger()));
		Assertions.assertTrue(Arrays.equals(oInteger, object.getoInteger()));
		Assertions.assertTrue(Arrays.equals(pLong, object.getpLong()));
		Assertions.assertTrue(Arrays.equals(oLong, object.getoLong()));
		Assertions.assertTrue(Arrays.equals(pFloat, object.getpFloat()));
		Assertions.assertTrue(Arrays.equals(oFloat, object.getoFloat()));
		Assertions.assertTrue(Arrays.equals(pDouble, object.getpDouble()));
		Assertions.assertTrue(Arrays.equals(oDouble, object.getoDouble()));
		Assertions.assertTrue(Arrays.equals(string, object.getString()));

	}

	@Test
	public void testToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		ArrayPrimitiveTypeTestPojo object = (ArrayPrimitiveTypeTestPojo) mapMapper
				.toObject(ArrayPrimitiveTypeTestPojo.class, map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof ArrayPrimitiveTypeTestPojo);

		Assertions.assertTrue(Arrays.equals(pBoolean, object.getpBoolean()));
		Assertions.assertTrue(Arrays.equals(oBoolean, object.getoBoolean()));
		Assertions.assertTrue(Arrays.equals(pByte, object.getpByte()));
		Assertions.assertTrue(Arrays.equals(oByte, object.getoByte()));
		Assertions.assertTrue(Arrays.equals(pChar, object.getpChar()));
		Assertions.assertTrue(Arrays.equals(oChar, object.getoChar()));
		Assertions.assertTrue(Arrays.equals(pShort, object.getpShort()));
		Assertions.assertTrue(Arrays.equals(oShort, object.getoShort()));
		Assertions.assertTrue(Arrays.equals(pInteger, object.getpInteger()));
		Assertions.assertTrue(Arrays.equals(oInteger, object.getoInteger()));
		Assertions.assertTrue(Arrays.equals(pLong, object.getpLong()));
		Assertions.assertTrue(Arrays.equals(oLong, object.getoLong()));
		Assertions.assertTrue(Arrays.equals(pFloat, object.getpFloat()));
		Assertions.assertTrue(Arrays.equals(oFloat, object.getoFloat()));
		Assertions.assertTrue(Arrays.equals(pDouble, object.getpDouble()));
		Assertions.assertTrue(Arrays.equals(oDouble, object.getoDouble()));
		Assertions.assertTrue(Arrays.equals(string, object.getString()));

	}

	@Test
	public void testMapToObjectException() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		// corrupting the map by putting an invalid value for x.
		map.put("pBoolean", "error");

		Assertions.assertThrows(DataBindException.class, () -> {
			mapMapper.toObject(ArrayPrimitiveTypeTestPojo.class, map);
		});

	}
}
