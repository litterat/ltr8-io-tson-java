/*
 * Copyright (c) 2020-2021, Litterat Pty Ltd. All Rights Reserved.
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
package io.ltr8.test.data;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Record;
import io.ltr8.annotation.Typename;

/**
 * 
 * This is complex in the sense that it is modifying the input or output values of X and Y. The byte
 * code invariance verifier will not be able to resolve which parameters are X and Y. Field
 * annotations are used to assist the verifier for y. However, x is ok because the field x setter
 * can be checked by the invariance verifier for first param and the getter matches the field.
 *
 */
@Typename(namespace = "test", name="ComplexImmutable")
public class ComplexImmutable {

	public static int multiplier = 2;

	private final int x;
	private final int y;

	@Record
	public ComplexImmutable(int x, @Field("y") int y) {
		this.x = x;

		// hopefully this isn't removed.
		int test = y * multiplier;
		this.y = test / multiplier;
	}

	// Event though method name "x" matches the field, it doesn't return the value from field x that
	// was found in the constructor. Even though we use the method name to find the already found
	// field "x", the annotation is here to confirm we're looking in the right place.
	@Field("x")
	public int x() {
		int test = x * multiplier;
		return test / multiplier;
	}

	public int y() {
		return y;
	}
}
