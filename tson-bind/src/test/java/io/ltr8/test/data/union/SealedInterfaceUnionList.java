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
package io.ltr8.test.data.union;

import io.ltr8.annotation.Record;

import java.util.List;

@Record
public class SealedInterfaceUnionList {
	private final List<SealedInterfaceUnion> list;

	private final SealedInterfaceUnion[] array;

	public SealedInterfaceUnionList(List<SealedInterfaceUnion> list, SealedInterfaceUnion[] array) {
		this.list = list;
		this.array = array;
	}

	public List<SealedInterfaceUnion> list() {
		return this.list;
	}

	public SealedInterfaceUnion[] array() {
		return this.array;
	}
}
