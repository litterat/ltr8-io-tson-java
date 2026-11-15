package io.ltr8.tson;

import io.ltr8.bind.DataNameBinder;
import io.ltr8.tson.base.CanonicalIdentity;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static io.ltr8.tson.ExternWorld.ORDER_1;
import static io.ltr8.tson.ExternWorld.ORDER_2;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A class is bound by its entry's identity: the schema that declares the entry and its name there. Two versions of one
 * schema that both declare {@code order}, registered in one processor, bind each {@code order} to its own class through
 * one bind context, and an entry two schemas share through a common import binds once.
 */
public class BindByEntryIdentityTest {

    static final String COMMON = "https://example.test/common.tn";
    static final String BILLING = "https://example.test/billing.tn";
    static final String REFUNDS = "https://example.test/refunds.tn";

    public record CardOne(String number) {
    }

    public record OrderOne(String id, CardOne payment) {
    }

    public record CardTwo(String pan) {
    }

    public record Line(String sku, int qty) {
    }

    public record OrderTwo(String ref, List<Line> lines, CardTwo payment) {
    }

    public record Rush(String ref, List<Line> lines, CardTwo payment, String by) {
    }

    public record Money(long cents) {
    }

    public record Bill(Money total) {
    }

    public record Refund(Money amount) {
    }

    private final ExternWorld world = new ExternWorld()
            .schema(COMMON, "money => { cents: int64 }\n")
            .schema(BILLING, "bill => { total: money }\n", COMMON)
            .schema(REFUNDS, "refund => { amount: money }\n", COMMON);

    private static String identity(String uri) {
        return CanonicalIdentity.canonicalize(uri);
    }

    @Test
    void twoVersionsDeclaringOrderBindEachToItsOwnClassThroughOneContext() {
        Tson tson = world.tson(DataNameBinder.byNamespace(Map.of(
                identity(ORDER_1), DataNameBinder.ofMap(Map.of("order", OrderOne.class, "card", CardOne.class)),
                identity(ORDER_2), DataNameBinder.ofMap(Map.of("order", OrderTwo.class, "card", CardTwo.class,
                        "line", Line.class, "rush", Rush.class))), DataNameBinder.ofMap(Map.of())));

        assertEquals(new OrderOne("a", new CardOne("n")), tson.objectReader().read(
                "!!import:\"" + ORDER_1 + "\"\n!order { id: a  payment: { number: n } }", OrderOne.class));
        assertEquals(new OrderTwo("r", List.of(new Line("s", 2)), new CardTwo("p")), tson.objectReader().read(
                "!!import:\"" + ORDER_2 + "\"\n!order { ref: r  lines: [{ sku: s  qty: 2 }]  payment: { pan: p } }",
                OrderTwo.class));
    }

    @Test
    void anEntrySharedThroughACommonImportBindsWithinTheSchemaDeclaringIt() {
        Tson tson = world.tson(DataNameBinder.byNamespace(Map.of(
                identity(BILLING), DataNameBinder.ofMap(Map.of("bill", Bill.class)),
                identity(REFUNDS), DataNameBinder.ofMap(Map.of("refund", Refund.class)),
                identity(COMMON), DataNameBinder.ofMap(Map.of("money", Money.class))),
                DataNameBinder.ofMap(Map.of())));

        assertEquals(new Bill(new Money(5)), tson.objectReader().read(
                "!!import:\"" + BILLING + "\"\n!bill { total: { cents: 5 } }", Bill.class));
        assertEquals(new Refund(new Money(7)), tson.objectReader().read(
                "!!import:\"" + REFUNDS + "\"\n!refund { amount: { cents: 7 } }", Refund.class));
    }
}
