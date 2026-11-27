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

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The type each finder path gives a generic component, per shape of its declared type: on a class described raw, on
 * one described at {@code String}, held at {@code String} by another class, and inherited from a class whose
 * variable the subclass binds to {@code String}. Records what binds and what is refused, message included.
 */
public class GenericComponentTypeTest {

	private static final String[] SHAPES = { "List", "Map", "Nested", "Bare", "Array", "Wild", "Opt", "OptList",
			"Bridged" };

	@Test
	void aRecordsCanonicalConstructor() {
		assertEquals("""
				RecList: CodeAnalysisException: Unrecognized parameterized type
				RecList<String>: RecList(value:List [String])
				RecMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				RecMap<String>: RecMap(value:Map {String=String})
				RecNested: CodeAnalysisException: Unrecognized parameterized type
				RecNested<String>: RecNested(value:List [[String]])
				RecBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				RecBare<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				RecArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				RecArray<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				RecWild: CodeAnalysisException: Unrecognized parameterized type
				RecWild<String>: CodeAnalysisException: Unrecognized parameterized type
				RecOpt: ClassCastException
				RecOpt<String>: RecOpt(value:String String)
				RecOptList: CodeAnalysisException: Unrecognized parameterized type
				RecOptList<String>: RecOptList(value:List [String])
				RecBridged: CodeAnalysisException: Bridge object type not assignable from field type
				RecBridged<String>: CodeAnalysisException: Bridge object type not assignable from field type
				HoldsRec: HoldsRec(held:RecList RecList(value:List [String]))
				""", table("Rec", null, SHAPES, false));
	}

	@Test
	void aRecordsProfileConstructor() {
		assertEquals("""
				ProfList: CodeAnalysisException: Unrecognized parameterized type
				ProfList<String>: ProfList(value:List [String])
				ProfMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				ProfMap<String>: ProfMap(value:Map {String=String})
				ProfNested: CodeAnalysisException: Unrecognized parameterized type
				ProfNested<String>: ProfNested(value:List [[String]])
				ProfBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				ProfBare<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				ProfArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				ProfArray<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				ProfWild: CodeAnalysisException: Unrecognized parameterized type
				ProfWild<String>: CodeAnalysisException: Unrecognized parameterized type
				ProfOpt: ClassCastException
				ProfOpt<String>: ProfOpt(value:String String)
				ProfOptList: CodeAnalysisException: Unrecognized parameterized type
				ProfOptList<String>: ProfOptList(value:List [String])
				ProfBridged: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				ProfBridged<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				HoldsProf: HoldsProf(held:ProfList ProfList(value:List [String]))
				""", table("Prof", "p", SHAPES, false));
	}

	@Test
	void aConstructorStoringItsParameter() {
		assertEquals("""
				CtorList: CodeAnalysisException: Unrecognized parameterized type
				CtorList<String>: CtorList(value:List [String])
				TextCtorList: TextCtorList(value:List [String])
				CtorMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				CtorMap<String>: CtorMap(value:Map {String=String})
				TextCtorMap: TextCtorMap(value:Map {String=String})
				CtorNested: CodeAnalysisException: Unrecognized parameterized type
				CtorNested<String>: CtorNested(value:List [[String]])
				TextCtorNested: TextCtorNested(value:List [[String]])
				CtorBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				CtorBare<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				TextCtorBare: TextCtorBare(value:String String)
				CtorArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				CtorArray<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				TextCtorArray: TextCtorArray(value:String[] [String])
				CtorWild: CodeAnalysisException: Unrecognized parameterized type
				CtorWild<String>: CodeAnalysisException: Unrecognized parameterized type
				TextCtorWild: CodeAnalysisException: Unrecognized parameterized type
				CtorOpt: ClassCastException
				CtorOpt<String>: CtorOpt(value:String String)
				TextCtorOpt: TextCtorOpt(value:String String)
				CtorOptList: CodeAnalysisException: Unrecognized parameterized type
				CtorOptList<String>: CtorOptList(value:List [String])
				TextCtorOptList: TextCtorOptList(value:List [String])
				CtorBridged: CodeAnalysisException: Bridge object type not assignable from field type
				CtorBridged<String>: CodeAnalysisException: Bridge object type not assignable from field type
				TextCtorBridged: TextCtorBridged(value:String String)
				HoldsCtor: HoldsCtor(held:CtorList CtorList(value:List [String]))
				""", table("Ctor", null, SHAPES, true));
	}

	@Test
	void aConstructorNamingItsParameter() {
		assertEquals("""
				NamedList: CodeAnalysisException: Collection must provide parameterized type information
				NamedList<String>: CodeAnalysisException: Collection must provide parameterized type information
				NamedMap: CodeAnalysisException: Map must provide parameterized type information
				NamedMap<String>: CodeAnalysisException: Map must provide parameterized type information
				NamedBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				NamedBare<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				NamedOpt: DataBindException: Unable to find a valid data conversion for class: class java.util.Optional
				NamedOpt<String>: DataBindException: Unable to find a valid data conversion for class: class java.util.Optional
				HoldsNamed: CodeAnalysisException: Collection must provide parameterized type information
				""", table("Named", null, new String[] { "List", "Map", "Bare", "Opt" }, false));
	}

	@Test
	void aPublicField() {
		assertEquals("""
				PubList: CodeAnalysisException: Unrecognized parameterized type
				PubList<String>: PubList(value:List set [String])
				TextPubList: TextPubList(value:List set [String])
				PubMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				PubMap<String>: PubMap(value:Map set {String=String})
				TextPubMap: TextPubMap(value:Map set {String=String})
				PubNested: CodeAnalysisException: Unrecognized parameterized type
				PubNested<String>: PubNested(value:List set [[String]])
				TextPubNested: TextPubNested(value:List set [[String]])
				PubBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PubBare<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				TextPubBare: TextPubBare(value:String set String)
				PubArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PubArray<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				TextPubArray: TextPubArray(value:String[] set [String])
				PubWild: CodeAnalysisException: Unrecognized parameterized type
				PubWild<String>: CodeAnalysisException: Unrecognized parameterized type
				TextPubWild: CodeAnalysisException: Unrecognized parameterized type
				PubOpt: ClassCastException
				PubOpt<String>: PubOpt(value:String set String)
				TextPubOpt: TextPubOpt(value:String set String)
				PubOptList: CodeAnalysisException: Unrecognized parameterized type
				PubOptList<String>: PubOptList(value:List set [String])
				TextPubOptList: TextPubOptList(value:List set [String])
				PubBridged: CodeAnalysisException: Bridge object type not assignable from field type
				PubBridged<String>: CodeAnalysisException: Bridge object type not assignable from field type
				TextPubBridged: TextPubBridged(value:String set String)
				HoldsPub: HoldsPub(held:PubList PubList(value:List set [String]))
				""", table("Pub", null, SHAPES, true));
	}

	@Test
	void aGetterSetterPair() {
		assertEquals("""
				PairList: CodeAnalysisException: Unrecognized parameterized type
				PairList<String>: PairList(value:List set [String])
				TextPairList: TextPairList(value:List set [String])
				PairMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				PairMap<String>: PairMap(value:Map set {String=String})
				TextPairMap: TextPairMap(value:Map set {String=String})
				PairNested: CodeAnalysisException: Unrecognized parameterized type
				PairNested<String>: PairNested(value:List set [[String]])
				TextPairNested: TextPairNested(value:List set [[String]])
				PairBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PairBare<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				TextPairBare: TextPairBare(value:String set String)
				PairArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				PairArray<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				TextPairArray: TextPairArray(value:String[] set [String])
				PairWild: CodeAnalysisException: Unrecognized parameterized type
				PairWild<String>: CodeAnalysisException: Unrecognized parameterized type
				TextPairWild: CodeAnalysisException: Unrecognized parameterized type
				PairOpt: ClassCastException
				PairOpt<String>: PairOpt(value:String set String)
				TextPairOpt: TextPairOpt(value:String set String)
				PairOptList: CodeAnalysisException: Unrecognized parameterized type
				PairOptList<String>: PairOptList(value:List set [String])
				TextPairOptList: TextPairOptList(value:List set [String])
				PairBridged: CodeAnalysisException: Bridge object type not assignable from field type
				PairBridged<String>: CodeAnalysisException: Bridge object type not assignable from field type
				TextPairBridged: TextPairBridged(value:String set String)
				HoldsPair: HoldsPair(held:PairList PairList(value:List set [String]))
				""", table("Pair", null, SHAPES, true));
	}

	@Test
	void aConstructorComponentASetterWrites() {
		assertEquals("""
				MergedList: CodeAnalysisException: Unrecognized parameterized type
				MergedList<String>: MergedList(value:List set [String])
				TextMergedList: TextMergedList(value:List set [String])
				MergedMap: CodeAnalysisException: Unrecognized parameterized type for map key/value
				MergedMap<String>: MergedMap(value:Map set {String=String})
				TextMergedMap: TextMergedMap(value:Map set {String=String})
				MergedNested: CodeAnalysisException: Unrecognized parameterized type
				MergedNested<String>: MergedNested(value:List set [[String]])
				TextMergedNested: TextMergedNested(value:List set [[String]])
				MergedBare: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				MergedBare<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				TextMergedBare: TextMergedBare(value:String set String)
				MergedArray: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				MergedArray<String>: DataBindException: Unable to find a valid data conversion for class: class java.lang.Object
				TextMergedArray: TextMergedArray(value:String[] set [String])
				MergedWild: CodeAnalysisException: Unrecognized parameterized type
				MergedWild<String>: CodeAnalysisException: Unrecognized parameterized type
				TextMergedWild: CodeAnalysisException: Unrecognized parameterized type
				MergedOpt: ClassCastException
				MergedOpt<String>: MergedOpt(value:String set String)
				TextMergedOpt: TextMergedOpt(value:String set String)
				MergedOptList: CodeAnalysisException: Unrecognized parameterized type
				MergedOptList<String>: MergedOptList(value:List set [String])
				TextMergedOptList: TextMergedOptList(value:List set [String])
				MergedBridged: CodeAnalysisException: Bridge object type not assignable from field type
				MergedBridged<String>: CodeAnalysisException: Bridge object type not assignable from field type
				TextMergedBridged: TextMergedBridged(value:String set String)
				HoldsMerged: HoldsMerged(held:MergedList MergedList(value:List set [String]))
				""", table("Merged", null, SHAPES, true));
	}

	@Test
	void theRest() {
		assertEquals("""
				RecOptWild: ClassCastException
				PairOptWild: ClassCastException
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
				""", lines(
				line("RecOptWild", null, null),
				line("PairOptWild", null, null),
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
				line("RawCtorTextSetter", null, null)));
	}

	// --- helpers

	/** Each shape on the path raw, at {@code String}, and inherited where {@code inherited}; then its holder. */
	private static String table(String path, String profile, String[] shapes, boolean inherited) {
		List<String> lines = new ArrayList<>();
		for (String shape : shapes) {
			lines.add(line(path + shape, profile, null));
			lines.add(at(path + shape, profile));
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
		Class<?> type = fixture(name);
		return line(name, profile, new DataParameterizedType(type, String.class));
	}

	private static String line(String name, String profile, Type at) {
		Class<?> type = fixture(name);
		DataBindContext.Builder builder = DataBindContext.builder();
		if (profile != null) {
			builder.profile(profile);
		}
		DataBindContext context = builder.build();
		String described = name + (at == null ? "" : "<String>");
		try {
			DataClass descriptor = at == null ? context.getDescriptor(type) : context.getDescriptor(type, at);
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
