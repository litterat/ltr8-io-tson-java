/*
 * Copyright (c) 2026, Litterat Pty Ltd. All Rights Reserved.
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
package io.ltr8.bind;

/**
 * Whatever answers a {@link DataProtocol}'s exchanges: an object implementing the interface, through {@link
 * DataProtocol#handler}, or a router, a decorator or a remote handler written over another handler. Nothing that
 * calls one knows which kind it holds.
 *
 * <p>Per-call context (a deadline, a caller's identity, a trace id) is never a parameter here: the caller binds a
 * {@link ScopedValue} around {@code handle}, and a decorator rebinds it. A binding is not visible in a callback on
 * another thread, which is why {@code handle} blocks rather than returning a promise.
 */
@FunctionalInterface
public interface DataExchangeHandler {

	/**
	 * Answers {@code exchange} with {@code request}.
	 *
	 * @param exchange an exchange of the protocol this handler answers
	 * @param request  an instance of {@code exchange.request()}'s class, or {@code null} where the exchange takes
	 *                 nothing
	 * @return the response, or {@code null} where the exchange has none
	 * @throws Throwable a declared error, which {@link DataExchange#error} recognises, or any other throwable,
	 *                   which is the caller's fault path; neither is wrapped
	 */
	Object handle(DataExchange exchange, Object request) throws Throwable;
}
