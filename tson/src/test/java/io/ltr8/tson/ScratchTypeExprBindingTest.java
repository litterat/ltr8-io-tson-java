package io.ltr8.tson;

import io.ltr8.annotation.Field;
import io.ltr8.annotation.Transparent;
import io.ltr8.annotation.Typename;
import io.ltr8.bind.DataBindContext;
import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.ProcessorConfig;
import io.ltr8.tson.base.bind.AtomContext;
import io.ltr8.tson.base.source.SchemaAccess;
import io.ltr8.tson.base.source.SchemaSource;
import io.ltr8.tson.compiler.config.SchemaModelNameBinder;
import io.ltr8.tson.schema.TsonBundledSchemas;
import io.ltr8.tson.schema.meta.ArrayBody;
import io.ltr8.tson.schema.meta.TypeExpr;
import io.ltr8.tson.schema.meta.TypeInline;
import io.ltr8.tson.schema.meta.TypeRef;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike for proposal/inline-types.md §7.3: can bind read, and write back, a slot declared as a disjoint choice of a
 * string-class name and a tagged abstract record family into one sealed Java interface?
 *
 * <p>Tier 1: a test-local schema mirroring the kernel shape of §3.3 ({@code t_expr => @meta.disjoint ( t_name |
 * t_inline )}, {@code t_array => t_product & t_inline & { … }}) bound to test-local records, read through the
 * bind-mode compiled readers ({@code ValueReaderFactoryRegistry.bind}) that a schema's {@code !meta.array { … }}
 * construction is read through too, and written back through {@code DataClassObjectWriter}.
 *
 * <p>Tier 1 is the marker shape (§3.3), which the design did not take: the kernel's {@code type_inline} is a wrapper
 * record, a concrete variant, so a tag naming a subtype of a variant is not needed and is not read. Its tests that
 * depend on that, or on G3, are disabled, each saying which; {@link RealModel} is the shape taken.
 *
 * <p><b>The gaps found here.</b>
 * <ul>
 *   <li><b>G1, untagged recovery</b>: {@code ChoiceReader} recovered an untagged value only where every variant is
 *       scalar, so the bare name at {@code ( t_name | t_inline )} needed a tag. Closed: a disjoint choice recovers a
 *       brace- or bracket-class variant by its delimiter ({@code ChoiceReader.untaggedRecovery}).</li>
 *   <li><b>G2, a tag naming a subtype of a variant</b>: {@code NamedDispatchReader} admits only a tag naming a
 *       declared variant, so {@code !t_array} at {@code ( t_name | t_inline )} is refused. Not patched: the
 *       wrapper does not need it, and the spec does not say it ({@code SPEC-FEEDBACK.md} #4).</li>
 *   <li><b>G3, a bridged variant through a choice</b>: still open. {@code BindTargets} reads a choice "as it is", so a
 *       variant whose Java member is a bridged or {@code @Transparent} class (here {@code TName} over {@code text})
 *       delivers the wire value, a {@code String}, into the {@code TExpr} component: {@code ClassCastException}. The
 *       real model does not meet it -- {@code meta.type_ref}'s own reader produces the {@code TypeRef} -- see
 *       {@link RealModel}.</li>
 *   <li><b>G4, annotations on a name through a choice</b>: closed for the kernel's slots. A {@code TypeExpr}
 *       component at a type slot's choice reads a name through the {@code TypeNameSlotReader} a {@code TypeRef}
 *       component gets, which keeps the annotations written on it ({@code BindTargets}); a choice in general still
 *       reads its variants as they are.</li>
 * </ul>
 *
 * <p>The model it reads into is the real one: {@code TypeExpr} over {@code TypeRef} and the wrapper {@code
 * TypeInline}, with every type slot retyped.
 */
class ScratchTypeExprBindingTest {

    static final String ID = "https://example.test/scratch-type-expr.tn";

    static final String SCHEMA = """
            !!id:"https://example.test/scratch-type-expr.tn"
            !!import:"%s"
            !!import:"%s"
            !schema {
              t_name    => text
              t_type    => abstract {}
              t_product => abstract t_type & {}
              t_inline  => abstract {}
              t_array   => t_product & t_inline & { element_type: t_expr  min_items?: int64 }
              t_map     => t_product & t_inline & { key_type: t_expr  value_type: t_expr }
              t_choice  => t_inline & { variants: [t_expr] }
              t_tuple   => t_product & t_inline & { arity: int32 }
              t_rec     => t_product & { width: int32 }
              t_expr    => @meta.disjoint ( t_name | t_inline )
              t_wide    => @meta.disjoint ( t_name | t_type )
              t_flat    => ( t_name | t_array | t_map )

              holder    => { slot: t_expr }
              holders   => { slots: [t_expr] }
              wide      => { slot: t_wide }
              flat      => { slot: t_flat }
            }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

    // The narrow Java model: TExpr over TRef and TInline, as §7.1.
    public sealed interface TExpr permits TRef, TInline {
    }

    public sealed interface TRef extends TExpr, TWide permits TName {
    }

    @Transparent
    public record TName(String name) implements TRef {
    }

    // The wide model: TWide over TRef and TType; TInline extends TType, as §7.1's TypeInline extends Type.
    public sealed interface TWide permits TRef, TType {
    }

    public sealed interface TType extends TWide permits TProduct, TInline {
    }

    public sealed interface TProduct extends TType permits TArray, TMap, TTuple, TRec {
    }

    public sealed interface TInline extends TType, TExpr permits TArray, TMap, TTuple, TChoice {
    }

    /** Doubly parented and holding no name, so it shows Q3 apart from G3. */
    @Typename(name = "t_tuple")
    public record TTuple(int arity) implements TProduct, TInline {
    }

    /** Implements two sealed interfaces, as ArrayBody would implement Product and TypeInline. */
    @Typename(name = "t_array")
    public record TArray(@Field("element_type") TExpr elementType,
                         @Field("min_items") Optional<Long> minItems) implements TProduct, TInline {
    }

    @Typename(name = "t_map")
    public record TMap(@Field("key_type") TExpr keyType, @Field("value_type") TExpr valueType)
            implements TProduct, TInline {
    }

    @Typename(name = "t_choice")
    public record TChoice(List<TExpr> variants) implements TInline {
    }

    @Typename(name = "t_rec")
    public record TRec(int width) implements TProduct {
    }

    public record Holder(TExpr slot) {
    }

    public record Holders(List<TExpr> slots) {
    }

    public record Wide(TWide slot) {
    }

    public record Flat(TExpr slot) {
    }

    static Tson bound() {
        SchemaSource source = uri -> SCHEMA;
        DataBindContext context = DataBindContext.builder()
                .nameBinder(DataNameBinder.ofMap(Map.ofEntries(
                        Map.entry("holder", Holder.class), Map.entry("holders", Holders.class),
                        Map.entry("wide", Wide.class), Map.entry("flat", Flat.class), Map.entry("t_expr", TExpr.class),
                        Map.entry("t_wide", TWide.class), Map.entry("t_name", TName.class),
                        Map.entry("t_type", TType.class), Map.entry("t_product", TProduct.class),
                        Map.entry("t_inline", TInline.class), Map.entry("t_array", TArray.class),
                        Map.entry("t_map", TMap.class), Map.entry("t_choice", TChoice.class),
                        Map.entry("t_tuple", TTuple.class), Map.entry("t_rec", TRec.class)))
                        .orElse(SchemaModelNameBinder.INSTANCE))
                .registerAtoms(AtomContext.hostTypes())
                .build();
        Tson tson = Tson.of(ProcessorConfig.defaults()
                .withSchemaAccess(SchemaAccess.of(source))
                .withDataBindContext(context));
        tson.resolve(SCHEMA);
        return tson;
    }

    static String doc(String root) {
        return "!!import:\"" + ID + "\"\n" + root;
    }

    static final TArray ARRAY_OF_MONEY = new TArray(new TName("money"), Optional.empty());

    // ── Q2: a name and a tagged inline type into one sealed interface whose members are sealed interfaces ──

    /** Fails on G3: the bare name recovers to t_name (G1 patched) and arrives as a String. */
    @Disabled("G3 open: a choice reads a bridged variant as it is, so the wire value reaches the sealed slot")
    @Test
    void q2_aNameReadsIntoTheSealedSlot() {
        Holder read = bound().objectReader().read(doc("!holder { slot: text }"), Holder.class);
        assertEquals(new Holder(new TName("text")), read);
    }

    /** Fails on G3 at the nested element_type; the !t_array dispatch itself (G2 patched) succeeds. */
    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q2_aTaggedInlineTypeReadsIntoTheSealedSlot() {
        Holder read = bound().objectReader().read(doc("!holder { slot: !t_array { element_type: text } }"),
                Holder.class);
        assertEquals(new Holder(new TArray(new TName("text"), Optional.empty())), read);
    }

    // ── Q2, isolated from dispatch: each variant named by its own tag, so the choice dispatches as it does today ──

    /** Fails on G3 with no G1 or G2 involved: the variant is tagged, and still arrives as a String. */
    @Disabled("G3 open: a choice reads a bridged variant as it is, so the wire value reaches the sealed slot")
    @Test
    void q2_isolated_aNameTaggedWithItsVariantBindsTheTransparentMember() {
        Holder read = bound().objectReader().read(doc("!holder { slot: !t_name text }"), Holder.class);
        assertEquals(new Holder(new TName("text")), read);
    }

    /** Fails on G3 inside TArray: the flat choice's dispatch of !t_array to TArray succeeds. */
    @Disabled("G3 open: a choice reads a bridged variant as it is, so the wire value reaches the sealed slot")
    @Test
    void q2_isolated_aFlatChoiceDispatchesEachTaggedVariantToItsSealedMember() {
        Flat read = bound().objectReader().read(doc(
                "!flat { slot: !t_array { element_type: !t_name money } }"), Flat.class);
        assertEquals(new Flat(ARRAY_OF_MONEY), read);
    }

    // ── Q3: a record implementing two sealed interfaces, read and written in both hierarchies ──

    /**
     * The narrow slot reaches TTuple through t_inline's dispatcher (union TInline), the wide one through t_type's
     * (union TWide, whose flattening lists TArray, TMap and TTuple twice, once under TProduct and once under TInline).
     */
    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q3_aDoublyParentedRecordReadsAtTheNarrowAndTheWideSlot_noNames() {
        Tson tson = bound();
        assertEquals(new Holder(new TTuple(2)),
                tson.objectReader().read(doc("!holder { slot: !t_tuple { arity: 2 } }"), Holder.class));
        assertEquals(new Wide(new TTuple(2)),
                tson.objectReader().read(doc("!wide { slot: !t_tuple { arity: 2 } }"), Wide.class));
    }

    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q3_aDoublyParentedRecordWritesAndRoundTripsInBothHierarchies_noNames() {
        Tson tson = bound();
        String narrow = tson.objectWriter().toTson(new Holder(new TTuple(2)));
        String wide = tson.objectWriter().toTson(new Wide(new TTuple(2)));
        assertEquals("{ slot: !t_tuple { arity: 2 } }", narrow);
        assertEquals("{ slot: !t_tuple { arity: 2 } }", wide);
        assertEquals(new Holder(new TTuple(2)), tson.objectReader().read(doc("!holder " + narrow), Holder.class));
        assertEquals(new Wide(new TTuple(2)), tson.objectReader().read(doc("!wide " + wide), Wide.class));
    }

    /** Fails on G3 at key_type; the same record with names inside. */
    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q3_aDoublyParentedRecordReadsAtTheNarrowAndTheWideSlot() {
        Tson tson = bound();
        Holder narrow = tson.objectReader().read(doc("!holder { slot: !t_map { key_type: text  value_type: int32 } }"),
                Holder.class);
        Wide wide = tson.objectReader().read(doc("!wide { slot: !t_map { key_type: text  value_type: int32 } }"),
                Wide.class);
        TMap map = new TMap(new TName("text"), new TName("int32"));
        assertEquals(new Holder(map), narrow);
        assertEquals(new Wide(map), wide);
    }

    /**
     * The test-local name is a {@code @Transparent} wrapper over a {@code text} value, so it writes as a quoted string
     * with no tag; the real model's {@code TypeRef} writes unquoted (see {@link RealModel}).
     */
    @Test
    void q3_aDoublyParentedRecordWritesTheSameTagInBothHierarchies() {
        Tson tson = bound();
        TMap map = new TMap(new TName("text"), new TName("int32"));
        assertEquals("{ slot: !t_map { key_type: \"text\" value_type: \"int32\" } }",
                tson.objectWriter().toTson(new Holder(map)));
        assertEquals("{ slot: !t_map { key_type: \"text\" value_type: \"int32\" } }",
                tson.objectWriter().toTson(new Wide(map)));
    }

    // ── Q4: the write direction, a name, an inline type, and nesting; and the round trip ──

    @Test
    void q4_aNameWritesUntaggedAsItsText() {
        assertEquals("{ slot: \"text\" }", bound().objectWriter().toTson(new Holder(new TName("text"))));
    }

    @Test
    void q4_aNestedInlineTypeWritesTaggedAtEachLevel() {
        Holder nested = new Holder(new TMap(new TName("text"), ARRAY_OF_MONEY));
        assertEquals("{ slot: !t_map { key_type: \"text\" value_type: !t_array { element_type: \"money\" } } }",
                bound().objectWriter().toTson(nested));
    }

    /** Fails on G3: a name inside arrives as a String. */
    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q4_aNestedInlineTypeRoundTrips() {
        Tson tson = bound();
        Holder nested = new Holder(new TMap(new TName("text"), ARRAY_OF_MONEY));
        String written = tson.objectWriter().toTson(nested);
        Holder read = tson.objectReader().read(doc("!holder " + written), Holder.class);
        assertEquals(nested, read);
    }

    /** Fails on G3: a name inside arrives as a String. */
    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q4_aNestedInlineTypeReadsFromTheResolvedFormSpelling() {
        Holder read = bound().objectReader().read(doc(
                "!holder { slot: !t_map { key_type: text  value_type: !t_array { element_type: money } } }"),
                Holder.class);
        assertEquals(new Holder(new TMap(new TName("text"), ARRAY_OF_MONEY)), read);
    }

    // ── Q5: the wide variant (t_type) admits what the narrow one refuses, and binds the same way ──

    /** Fails on G3: a name inside arrives as a String. */
    @Disabled("G3 open: a choice reads a bridged variant as it is, so the wire value reaches the sealed slot")
    @Test
    void q5_theWideSlotReadsANameAndAnInlineType() {
        Tson tson = bound();
        assertEquals(new Wide(new TName("uuid")),
                tson.objectReader().read(doc("!wide { slot: uuid }"), Wide.class));
        assertEquals(new Wide(ARRAY_OF_MONEY),
                tson.objectReader().read(doc("!wide { slot: !t_array { element_type: money } }"), Wide.class));
    }

    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q5_theWideSlotReadsAMemberOutsideTheNarrowLine() {
        assertEquals(new Wide(new TRec(3)),
                bound().objectReader().read(doc("!wide { slot: !t_rec { width: 3 } }"), Wide.class));
    }

    @Test
    void q5_theWideSlotWritesAMemberOutsideTheNarrowLine() {
        assertEquals("{ slot: !t_rec { width: 3 } }", bound().objectWriter().toTson(new Wide(new TRec(3))));
    }

    // ── Q6: a list of slots ──

    /** Fails on G3: a name inside arrives as a String. */
    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q6_aListOfSlotsReadsNamesAndInlineTypesMixed() {
        Holders read = bound().objectReader().read(doc(
                "!holders { slots: [ text  !t_array { element_type: money }  int32 ] }"), Holders.class);
        assertEquals(new Holders(List.of(new TName("text"), ARRAY_OF_MONEY, new TName("int32"))), read);
    }

    /** Fails on G3: a name inside arrives as a String. */
    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q6_aChoiceBodyHoldsAListOfSlots() {
        Holder read = bound().objectReader().read(doc(
                "!holder { slot: !t_choice { variants: [ text  !t_array { element_type: money } ] } }"), Holder.class);
        assertEquals(new Holder(new TChoice(List.of(new TName("text"), ARRAY_OF_MONEY))), read);
    }

    /** Fails on G3: a name inside arrives as a String. */
    @Disabled("The marker shape, not taken: a tag naming a subtype of a variant is not read (SPEC-FEEDBACK.md #4)")
    @Test
    void q6_aListOfSlotsRoundTrips() {
        Tson tson = bound();
        Holders value = new Holders(List.of(new TName("text"), ARRAY_OF_MONEY));
        String written = tson.objectWriter().toTson(value);
        assertEquals(value, tson.objectReader().read(doc("!holders " + written), Holders.class));
    }

    /**
     * Tier 2: the real model under the phase 1 kernel, {@code TypeExpr} over {@code TypeRef} and the wrapper {@code
     * TypeInline}. The slot is the kernel's own {@code meta.type_expr} ({@code ( meta.type_ref | meta.type_inline
     * )}), bound to a record holding a {@code TypeExpr}; a value written in place is the wrapper, {@code { of:
     * !meta.array { … } }}, its construction bound by {@code SchemaModelNameBinder} through the same compiled {@code
     * meta.array} reader a schema's {@code !meta.array { … }} goes through.
     */
    @Nested
    class RealModel {

        static final String MODEL_ID = "https://example.test/scratch-type-expr-model.tn";

        static final String MODEL_SCHEMA = """
                !!id:"https://example.test/scratch-type-expr-model.tn"
                !!import:"%s"
                !!import:"%s"
                !schema {
                  slot_holder  => { slot: meta.type_expr }
                  slots_holder => { slots: [meta.type_expr] }
                  ref_holder   => { slot: meta.type_ref }
                }""".formatted(TsonBundledSchemas.META_ID, TsonBundledSchemas.CORE_ID);

        public record SlotHolder(TypeExpr slot) {
        }

        public record SlotsHolder(List<TypeExpr> slots) {
        }

        public record RefHolder(TypeRef slot) {
        }

        static Tson model() {
            SchemaSource source = uri -> MODEL_SCHEMA;
            DataBindContext context = DataBindContext.builder()
                    .nameBinder(DataNameBinder.ofMap(Map.of("slot_holder", SlotHolder.class,
                            "slots_holder", SlotsHolder.class, "ref_holder", RefHolder.class))
                            .orElse(SchemaModelNameBinder.INSTANCE))
                    .registerAtoms(AtomContext.hostTypes())
                    .build();
            Tson tson = Tson.of(ProcessorConfig.defaults()
                    .withSchemaAccess(SchemaAccess.of(source))
                    .withDataBindContext(context));
            tson.resolve(MODEL_SCHEMA);
            return tson;
        }

        static String modelDoc(String root) {
            return "!!import:\"" + MODEL_ID + "\"\n" + root;
        }

        static final TypeInline ARRAY_OF_TEXT = new TypeInline(ArrayBody.of(TypeRef.of("text")));

        @Test
        void q2_real_aNameReadsAsATypeRefIntoTheTypeExprSlot() {
            SlotHolder read = model().objectReader().read(modelDoc("!slot_holder { slot: text }"), SlotHolder.class);
            assertEquals(new SlotHolder(TypeRef.of("text")), read);
        }

        @Test
        void q2_real_aWrappedConstructionReadsAsATypeInlineIntoTheTypeExprSlot() {
            SlotHolder read = model().objectReader().read(
                    modelDoc("!slot_holder { slot: { of: !meta.array { element_type: text } } }"), SlotHolder.class);
            assertEquals(new SlotHolder(ARRAY_OF_TEXT), read);
        }

        /** G4: through the slot's choice, the name is read as a TypeRef component reads one, its annotations kept. */
        @Test
        void q2_real_anAnnotationWrittenOnTheNameIsKept() {
            SlotHolder read = model().objectReader().read(modelDoc("!slot_holder { slot: @doc:\"x\" text }"),
                    SlotHolder.class);
            TypeRef name = (TypeRef) read.slot();
            assertTrue(name.annotations().get("doc").isPresent(), name::toString);
        }

        /** The control for G4: at a slot typed meta.type_ref, bound to a TypeRef, the annotation is kept. */
        @Test
        void q2_real_anAnnotationWrittenOnTheNameIsKeptAtAPlainTypeRefSlot() {
            RefHolder read = model().objectReader().read(modelDoc("!ref_holder { slot: @doc:\"x\" text }"),
                    RefHolder.class);
            assertTrue(read.slot().annotations().get("doc").isPresent(), read::toString);
        }

        @Test
        void q4_real_aNameWritesAsItsBareToken() {
            assertEquals("{ slot: text }", model().objectWriter().toTson(new SlotHolder(TypeRef.of("text"))));
        }

        @Test
        void q4_real_anInlineArrayRoundTrips() {
            Tson tson = model();
            String written = tson.objectWriter().toTson(new SlotHolder(ARRAY_OF_TEXT));
            assertEquals(new SlotHolder(ARRAY_OF_TEXT),
                    tson.objectReader().read(modelDoc("!slot_holder " + written), SlotHolder.class));
        }

        @Test
        void q4_real_aNestedInlineArrayReadsAtEachLevel() {
            SlotHolder read = model().objectReader().read(modelDoc("!slot_holder { slot: { of: !meta.array { "
                    + "element_type: { of: !meta.array { element_type: uuid } } } } }"), SlotHolder.class);
            assertEquals(new SlotHolder(new TypeInline(ArrayBody.of(new TypeInline(ArrayBody.of(
                    TypeRef.of("uuid")))))), read);
        }

        /** {@code of} is typed by the abstract meta.type, which admits a record; the wrapper itself refuses one. */
        @Test
        void q5_real_aRecordInTheWrapperIsRefused() {
            RuntimeException refused = assertThrows(RuntimeException.class, () -> model().objectReader().read(
                    modelDoc("!slot_holder { slot: { of: !meta.record { fields: { a => { type: text } } } } }"),
                    SlotHolder.class));
            assertTrue(refused.getMessage().contains("declared and named"), refused::toString);
        }

        @Test
        void q6_real_aListOfSlotsReadsNamesAndInlineTypesMixed() {
            SlotsHolder read = model().objectReader().read(modelDoc(
                    "!slots_holder { slots: [ text  { of: !meta.array { element_type: text } }  int32 ] }"),
                    SlotsHolder.class);
            assertEquals(new SlotsHolder(List.of(TypeRef.of("text"), ARRAY_OF_TEXT, TypeRef.of("int32"))), read);
        }

        @Test
        void q6_real_aListOfSlotsRoundTrips() {
            Tson tson = model();
            SlotsHolder value = new SlotsHolder(List.of(TypeRef.of("text"), ARRAY_OF_TEXT));
            String written = tson.objectWriter().toTson(value);
            assertEquals(value, tson.objectReader().read(modelDoc("!slots_holder " + written), SlotsHolder.class));
        }
    }
}