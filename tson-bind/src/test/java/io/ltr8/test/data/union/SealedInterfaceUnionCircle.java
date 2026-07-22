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

/**
 * This could extend InterfaceUnionPoint, however, that represents another more complex use case.
 * Duplicating x,y to isolate this interface based union test case.
 */
@Record
public class SealedInterfaceUnionCircle implements SealedInterfaceUnion {

	private final int x;
	private final int y;
	private final int radius;

	public SealedInterfaceUnionCircle(int x, int y, int radius) {
		this.x = x;
		this.y = y;
		this.radius = radius;
	}

	@Override
	public int x() {
		return x;
	}

	@Override
	public int y() {
		return y;
	}

	public int radius() {
		return radius;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + radius;
		result = prime * result + x;
		result = prime * result + y;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SealedInterfaceUnionCircle other = (SealedInterfaceUnionCircle) obj;
		if (radius != other.radius)
			return false;
		if (x != other.x)
			return false;
		if (y != other.y)
			return false;
		return true;
	}
}
