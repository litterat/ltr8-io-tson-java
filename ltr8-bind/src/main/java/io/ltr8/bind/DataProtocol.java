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

import io.ltr8.bind.analysis.ProtocolBinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An interface described as a set of {@link DataExchange}s, from {@link DataBindContext#getProtocol}. Immutable:
 * built once and shared by every call.
 *
 * @param <T> the interface it was read from
 */
public final class DataProtocol<T> {

	private final Class<T> typeClass;
	private final List<DataExchange> exchanges;
	private final Map<String, DataExchange> byJavaName;

	DataProtocol(Class<T> typeClass, List<ProtocolBinder.Exchange> analysed) {
		this.typeClass = typeClass;
		List<DataExchange> exchanges = new ArrayList<>(analysed.size());
		Map<String, DataExchange> byJavaName = new HashMap<>();
		for (ProtocolBinder.Exchange exchange : analysed) {
			DataExchange built = new DataExchange(this, exchange.name(), exchange.request(), exchange.response(),
					exchange.errors(), exchange.invoker());
			exchanges.add(built);
			byJavaName.put(ProtocolBinder.javaName(exchange.name()), built);
		}
		exchanges.sort(Comparator.comparing(DataExchange::name));
		this.exchanges = List.copyOf(exchanges);
		this.byJavaName = Map.copyOf(byJavaName);
	}

	/** The interface this protocol was read from. */
	public Class<T> typeClass() {
		return typeClass;
	}

	/** Every exchange, ordered by name. */
	public List<DataExchange> exchanges() {
		return exchanges;
	}

	/**
	 * The exchange whose name is one with {@code name} once each {@code _} is removed and the character after it
	 * upper-cased: {@code place_order} and {@code placeOrder} both find a method spelt either way, and {@code
	 * PlaceOrder} finds neither.
	 *
	 * @param name a name as the data spells it, not {@code null}
	 * @return the exchange, or empty where the protocol has none of that name
	 */
	public Optional<DataExchange> exchange(String name) {
		return Optional.ofNullable(byJavaName.get(ProtocolBinder.javaName(Objects.requireNonNull(name, "name"))));
	}

	/**
	 * {@code target} as a handler, answering each exchange by calling its {@link DataExchange#invoker()} on
	 * {@code target}: the response is returned and every throwable passes through untouched.
	 *
	 * @param target an instance of {@link #typeClass()}
	 * @return a handler for this protocol's exchanges, which throws {@code IllegalArgumentException} when handed an
	 *         exchange of another protocol
	 * @throws IllegalArgumentException where {@code target} is {@code null} or not an instance of {@link
	 *                                  #typeClass()}, which a caller bypassing the generic signature can pass
	 */
	public DataExchangeHandler handler(T target) {
		if (!typeClass.isInstance(target)) {
			throw new IllegalArgumentException(String.format("%s is not an instance of %s, so it cannot answer "
					+ "the protocol read from it", target == null ? "null" : target.getClass().getName(),
					typeClass.getName()));
		}
		Object answering = target;
		return (exchange, request) -> {
			if (exchange.protocol != this) {
				throw new IllegalArgumentException(String.format("%s is not an exchange of this protocol over %s",
						exchange, typeClass.getName()));
			}
			return exchange.invoker().invokeExact(answering, request);
		};
	}

	@Override
	public String toString() {
		return "DataProtocol [" + typeClass.getName() + ", exchanges="
				+ exchanges.stream().map(DataExchange::name).toList() + "]";
	}
}
