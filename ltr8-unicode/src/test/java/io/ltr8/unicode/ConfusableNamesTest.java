package io.ltr8.unicode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Skeleton distinctness over one scope. A small scope is compared pairwise and a large one
 * through a {@link ConfusableNames.Scope}, so every rule here is asserted at both sizes: the two must answer
 * alike.
 *
 * <p>Confusable characters are written as escapes, never literally.
 */
class ConfusableNamesTest {

    /** Latin {@code pass}'s all-Cyrillic look-alike: U+0440 U+0430 U+0455 U+0455. */
    private static final String CYRILLIC_PASS = "раѕѕ";

    /** {@code names} after enough distinct filler to take the {@link ConfusableNames.Scope} path. */
    private static List<String> large(String... names) {
        List<String> padded = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            padded.add("filler" + (char) ('a' + i));
        }
        padded.addAll(List.of(names));
        return padded;
    }

    private static Optional<ConfusableNames.Collision> small(String... names) {
        return ConfusableNames.firstCollision(List.of(names));
    }

    @Test
    void aPairIsReportedEarlierNameFirst() {
        ConfusableNames.Collision expected = new ConfusableNames.Collision("pass", CYRILLIC_PASS);
        assertEquals(Optional.of(expected), small("id", "pass", "note", CYRILLIC_PASS));
        assertEquals(Optional.of(expected), ConfusableNames.firstCollision(large("pass", CYRILLIC_PASS)));
    }

    /** ASCII alone can collide: {@code m} reads as {@code rn}. */
    @Test
    void anAsciiPairCollidesToo() {
        assertEquals(Optional.of(new ConfusableNames.Collision("payment", "payrnent")), small("payment", "payrnent"));
        assertEquals(Optional.of(new ConfusableNames.Collision("payment", "payrnent")),
                ConfusableNames.firstCollision(large("payment", "payrnent")));
    }

    /** A repeat is a duplicate, which is another rule's; it collides with nothing. */
    @Test
    void aRepeatedNameCollidesWithNothing() {
        assertEquals(Optional.empty(), small("pass", "pass"));
        assertEquals(Optional.empty(), ConfusableNames.firstCollision(large("pass", "pass")));
        assertEquals(Optional.of(new ConfusableNames.Collision("pass", CYRILLIC_PASS)),
                small("pass", "pass", CYRILLIC_PASS));
    }

    @Test
    void distinguishableNamesAndTinyScopesPass() {
        assertEquals(Optional.empty(), small("id", "customer", "placed", "lines", "note"));
        assertEquals(Optional.empty(), ConfusableNames.firstCollision(large("id", "customer")));
        assertEquals(Optional.empty(), small());
        assertEquals(Optional.empty(), small("pass"));
    }
}
