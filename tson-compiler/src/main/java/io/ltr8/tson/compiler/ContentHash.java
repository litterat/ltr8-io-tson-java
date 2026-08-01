package io.ltr8.tson.compiler;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A document's content hash ([TSON-DATA] §2.2.1): SHA-256, lowercase hex at full length, of every byte
 * after the first line's terminator. The first line is the {@code !!id} line (the grammar places the
 * id directive at the very start), so the id line -- up to and including its terminator -- is excluded;
 * that lets a document carry its own hash on its own id line without the circularity of hashing it. A
 * leading byte-order mark is stripped and never enters the hash input, and a content-addressed document
 * MUST be UTF-8.
 */
public final class ContentHash {

    private ContentHash() {
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
