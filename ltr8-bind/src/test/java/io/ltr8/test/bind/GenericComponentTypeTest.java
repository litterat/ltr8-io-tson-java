package io.ltr8.test.bind;

import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataClass;
import io.ltr8.bind.DataClassArray;
import io.ltr8.bind.DataClassField;
import io.ltr8.bind.DataClassMap;
import io.ltr8.bind.DataClassRecord;
import io.ltr8.bind.DataParameterizedType;
import io.ltr8.test.data.GenericComponents;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The type each finder path gives a generic component, per shape of its declared type: on a class described raw, on
 * one described at {@code String} and at {@code ? extends String}, held at {@code String} by another class, and
 * inherited from a class whose variable the subclass binds to {@code String}. Records what binds and what is
 * refused, message included.
 */
public class GenericComponentTypeTest {

	private static final String[] SHAPES = { "List", "Map", "Nested", "Bare", "Array", "Wild", "Opt", "OptList",
			"Bridged" };

	@Test
	void aRecordsCanonicalConstructor() {
		assertEquals("""
				RecList: CodeAnalysisException: Unrecognized parameterized type
				RecList<String>: RecList(value:List [String])
				RecList<? extends String>: RecList(value:List [String])
				RecMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				RecMap<String>: RecMap(value:Map {String=String})
				RecMap<? extends String>: RecMap(value:Map {String=String})
				RecNested: CodeAnalysisException: Unrecognized parameterized type
				RecNested<String>: RecNested(value:List [[String]])
				RecNested<? extends String>: RecNested(value:List [[String]])
				RecBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				RecBare<String>: RecBare(value:String String)
				RecBare<? extends String>: RecBare(value:String String)
				RecArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				RecArray<String>: RecArray(value:String[] [String])
				RecArray<? extends String>: RecArray(value:String[] [String])
				RecWild: CodeAnalysisException: Unrecognized parameterized type
				RecWild<String>: CodeAnalysisException: Unrecognized parameterized type
				RecWild<? extends String>: CodeAnalysisException: Unrecognized parameterized type
				RecOpt: CodeAnalysisException: io.ltr8.test.data.GenericComponents$RecOpt's component 'value' \
				is an Optional of T, neither a class nor a parameterized type
				RecOpt<String>: RecOpt(value:String String)
				RecOpt<? extends String>: RecOpt(value:String String)
				RecOptList: CodeAnalysisException: Unrecognized parameterized type
				RecOptList<String>: RecOptList(value:List [String])
				RecOptList<? extends String>: RecOptList(value:List [String])
				RecBridged: CodeAnalysisException: Bridge object type not assignable from field type
				RecBridged<String>: RecBridged(value:String String)
				RecBridged<? extends String>: RecBridged(value:String String)
				HoldsRec: HoldsRec(held:RecList RecList(value:List [String]))
				""", table("Rec", null, SHAPES, false));
	}

	@Test
	void aRecordsProfileConstructor() {
		assertEquals("""
				ProfList: CodeAnalysisException: Unrecognized parameterized type
				ProfList<String>: ProfList(value:List [String])
				ProfList<? extends String>: ProfList(value:List [String])
				ProfMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				ProfMap<String>: ProfMap(value:Map {String=String})
				ProfMap<? extends String>: ProfMap(value:Map {String=String})
				ProfNested: CodeAnalysisException: Unrecognized parameterized type
				ProfNested<String>: ProfNested(value:List [[String]])
				ProfNested<? extends String>: ProfNested(value:List [[String]])
				ProfBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				ProfBare<String>: ProfBare(value:String String)
				ProfBare<? extends String>: ProfBare(value:String String)
				ProfArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				ProfArray<String>: ProfArray(value:String[] [String])
				ProfArray<? extends String>: ProfArray(value:String[] [String])
				ProfWild: CodeAnalysisException: Unrecognized parameterized type
				ProfWild<String>: CodeAnalysisException: Unrecognized parameterized type
				ProfWild<? extends String>: CodeAnalysisException: Unrecognized parameterized type
				ProfOpt: CodeAnalysisException: io.ltr8.test.data.GenericComponents$ProfOpt's component 'value' \
				is an Optional of T, neither a class nor a parameterized type
				ProfOpt<String>: ProfOpt(value:String String)
				ProfOpt<? extends String>: ProfOpt(value:String String)
				ProfOptList: CodeAnalysisException: Unrecognized parameterized type
				ProfOptList<String>: ProfOptList(value:List [String])
				ProfOptList<? extends String>: ProfOptList(value:List [String])
				ProfBridged: CodeAnalysisException: Bridge object type not assignable from field type
				ProfBridged<String>: ProfBridged(value:String String)
				ProfBridged<? extends String>: ProfBridged(value:String String)
				HoldsProf: HoldsProf(held:ProfList ProfList(value:List [String]))
				""", table("Prof", "p", SHAPES, false));
	}

	@Test
	void aConstructorStoringItsParameter() {
		assertEquals("""
				CtorList: CodeAnalysisException: Unrecognized parameterized type
				CtorList<String>: CtorList(value:List [String])
				CtorList<? extends String>: CtorList(value:List [String])
				TextCtorList: TextCtorList(value:List [String])
				CtorMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				CtorMap<String>: CtorMap(value:Map {String=String})
				CtorMap<? extends String>: CtorMap(value:Map {String=String})
				TextCtorMap: TextCtorMap(value:Map {String=String})
				CtorNested: CodeAnalysisException: Unrecognized parameterized type
				CtorNested<String>: CtorNested(value:List [[String]])
				CtorNested<? extends String>: CtorNested(value:List [[String]])
				TextCtorNested: TextCtorNested(value:List [[String]])
				CtorBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				CtorBare<String>: CtorBare(value:String String)
				CtorBare<? extends String>: CtorBare(value:String String)
				TextCtorBare: TextCtorBare(value:String String)
				CtorArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				CtorArray<String>: CtorArray(value:String[] [String])
				CtorArray<? extends String>: CtorArray(value:String[] [String])
				TextCtorArray: TextCtorArray(value:String[] [String])
				CtorWild: CodeAnalysisException: Unrecognized parameterized type
				CtorWild<String>: CodeAnalysisException: Unrecognized parameterized type
				CtorWild<? extends String>: CodeAnalysisException: Unrecognized parameterized type
				TextCtorWild: CodeAnalysisException: Unrecognized parameterized type
				CtorOpt: CodeAnalysisException: io.ltr8.test.data.GenericComponents$CtorOpt's component 'value' \
				is an Optional of T, neither a class nor a parameterized type
				CtorOpt<String>: CtorOpt(value:String String)
				CtorOpt<? extends String>: CtorOpt(value:String String)
				TextCtorOpt: TextCtorOpt(value:String String)
				CtorOptList: CodeAnalysisException: Unrecognized parameterized type
				CtorOptList<String>: CtorOptList(value:List [String])
				CtorOptList<? extends String>: CtorOptList(value:List [String])
				TextCtorOptList: TextCtorOptList(value:List [String])
				CtorBridged: CodeAnalysisException: Bridge object type not assignable from field type
				CtorBridged<String>: CtorBridged(value:String String)
				CtorBridged<? extends String>: CtorBridged(value:String String)
				TextCtorBridged: TextCtorBridged(value:String String)
				HoldsCtor: HoldsCtor(held:CtorList CtorList(value:List [String]))
				""", table("Ctor", null, SHAPES, true));
	}

	@Test
	void aConstructorNamingItsParameter() {
		assertEquals("""
				NamedList: CodeAnalysisException: Unrecognized parameterized type
				NamedList<String>: NamedList(value:List [String])
				NamedList<? extends String>: NamedList(value:List [String])
				NamedMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				NamedMap<String>: NamedMap(value:Map {String=String})
				NamedMap<? extends String>: NamedMap(value:Map {String=String})
				NamedBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				NamedBare<String>: NamedBare(value:String String)
				NamedBare<? extends String>: NamedBare(value:String String)
				NamedOpt: CodeAnalysisException: io.ltr8.test.data.GenericComponents$NamedOpt's component 'value' \
				is an Optional of T, neither a class nor a parameterized type
				NamedOpt<String>: NamedOpt(value:String String)
				NamedOpt<? extends String>: NamedOpt(value:String String)
				HoldsNamed: HoldsNamed(held:NamedList NamedList(value:List [String]))
				""", table("Named", null, new String[] { "List", "Map", "Bare", "Opt" }, false));
	}

	@Test
	void aPublicField() {
		assertEquals("""
				PubList: CodeAnalysisException: Unrecognized parameterized type
				PubList<String>: PubList(value:List set [String])
				PubList<? extends String>: PubList(value:List set [String])
				TextPubList: TextPubList(value:List set [String])
				PubMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				PubMap<String>: PubMap(value:Map set {String=String})
				PubMap<? extends String>: PubMap(value:Map set {String=String})
				TextPubMap: TextPubMap(value:Map set {String=String})
				PubNested: CodeAnalysisException: Unrecognized parameterized type
				PubNested<String>: PubNested(value:List set [[String]])
				PubNested<? extends String>: PubNested(value:List set [[String]])
				TextPubNested: TextPubNested(value:List set [[String]])
				PubBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PubBare<String>: PubBare(value:String set String)
				PubBare<? extends String>: PubBare(value:String set String)
				TextPubBare: TextPubBare(value:String set String)
				PubArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PubArray<String>: PubArray(value:String[] set [String])
				PubArray<? extends String>: PubArray(value:String[] set [String])
				TextPubArray: TextPubArray(value:String[] set [String])
				PubWild: CodeAnalysisException: Unrecognized parameterized type
				PubWild<String>: CodeAnalysisException: Unrecognized parameterized type
				PubWild<? extends String>: CodeAnalysisException: Unrecognized parameterized type
				TextPubWild: CodeAnalysisException: Unrecognized parameterized type
				PubOpt: CodeAnalysisException: io.ltr8.test.data.GenericComponents$PubOpt's component 'value' \
				is an Optional of T, neither a class nor a parameterized type
				PubOpt<String>: PubOpt(value:String set String)
				PubOpt<? extends String>: PubOpt(value:String set String)
				TextPubOpt: TextPubOpt(value:String set String)
				PubOptList: CodeAnalysisException: Unrecognized parameterized type
				PubOptList<String>: PubOptList(value:List set [String])
				PubOptList<? extends String>: PubOptList(value:List set [String])
				TextPubOptList: TextPubOptList(value:List set [String])
				PubBridged: CodeAnalysisException: Bridge object type not assignable from field type
				PubBridged<String>: PubBridged(value:String set String)
				PubBridged<? extends String>: PubBridged(value:String set String)
				TextPubBridged: TextPubBridged(value:String set String)
				HoldsPub: HoldsPub(held:PubList PubList(value:List set [String]))
				""", table("Pub", null, SHAPES, true));
	}

	@Test
	void aGetterSetterPair() {
		assertEquals("""
				PairList: CodeAnalysisException: Unrecognized parameterized type
				PairList<String>: PairList(value:List set [String])
				PairList<? extends String>: PairList(value:List set [String])
				TextPairList: TextPairList(value:List set [String])
				PairMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				PairMap<String>: PairMap(value:Map set {String=String})
				PairMap<? extends String>: PairMap(value:Map set {String=String})
				TextPairMap: TextPairMap(value:Map set {String=String})
				PairNested: CodeAnalysisException: Unrecognized parameterized type
				PairNested<String>: PairNested(value:List set [[String]])
				PairNested<? extends String>: PairNested(value:List set [[String]])
				TextPairNested: TextPairNested(value:List set [[String]])
				PairBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PairBare<String>: PairBare(value:String set String)
				PairBare<? extends String>: PairBare(value:String set String)
				TextPairBare: TextPairBare(value:String set String)
				PairArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PairArray<String>: PairArray(value:String[] set [String])
				PairArray<? extends String>: PairArray(value:String[] set [String])
				TextPairArray: TextPairArray(value:String[] set [String])
				PairWild: CodeAnalysisException: Unrecognized parameterized type
				PairWild<String>: CodeAnalysisException: Unrecognized parameterized type
				PairWild<? extends String>: CodeAnalysisException: Unrecognized parameterized type
				TextPairWild: CodeAnalysisException: Unrecognized parameterized type
				PairOpt: CodeAnalysisException: io.ltr8.test.data.GenericComponents$PairOpt's component 'value' \
				is an Optional of T, neither a class nor a parameterized type
				PairOpt<String>: PairOpt(value:String set String)
				PairOpt<? extends String>: PairOpt(value:String set String)
				TextPairOpt: TextPairOpt(value:String set String)
				PairOptList: CodeAnalysisException: Unrecognized parameterized type
				PairOptList<String>: PairOptList(value:List set [String])
				PairOptList<? extends String>: PairOptList(value:List set [String])
				TextPairOptList: TextPairOptList(value:List set [String])
				PairBridged: CodeAnalysisException: Bridge object type not assignable from field type
				PairBridged<String>: PairBridged(value:String set String)
				PairBridged<? extends String>: PairBridged(value:String set String)
				TextPairBridged: TextPairBridged(value:String set String)
				HoldsPair: HoldsPair(held:PairList PairList(value:List set [String]))
				""", table("Pair", null, SHAPES, true));
	}

	@Test
	void aConstructorComponentASetterWrites() {
		assertEquals("""
				MergedList: CodeAnalysisException: Unrecognized parameterized type
				MergedList<String>: MergedList(value:List set [String])
				MergedList<? extends String>: MergedList(value:List set [String])
				TextMergedList: TextMergedList(value:List set [String])
				MergedMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				MergedMap<String>: MergedMap(value:Map set {String=String})
				MergedMap<? extends String>: MergedMap(value:Map set {String=String})
				TextMergedMap: TextMergedMap(value:Map set {String=String})
				MergedNested: CodeAnalysisException: Unrecognized parameterized type
				MergedNested<String>: MergedNested(value:List set [[String]])
				MergedNested<? extends String>: MergedNested(value:List set [[String]])
				TextMergedNested: TextMergedNested(value:List set [[String]])
				MergedBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				MergedBare<String>: MergedBare(value:String set String)
				MergedBare<? extends String>: MergedBare(value:String set String)
				TextMergedBare: TextMergedBare(value:String set String)
				MergedArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				MergedArray<String>: MergedArray(value:String[] set [String])
				MergedArray<? extends String>: MergedArray(value:String[] set [String])
				TextMergedArray: TextMergedArray(value:String[] set [String])
				MergedWild: CodeAnalysisException: Unrecognized parameterized type
				MergedWild<String>: CodeAnalysisException: Unrecognized parameterized type
				MergedWild<? extends String>: CodeAnalysisException: Unrecognized parameterized type
				TextMergedWild: CodeAnalysisException: Unrecognized parameterized type
				MergedOpt: CodeAnalysisException: io.ltr8.test.data.GenericComponents$MergedOpt's component 'value' \
				is an Optional of T, neither a class nor a parameterized type
				MergedOpt<String>: MergedOpt(value:String set String)
				MergedOpt<? extends String>: MergedOpt(value:String set String)
				TextMergedOpt: TextMergedOpt(value:String set String)
				MergedOptList: CodeAnalysisException: Unrecognized parameterized type
				MergedOptList<String>: MergedOptList(value:List set [String])
				MergedOptList<? extends String>: MergedOptList(value:List set [String])
				TextMergedOptList: TextMergedOptList(value:List set [String])
				MergedBridged: CodeAnalysisException: Bridge object type not assignable from field type
				MergedBridged<String>: MergedBridged(value:String set String)
				MergedBridged<? extends String>: MergedBridged(value:String set String)
				TextMergedBridged: TextMergedBridged(value:String set String)
				HoldsMerged: HoldsMerged(held:MergedList MergedList(value:List set [String]))
				""", table("Merged", null, SHAPES, true));
	}

	@Test
	void theRest() {
		assertEquals("""
				RecOptWild: CodeAnalysisException: io.ltr8.test.data.GenericComponents$RecOptWild's component 'value' \
				is an Optional of ? extends java.lang.Number, neither a class nor a parameterized type
				PairOptWild: CodeAnalysisException: io.ltr8.test.data.GenericComponents$PairOptWild's component 'value' \
				is an Optional of ? extends java.lang.Number, neither a class nor a parameterized type
				RecOptArray: CodeAnalysisException: io.ltr8.test.data.GenericComponents$RecOptArray's component 'value' \
				is an Optional of T[], neither a class nor a parameterized type
				RecOptArray<String>: RecOptArray(value:String[] [String])
				PairSetterAtArgument: PairSetterAtArgument()
				PairSetterAtArgument<String>: PairSetterAtArgument()
				PassedCtorList: CodeAnalysisException: Unrecognized parameterized type
				PassedCtorList<String>: PassedCtorList(value:List [String])
				PassedPubList: CodeAnalysisException: Unrecognized parameterized type
				PassedPubList<String>: PassedPubList(value:List set [String])
				PassedPairList: CodeAnalysisException: Unrecognized parameterized type
				PassedPairList<String>: PassedPairList(value:List set [String])
				ListPubBare: ListPubBare(value:List set [String])
				ListPairBare: ListPairBare(value:List set [String])
				RawCtorTextSetter: CodeAnalysisException: Collection must provide parameterized type information
				PlainNamedList: PlainNamedList(value:List [String])
				""", lines(
				line("RecOptWild", null, null),
				line("PairOptWild", null, null),
				line("RecOptArray", null, null),
				at("RecOptArray", null),
				line("PairSetterAtArgument", null, null),
				at("PairSetterAtArgument", null),
				line("PassedCtorList", null, null),
				at("PassedCtorList", null),
				line("PassedPubList", null, null),
				at("PassedPubList", null),
				line("PassedPairList", null, null),
				at("PassedPairList", null),
				line("ListPubBare", null, null),
				line("ListPairBare", null, null),
				line("RawCtorTextSetter", null, null),
				line("PlainNamedList", null, null)));
	}

	/** An upper-bounded wildcard argument binds its variable to its bound; these two bound nothing to bind. */
	@Test
	void aWildcardArgumentWithNoUpperBound() {
		assertEquals("""
				RecList<?>: CodeAnalysisException: Unrecognized parameterized type
				RecList<? super String>: CodeAnalysisException: Unrecognized parameterized type
				RecMap<?>: CodeAnalysisException: Unrecognized parameterized type for map key/value
				RecMap<? super String>: CodeAnalysisException: Unrecognized parameterized type for map key/value
				RecNested<?>: CodeAnalysisException: Unrecognized parameterized type
				RecNested<? super String>: CodeAnalysisException: Unrecognized parameterized type
				RecBare<?>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				RecBare<? super String>: DataBindException: Unable to find a valid data conversion for class: \
				class java.lang.Object
				RecArray<?>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				RecArray<? super String>: DataBindException: Unable to find a valid data conversion for class: \
				class java.lang.Object
				RecWild<?>: CodeAnalysisException: Unrecognized parameterized type
				RecWild<? super String>: CodeAnalysisException: Unrecognized parameterized type
				RecOpt<?>: CodeAnalysisException: io.ltr8.test.data.GenericComponents$RecOpt's component 'value' \
				is an Optional of ?, neither a class nor a parameterized type
				RecOpt<? super String>: CodeAnalysisException: io.ltr8.test.data.GenericComponents$RecOpt's component \
				'value' is an Optional of ? super java.lang.String, neither a class nor a parameterized type
				RecOptList<?>: CodeAnalysisException: Unrecognized parameterized type
				RecOptList<? super String>: CodeAnalysisException: Unrecognized parameterized type
				RecBridged<?>: CodeAnalysisException: Bridge object type not assignable from field type
				RecBridged<? super String>: CodeAnalysisException: Bridge object type not assignable from field type
				PairList<?>: CodeAnalysisException: Unrecognized parameterized type
				PairList<? super String>: CodeAnalysisException: Unrecognized parameterized type
				PairBare<?>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PairBare<? super String>: DataBindException: Unable to find a valid data conversion for class: \
				class java.lang.Object
				PairArray<?>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PairArray<? super String>: DataBindException: Unable to find a valid data conversion for class: \
				class java.lang.Object
				PairOpt<?>: CodeAnalysisException: io.ltr8.test.data.GenericComponents$PairOpt's component 'value' \
				is an Optional of ?, neither a class nor a parameterized type
				PairOpt<? super String>: CodeAnalysisException: io.ltr8.test.data.GenericComponents$PairOpt's \
				component 'value' is an Optional of ? super java.lang.String, neither a class nor a parameterized type
				""", lines(Arrays.stream(new String[] { "RecList", "RecMap", "RecNested", "RecBare", "RecArray",
						"RecWild", "RecOpt", "RecOptList", "RecBridged", "PairList", "PairBare", "PairArray", "PairOpt" })
				.flatMap(name -> Stream.of(line(name, null, ANY), line(name, null, SUPER_TEXT)))
				.toArray(String[]::new)));
	}

	@Test
	void anArrayOfAParameterizedType() {
		assertEquals("""
				RecListArray: RecListArray(value:List[] [[String]])
				RecNestedArray: RecNestedArray(value:List[] [[[String]]])
				RecMapArray: RecMapArray(value:Map[] [{String=String}])
				RecGridArray: RecGridArray(value:List[][] [[[String]]])
				RecOptListArray: RecOptListArray(value:List[] [[String]])
				RecListVarArray: CodeAnalysisException: Unrecognized parameterized type
				RecListVarArray<String>: RecListVarArray(value:List[] [[String]])
				CtorListArray: CtorListArray(value:List[] [[String]])
				PubListArray: PubListArray(value:List[] set [[String]])
				PairListArray: PairListArray(value:List[] set [[String]])
				RecArray<List<String>>: RecArray(value:List[] [[String]])
				ProfArray<List<String>>: ProfArray(value:List[] [[String]])
				CtorArray<List<String>>: CtorArray(value:List[] [[String]])
				PubArray<List<String>>: PubArray(value:List[] set [[String]])
				PairArray<List<String>>: PairArray(value:List[] set [[String]])
				MergedArray<List<String>>: MergedArray(value:List[] set [[String]])
				RecOptArray<List<String>>: RecOptArray(value:List[] [[String]])
				ListPubArray: ListPubArray(value:List[] set [[String]])
				ListPairArray: ListPairArray(value:List[] set [[String]])
				""", lines(
				line("RecListArray", null, null),
				line("RecNestedArray", null, null),
				line("RecMapArray", null, null),
				line("RecGridArray", null, null),
				line("RecOptListArray", null, null),
				line("RecListVarArray", null, null),
				at("RecListVarArray", null),
				line("CtorListArray", null, null),
				line("PubListArray", null, null),
				line("PairListArray", null, null),
				line("RecArray", null, TEXT_LIST),
				line("ProfArray", "p", TEXT_LIST),
				line("CtorArray", null, TEXT_LIST),
				line("PubArray", null, TEXT_LIST),
				line("PairArray", null, TEXT_LIST),
				line("MergedArray", null, TEXT_LIST),
				line("RecOptArray", null, TEXT_LIST),
				line("ListPubArray", null, null),
				line("ListPairArray", null, null)));
	}

	/** An element, a key and a value take what a component takes; a wildcard and an unbound variable stay refused. */
	@Test
	void anArrayOfAParameterizedTypeAsAnElementAKeyAndAValue() {
		assertEquals("""
				RecListOfListArrays: RecListOfListArrays(value:List [[[String]]])
				RecSetOfListArrays: RecSetOfListArrays(value:Set [[[String]]])
				RecArrayOfListsOfListArrays: RecArrayOfListsOfListArrays(value:List[] [[[[String]]]])
				RecMapOfListArrays: RecMapOfListArrays(value:Map {String=[[String]]})
				RecMapKeyedByListArrays: RecMapKeyedByListArrays(value:Map {[[String]]=String})
				RecMapKeyedByArrays: RecMapKeyedByArrays(value:Map {[String]=String})
				RecOptListOfListArrays: RecOptListOfListArrays(value:List [[[String]]])
				RecOptListOfArrays: RecOptListOfArrays(value:List [[String]])
				RecListOfVarArrays: CodeAnalysisException: Unrecognized parameterized type
				RecListOfVarArrays<String>: RecListOfVarArrays(value:List [[String]])
				RecListOfVarArrays<List<String>>: RecListOfVarArrays(value:List [[[String]]])
				RecListOfVarArrays<?>: CodeAnalysisException: Unrecognized parameterized type
				RecMapOfVarArrays: CodeAnalysisException: Unrecognized parameterized type for map key/value
				RecMapOfVarArrays<List<String>>: RecMapOfVarArrays(value:Map {String=[[String]]})
				PubListOfVarArrays: CodeAnalysisException: Unrecognized parameterized type
				PubListOfVarArrays<List<String>>: PubListOfVarArrays(value:List set [[[String]]])
				RecList<String[]>: RecList(value:List [[String]])
				RecList<List<String>[]>: RecList(value:List [[[String]]])
				RecMap<List<String>[]>: RecMap(value:Map {String=[[String]]})
				RecOptList<List<String>[]>: RecOptList(value:List [[[String]]])
				RecWild<List<String>[]>: CodeAnalysisException: Unrecognized parameterized type
				""", lines(
				line("RecListOfListArrays", null, null),
				line("RecSetOfListArrays", null, null),
				line("RecArrayOfListsOfListArrays", null, null),
				line("RecMapOfListArrays", null, null),
				line("RecMapKeyedByListArrays", null, null),
				line("RecMapKeyedByArrays", null, null),
				line("RecOptListOfListArrays", null, null),
				line("RecOptListOfArrays", null, null),
				line("RecListOfVarArrays", null, null),
				at("RecListOfVarArrays", null),
				line("RecListOfVarArrays", null, TEXT_LIST),
				line("RecListOfVarArrays", null, ANY),
				line("RecMapOfVarArrays", null, null),
				line("RecMapOfVarArrays", null, TEXT_LIST),
				line("PubListOfVarArrays", null, null),
				line("PubListOfVarArrays", null, TEXT_LIST),
				line("RecList", null, String[].class),
				line("RecList", null, TEXT_LIST_ARRAY),
				line("RecMap", null, TEXT_LIST_ARRAY),
				line("RecOptList", null, TEXT_LIST_ARRAY),
				line("RecWild", null, TEXT_LIST_ARRAY)));
	}

	// --- helpers

	private static final Type TEXT_LIST = new DataParameterizedType(List.class, String.class);

	private static List<String>[] textListArray;

	private static final Type TEXT_LIST_ARRAY = field("textListArray");

	private static List<? extends String> extendsText;

	private static List<?> any;

	private static List<? super String> superText;

	private static final Type EXTENDS_TEXT = argument("extendsText");

	private static final Type ANY = argument("any");

	private static final Type SUPER_TEXT = argument("superText");

	private static Type argument(String field) {
		return ((ParameterizedType) field(field)).getActualTypeArguments()[0];
	}

	private static Type field(String field) {
		try {
			return GenericComponentTypeTest.class.getDeclaredField(field).getGenericType();
		} catch (NoSuchFieldException e) {
			throw new AssertionError(e);
		}
	}

	/**
	 * Each shape on the path raw, at {@code String}, at {@code ? extends String}, and inherited where {@code
	 * inherited}; then its holder.
	 */
	private static String table(String path, String profile, String[] shapes, boolean inherited) {
		List<String> lines = new ArrayList<>();
		for (String shape : shapes) {
			lines.add(line(path + shape, profile, null));
			lines.add(at(path + shape, profile));
			lines.add(line(path + shape, profile, EXTENDS_TEXT));
			if (inherited) {
				lines.add(line("Text" + path + shape, profile, null));
			}
		}
		lines.add(line("Holds" + path, profile, null));
		return lines(lines.toArray(new String[0]));
	}

	private static String lines(String... lines) {
		return Arrays.stream(lines).map(line -> line + "\n").collect(Collectors.joining());
	}

	private static String at(String name, String profile) {
		return line(name, profile, String.class);
	}

	/** {@code name} described raw where {@code argument} is null, and otherwise at {@code argument}. */
	private static String line(String name, String profile, Type argument) {
		Class<?> type = fixture(name);
		DataBindContext.Builder builder = DataBindContext.builder();
		if (profile != null) {
			builder.profile(profile);
		}
		DataBindContext context = builder.build();
		String described = name + (argument == null ? ""
				: "<" + argument.getTypeName().replace("java.lang.", "").replace("java.util.", "") + ">");
		try {
			DataClass descriptor = argument == null ? context.getDescriptor(type)
					: context.getDescriptor(type, new DataParameterizedType(type, argument));
			return described + ": " + render(descriptor, 0);
		} catch (ClassCastException refused) {
			// Thrown often enough, the JIT throws it with no message.
			return described + ": ClassCastException";
		} catch (Exception refused) {
			return described + ": " + refused.getClass().getSimpleName() + ": " + refused.getMessage();
		}
	}

	private static Class<?> fixture(String name) {
		try {
			return Class.forName(GenericComponents.class.getName() + "$" + name);
		} catch (ClassNotFoundException e) {
			throw new AssertionError(e);
		}
	}

	private static String render(DataClass descriptor, int depth) {
		if (descriptor == null) {
			return "-";
		}
		return switch (descriptor) {
			case DataClassArray array -> "[" + render(array.arrayDataClass(), depth + 1) + "]";
			case DataClassMap map -> "{" + render(map.keyDataClass(), depth + 1) + "="
					+ render(map.valueDataClass(), depth + 1) + "}";
			case DataClassRecord record when depth < 3 -> record.typeClass().getSimpleName()
					+ Arrays.stream(record.fields()).map(field -> render(field, depth + 1))
							.collect(Collectors.joining(", ", "(", ")"));
			default -> descriptor.typeClass().getSimpleName();
		};
	}

	private static String render(DataClassField field, int depth) {
		return field.name() + ":" + field.type().getSimpleName() + (field.setter().isPresent() ? " set" : "")
				+ (field.isRequired() ? " required" : "") + " " + render(field.dataClass(), depth);
	}
}
