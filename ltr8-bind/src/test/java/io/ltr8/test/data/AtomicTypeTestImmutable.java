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

public class AtomicTypeTestImmutable {

	private final boolean pBoolean;
	private final Boolean oBoolean;
	private final byte pByte;
	private final Byte oByte;
	private final char pChar;
	private final Character oChar;
	private final short pShort;
	private final Short oShort;
	private final int pInteger;
	private final Integer oInteger;
	private final long pLong;
	private final Long oLong;
	private final float pFloat;
	private final Float oFloat;
	private final double pDouble;
	private final Double oDouble;
	private final String string;

	@Record
	public AtomicTypeTestImmutable(boolean pBoolean, Boolean oBoolean, byte pByte, Byte oByte, char pChar,
			Character oChar, short pShort, Short oShort, int pInteger, Integer oInteger, long pLong, Long oLong,
			float pFloat, Float oFloat, double pDouble, Double oDouble, String string) {
		super();
		this.pBoolean = pBoolean;
		this.oBoolean = oBoolean;
		this.pByte = pByte;
		this.oByte = oByte;
		this.pChar = pChar;
		this.oChar = oChar;
		this.pShort = pShort;
		this.oShort = oShort;
		this.pInteger = pInteger;
		this.oInteger = oInteger;
		this.pLong = pLong;
		this.oLong = oLong;
		this.pFloat = pFloat;
		this.oFloat = oFloat;
		this.pDouble = pDouble;
		this.oDouble = oDouble;
		this.string = string;
	}

	public boolean getpBoolean() {
		return pBoolean;
	}

	public Boolean getoBoolean() {
		return oBoolean;
	}

	public byte getpByte() {
		return pByte;
	}

	public Byte getoByte() {
		return oByte;
	}

	public char getpChar() {
		return pChar;
	}

	public Character getoChar() {
		return oChar;
	}

	public short getpShort() {
		return pShort;
	}

	public Short getoShort() {
		return oShort;
	}

	public int getpInteger() {
		return pInteger;
	}

	public Integer getoInteger() {
		return oInteger;
	}

	public long getpLong() {
		return pLong;
	}

	public Long getoLong() {
		return oLong;
	}

	public float getpFloat() {
		return pFloat;
	}

	public Float getoFloat() {
		return oFloat;
	}

	public double getpDouble() {
		return pDouble;
	}

	public Double getoDouble() {
		return oDouble;
	}

	public String getString() {
		return string;
	}

}
