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

import io.ltr8.annotation.Record;

/**
 * 
 * This is a wrapper class to show how a complex atom value might be included in another class.
 *
 */
public class IntAtomData {

	private final IntAtom intAtom;

	@Record
	public IntAtomData(IntAtom intAtom) {
		this.intAtom = intAtom;
	}

	public IntAtom intAtom() {
		return intAtom;
	}
}
