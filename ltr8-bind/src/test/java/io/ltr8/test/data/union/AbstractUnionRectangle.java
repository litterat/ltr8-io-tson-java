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

@Record
public class AbstractUnionRectangle extends AbstractUnion {

	private final int width;

	private final int height;

	public AbstractUnionRectangle(int x, int y, int width, int height) {
		super(x, y);

		this.width = width;
		this.height = height;
	}

	public int width() {
		return this.width;
	}

	public int height() {
		return this.height;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + height;
		result = prime * result + width;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		AbstractUnionRectangle other = (AbstractUnionRectangle) obj;
		if (height != other.height)
			return false;
		if (width != other.width)
			return false;
		return true;
	}
}
