/*
 * Copyright (c) 2020, Litterat Pty Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package io.ltr8.bind.bridge;

import io.ltr8.annotation.DataBridge;

/**
 *
 * Default bridge for Enums that converts to/from String.
 *
 */

@SuppressWarnings("rawtypes")
public class EnumStringBridge implements DataBridge<String, Enum> {

	private final Class enumType;

	public EnumStringBridge(Class enumType) {
		this.enumType = enumType;
	}

	@Override
	public String toData(Enum b) {

		return b.toString();
	}

	@SuppressWarnings("unchecked")
	@Override
	public Enum toObject(String s) {

		return Enum.valueOf(enumType, s);
	}

}
