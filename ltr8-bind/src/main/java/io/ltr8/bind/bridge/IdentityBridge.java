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
package io.ltr8.bind.bridge;

import io.ltr8.annotation.DataBridge;

/**
 *
 * The identity bridge. Mainly here as a placeholder for the default bridge in @Field annotation.
 */

public class IdentityBridge implements DataBridge<Object,Object> {

	@Override
	public Object toData(Object b) {
		return b;
	}

	@Override
	public Object toObject(Object s) {
		return s;
	}

}
