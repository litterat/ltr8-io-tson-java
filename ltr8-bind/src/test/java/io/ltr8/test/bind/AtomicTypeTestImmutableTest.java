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
import io.ltr8.test.data.AtomicTypeTestImmutable;

public class AtomicTypeTestImmutableTest {

	private final static boolean pBoolean = false;
	private final static Boolean oBoolean = Boolean.TRUE;
	private final static byte pByte = 1;
	private final static Byte oByte = Byte.valueOf((byte) 2);
	private final static char pChar = 3;
	private final static Character oChar = Character.valueOf((char) 103);
	private final static short pShort = 4;
	private final static Short oShort = Short.valueOf((short) 5);
	private final static int pInteger = 42;
	private final static Integer oInteger = Integer.valueOf(6);
	private final static long pLong = 7;
	private final static Long oLong = Long.valueOf(10l);
	private final static float pFloat = 12.42f;
	private final static Float oFloat = Float.valueOf(13.54f);
	private final static double pDouble = 56.34d;
	private final static Double oDouble = Double.valueOf(123.456d);
	private final static String string = "test";

	AtomicTypeTestImmutable test = new AtomicTypeTestImmutable(pBoolean, oBoolean, pByte, oByte, pChar, oChar, pShort,
			oShort, pInteger, oInteger, pLong, oLong, pFloat, oFloat, pDouble, oDouble, string);

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {

		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(AtomicTypeTestImmutable.class);
		Assertions.assertNotNull(descriptor);

		Assertions.assertEquals(AtomicTypeTestImmutable.class, descriptor.typeClass());
		//Assertions.assertEquals(AtomicTypeTestImmutable.class, descriptor.dataClass());

		DataClassField[] fields = descriptor.fields();
		Assertions.assertNotNull(fields);
		Assertions.assertEquals(17, fields.length);

		DataClassField fieldPBoolean = fields[0];
		Assertions.assertEquals("pBoolean", fieldPBoolean.name());
		Assertions.assertEquals(boolean.class, fieldPBoolean.type());
		Assertions.assertEquals(true, fieldPBoolean.isRequired());

		DataClassField fieldOBoolean = fields[1];
		Assertions.assertEquals("oBoolean", fieldOBoolean.name());
		Assertions.assertEquals(Boolean.class, fieldOBoolean.type());
		Assertions.assertEquals(false, fieldOBoolean.isRequired());

		DataClassField fieldPByte = fields[2];
		Assertions.assertEquals("pByte", fieldPByte.name());
		Assertions.assertEquals(byte.class, fieldPByte.type());
		Assertions.assertEquals(true, fieldPByte.isRequired());

		DataClassField fieldOByte = fields[3];
		Assertions.assertEquals("oByte", fieldOByte.name());
		Assertions.assertEquals(Byte.class, fieldOByte.type());
		Assertions.assertEquals(false, fieldOByte.isRequired());

		DataClassField fieldPChar = fields[4];
		Assertions.assertEquals("pChar", fieldPChar.name());
		Assertions.assertEquals(char.class, fieldPChar.type());
		Assertions.assertEquals(true, fieldPChar.isRequired());

		DataClassField fieldOChar = fields[5];
		Assertions.assertEquals("oChar", fieldOChar.name());
		Assertions.assertEquals(Character.class, fieldOChar.type());
		Assertions.assertEquals(false, fieldOChar.isRequired());

		DataClassField fieldPShort = fields[6];
		Assertions.assertEquals("pShort", fieldPShort.name());
		Assertions.assertEquals(short.class, fieldPShort.type());
		Assertions.assertEquals(true, fieldPShort.isRequired());

		DataClassField fieldOShort = fields[7];
		Assertions.assertEquals("oShort", fieldOShort.name());
		Assertions.assertEquals(Short.class, fieldOShort.type());
		Assertions.assertEquals(false, fieldOShort.isRequired());

		DataClassField fieldPInteger = fields[8];
		Assertions.assertEquals("pInteger", fieldPInteger.name());
		Assertions.assertEquals(int.class, fieldPInteger.type());
		Assertions.assertEquals(true, fieldPInteger.isRequired());

		DataClassField fieldOInteger = fields[9];
		Assertions.assertEquals("oInteger", fieldOInteger.name());
		Assertions.assertEquals(Integer.class, fieldOInteger.type());
		Assertions.assertEquals(false, fieldOInteger.isRequired());

		DataClassField fieldPLong = fields[10];
		Assertions.assertEquals("pLong", fieldPLong.name());
		Assertions.assertEquals(long.class, fieldPLong.type());
		Assertions.assertEquals(true, fieldPLong.isRequired());

		DataClassField fieldOLong = fields[11];
		Assertions.assertEquals("oLong", fieldOLong.name());
		Assertions.assertEquals(Long.class, fieldOLong.type());
		Assertions.assertEquals(false, fieldOLong.isRequired());

		DataClassField fieldPFloat = fields[12];
		Assertions.assertEquals("pFloat", fieldPFloat.name());
		Assertions.assertEquals(float.class, fieldPFloat.type());
		Assertions.assertEquals(true, fieldPFloat.isRequired());

		DataClassField fieldOFloat = fields[13];
		Assertions.assertEquals("oFloat", fieldOFloat.name());
		Assertions.assertEquals(Float.class, fieldOFloat.type());
		Assertions.assertEquals(false, fieldOFloat.isRequired());

		DataClassField fieldPDouble = fields[14];
		Assertions.assertEquals("pDouble", fieldPDouble.name());
		Assertions.assertEquals(double.class, fieldPDouble.type());
		Assertions.assertEquals(true, fieldPDouble.isRequired());

		DataClassField fieldODouble = fields[15];
		Assertions.assertEquals("oDouble", fieldODouble.name());
		Assertions.assertEquals(Double.class, fieldODouble.type());
		Assertions.assertEquals(false, fieldODouble.isRequired());

		DataClassField fieldString = fields[16];
		Assertions.assertEquals("string", fieldString.name());
		Assertions.assertEquals(String.class, fieldString.type());
		Assertions.assertEquals(false, fieldString.isRequired());

	}

	@Test
	public void testToArray() throws Throwable {

		// project to an array.
		ArrayMapper arrayMap = new ArrayMapper(context);

		// write to array.
		Object[] values = arrayMap.toArray(test);

		// convert to object.
		AtomicTypeTestImmutable object = arrayMap.toObject(AtomicTypeTestImmutable.class, values);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof AtomicTypeTestImmutable);

		Assertions.assertEquals(pBoolean, object.getpBoolean());
		Assertions.assertEquals(oBoolean, object.getoBoolean());
		Assertions.assertEquals(pByte, object.getpByte());
		Assertions.assertEquals(oByte, object.getoByte());
		Assertions.assertEquals(pChar, object.getpChar());
		Assertions.assertEquals(oChar, object.getoChar());
		Assertions.assertEquals(pShort, object.getpShort());
		Assertions.assertEquals(oShort, object.getoShort());
		Assertions.assertEquals(pInteger, object.getpInteger());
		Assertions.assertEquals(oInteger, object.getoInteger());
		Assertions.assertEquals(pLong, object.getpLong());
		Assertions.assertEquals(oLong, object.getoLong());
		Assertions.assertEquals(pFloat, object.getpFloat());
		Assertions.assertEquals(oFloat, object.getoFloat());
		Assertions.assertEquals(pDouble, object.getpDouble());
		Assertions.assertEquals(oDouble, object.getoDouble());
		Assertions.assertEquals(string, object.getString());

	}

	@Test
	public void testToMap() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		AtomicTypeTestImmutable object = (AtomicTypeTestImmutable) mapMapper.toObject(AtomicTypeTestImmutable.class,
				map);

		// validate result.
		Assertions.assertNotNull(object);
		Assertions.assertTrue(object instanceof AtomicTypeTestImmutable);

		Assertions.assertEquals(pBoolean, object.getpBoolean());
		Assertions.assertEquals(oBoolean, object.getoBoolean());
		Assertions.assertEquals(pByte, object.getpByte());
		Assertions.assertEquals(oByte, object.getoByte());
		Assertions.assertEquals(pChar, object.getpChar());
		Assertions.assertEquals(oChar, object.getoChar());
		Assertions.assertEquals(pShort, object.getpShort());
		Assertions.assertEquals(oShort, object.getoShort());
		Assertions.assertEquals(pInteger, object.getpInteger());
		Assertions.assertEquals(oInteger, object.getoInteger());
		Assertions.assertEquals(pLong, object.getpLong());
		Assertions.assertEquals(oLong, object.getoLong());
		Assertions.assertEquals(pFloat, object.getpFloat());
		Assertions.assertEquals(oFloat, object.getoFloat());
		Assertions.assertEquals(pDouble, object.getpDouble());
		Assertions.assertEquals(oDouble, object.getoDouble());
		Assertions.assertEquals(string, object.getString());

	}

	@Test
	public void testMapToObjectException() throws Throwable {
		MapMapper mapMapper = new MapMapper(context);
		Map<String, Object> map = mapMapper.toMap(test);

		// corrupting the map by putting an invalid value for x.
		map.put("pBoolean", "error");

		Assertions.assertThrows(DataBindException.class, () -> {
			mapMapper.toObject(AtomicTypeTestImmutable.class, map);
		});

	}
}
