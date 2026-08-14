package io.ltr8.tson.compiler;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * A document's content hash ([TSON-DATA] §2.2.1): SHA-256, lowercase hex at full length, of every byte
 * after the first line's terminator. The first line is the {@code !!id} line (the grammar places the
 * id directive at the very start), so the id line -- up to and including its terminator -- is excluded;
 * that lets a document carry its own hash on its own id line without the circularity of hashing it. A
 * leading byte-order mark is stripped and never enters the hash input, and a content-addressed document
 * MUST be UTF-8.
 */
public final class TsonContentHash {

    private TsonContentHash() {
    }

    /** The lowercase-hex SHA-256 over every byte from {@link #contentStart} to the end. */
    public static String sha256(byte[] document) {
        int start = contentStart(document);
        MessageDigest digest = sha256Digest();
        digest.update(document, start, document.length - start);
        return toHex(digest.digest());
    }

    /**
     * The index where the hash input begins -- past a leading BOM and past the first line's terminator
     * (for {@code CR LF}, after the {@code LF}).
     *
     * @throws IllegalArgumentException if the first line has no terminator, so there is no hash-input boundary
     */
    public static int contentStart(byte[] document) {
        int i = document.length >= 3 && (document[0] & 0xFF) == 0xEF
                && (document[1] & 0xFF) == 0xBB && (document[2] & 0xFF) == 0xBF ? 3 : 0;
        for (; i < document.length; i++) {
            int b = document[i] & 0xFF;
            if (b == 0x0A) {
                return i + 1;                                                                      // LF
            }
            if (b == 0x0D) {
                return i + 1 < document.length && (document[i + 1] & 0xFF) == 0x0A ? i + 2 : i + 1; // CR LF or CR
            }
            if (b == 0xC2 && i + 1 < document.length && (document[i + 1] & 0xFF) == 0x85) {
                return i + 2;                                                                       // NEL U+0085
            }
            if (b == 0xE2 && i + 2 < document.length && (document[i + 1] & 0xFF) == 0x80) {
                int c = document[i + 2] & 0xFF;
                if (c == 0xA8 || c == 0xA9) {
                    return i + 3;                                                                   // LS U+2028 / PS U+2029
                }
            }
        }
        throw new IllegalArgumentException("the first line has no terminator -- a content-addressed "
                + "document must follow its !!id line with one ([TSON-DATA] §2.2.1)");
    }

    /**
     * The {@code sha256} content-hash pin declared on a reference URI's query ({@code ?sha256=<hex>}),
     * or empty if the URI carries no query. Per [TSON-DATA] §2.2.1 a content-address query may contain
     * *only* recognized hash-algorithm parameters, and the value is full-length (64) lowercase hex.
     *
     * @throws IllegalArgumentException if the query carries an unrecognized parameter name or a
     *     malformed {@code sha256} value (never silently retained)
     */
    public static Optional<String> declaredSha256(String uri) {
        int q = uri.indexOf('?');
        if (q < 0 || q == uri.length() - 1) {
            return Optional.empty();
        }
        String sha256 = null;
        for (String param : uri.substring(q + 1).split("&")) {
            int eq = param.indexOf('=');
            String name = eq < 0 ? param : param.substring(0, eq);
            if (!name.equals("sha256")) {
                throw new IllegalArgumentException("unrecognized query parameter '" + name + "' in \"" + uri
                        + "\" -- a content-address query may contain only hash-algorithm parameters ([TSON-DATA] §2.2.1)");
            }
            String value = eq < 0 ? "" : param.substring(eq + 1);
            if (!isFullLowercaseHex(value)) {
                throw new IllegalArgumentException("malformed sha256 pin \"" + value + "\" in \"" + uri
                        + "\" -- expected 64 lowercase hex digits ([TSON-DATA] §2.2.1)");
            }
            sha256 = value;
        }
        return Optional.ofNullable(sha256);
    }

    /**
     * Verifies {@code content} against the {@code sha256} pin declared on {@code referenceUri}, if any --
     * the [TSON-DATA] §2.2.1 rule that a consumer holding a hashed reference MUST verify before use and
     * MUST NOT use mismatched content. A reference with no pin is a no-op (resolves unverified).
     *
     * @throws TsonContentHashMismatchException if a pin is declared and the content's hash differs from it
     */
    public static void verify(byte[] content, String referenceUri) {
        declaredSha256(referenceUri).ifPresent(declared -> {
            String actual = sha256(content);
            if (!actual.equals(declared)) {
                throw new TsonContentHashMismatchException("content hash mismatch for \"" + referenceUri
                        + "\": the reference declares sha256=" + declared + " but the content hashes to "
                        + actual + " -- refusing to use mismatched content ([TSON-DATA] §2.2.1)");
            }
        });
    }

    private static boolean isFullLowercaseHex(String value) {
        if (value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
