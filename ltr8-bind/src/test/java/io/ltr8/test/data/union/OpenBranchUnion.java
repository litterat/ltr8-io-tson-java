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

/**
 * A sealed union with one closed leaf and one <b>non-sealed</b> branch -- the shape
 * {@code DefaultUnionBinder.collectSealedMembers} keeps as a member in its own right rather than walking
 * into, because its implementations cannot be known at analysis time.
 */
public sealed interface OpenBranchUnion permits OpenBranchUnionPoint, OpenBranchUnionShape {

	int x();
}
