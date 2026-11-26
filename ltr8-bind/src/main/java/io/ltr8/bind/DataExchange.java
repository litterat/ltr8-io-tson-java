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

import java.lang.invoke.MethodHandle;
import java.util.List;
import java.util.Optional;

/**
 * One exchange of a {@link DataProtocol}: a request, a response and declared errors, each a {@link DataClass} of
 * the context the protocol was read in. It holds no {@code Method}, only its name and its {@link #invoker()}, so
 * what it was read from is not part of what it is.
 */
public final class DataExchange {

	// Identity, for DataProtocol.handler to refuse an exchange of another protocol.
	final DataProtocol<?> protocol;

	private final String name;
	private final Optional<DataClass> request;
	private final Optional<DataClass> response;
	private final List<DataClass> errors;
	private final MethodHandle invoker;

	DataExchange(DataProtocol<?> protocol, String name, DataClass request, DataClass response,
			List<DataClass> errors, MethodHandle invoker) {
		this.protocol = protocol;
		this.name = name;
		this.request = Optional.ofNullable(request);
		this.response = Optional.ofNullable(response);
		this.errors = errors;
		this.invoker = invoker;
	}

	/** The name as its protocol spells it, {@code placeOrder}. */
	public String name() {
		return name;
	}

	/** The request's record descriptor, or empty where the exchange takes nothing and is handed {@code null}. */
	public Optional<DataClass> request() {
		return request;
	}

	/** The response's descriptor, or empty where the exchange has none and answers {@code null}. */
	public Optional<DataClass> response() {
		return response;
	}

	/**
	 * The declared errors, in the order the {@code throws} clause names them: each a bridged descriptor whose
	 * {@code typeClass()} is the throwable and whose {@code dataClass()} is its record. No two are related by
	 * subclassing and no two bridge to one record.
	 */
	public List<DataClass> errors() {
		return errors;
	}

	/**
	 * The declared error {@code thrown} is an instance of, the same instance {@link #errors()} lists, so a caller
	 * may key what it knows per error on it by identity.
	 *
	 * @param thrown any throwable, or {@code null}
	 * @return the declared error, or empty for a throwable the exchange does not declare and for {@code null}
	 */
	public Optional<DataClass> error(Throwable thrown) {
		for (DataClass error : errors) {
			if (error.typeClass().isInstance(thrown)) {
				return Optional.of(error);
			}
		}
		return Optional.empty();
	}

	/**
	 * A handle of type {@code (Object target, Object request) -> Object} that calls the method on {@code target}
	 * and behaves as it does: it returns the response, {@code null} where there is none, and lets every throwable
	 * through untouched. Where the exchange takes nothing the request is dropped unexamined. A target or request
	 * of the wrong class is a {@code ClassCastException}, and a {@code null} target a {@code
	 * NullPointerException}.
	 */
	public MethodHandle invoker() {
		return invoker;
	}

	@Override
	public String toString() {
		return "DataExchange [" + protocol.typeClass().getName() + "." + name + "]";
	}
}
