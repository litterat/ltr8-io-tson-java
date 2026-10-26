/*
 * Copyright (c) 2021, Litterat Pty Ltd. All Rights Reserved.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.test.data.FieldAnnotationImmutable;

public class FieldAnnotationImmutableTest {

	FieldAnnotationImmutable test = new FieldAnnotationImmutable("", "", "", "", "", "", "", "", "");

	DataBindContext context;

	@BeforeEach
	public void setup() {
		context = DataBindContext.builder().build();
	}

	@Test
	public void checkDescriptor() throws Throwable {

		DataClassRecord descriptor = (DataClassRecord) context.getDescriptor(FieldAnnotationImmutable.class);
		Assertions.assertNotNull(descriptor);

		Assertions.assertEquals(FieldAnnotationImmutable.class, descriptor.typeClass());
		//Assertions.assertEquals(FieldAnnotationImmutable.class, descriptor.dataClass());

		DataClassField[] fields = descriptor.fields();
		Assertions.assertNotNull(fields);
		Assertions.assertEquals(9, fields.length);

		DataClassField fieldA = fields[0];
		Assertions.assertEquals("a", fieldA.name());
		Assertions.assertEquals(String.class, fieldA.type());
		Assertions.assertEquals(true, fieldA.isRequired());

		DataClassField fieldB = fields[1];
		Assertions.assertEquals("b", fieldB.name());
		Assertions.assertEquals(String.class, fieldB.type());
		Assertions.assertEquals(false, fieldB.isRequired());

		DataClassField fieldC = fields[2];
		Assertions.assertEquals("c", fieldC.name());
		Assertions.assertEquals(String.class, fieldC.type());
		Assertions.assertEquals(true, fieldC.isRequired());

		DataClassField fieldD = fields[3];
		Assertions.assertEquals("d", fieldD.name());
		Assertions.assertEquals(String.class, fieldD.type());
		Assertions.assertEquals(true, fieldD.isRequired());

		DataClassField fieldE = fields[4];
		Assertions.assertEquals("e", fieldE.name());
		Assertions.assertEquals(String.class, fieldE.type());
		Assertions.assertEquals(false, fieldE.isRequired());

		DataClassField fieldF = fields[5];
		Assertions.assertEquals("f", fieldF.name());
		Assertions.assertEquals(String.class, fieldF.type());
		Assertions.assertEquals(true, fieldF.isRequired());

		DataClassField fieldG = fields[6];
		Assertions.assertEquals("g", fieldG.name());
		Assertions.assertEquals(String.class, fieldG.type());
		Assertions.assertEquals(true, fieldG.isRequired());

		DataClassField fieldH = fields[7];
		Assertions.assertEquals("h", fieldH.name());
		Assertions.assertEquals(String.class, fieldH.type());
		Assertions.assertEquals(false, fieldH.isRequired());

		DataClassField fieldI = fields[8];
		Assertions.assertEquals("i", fieldI.name());
		Assertions.assertEquals(String.class, fieldI.type());
		Assertions.assertEquals(true, fieldI.isRequired());
	}

}
