package io.ltr8.net;

/**
 * RFC 3492's Punycode with IDNA's parameters (§5): the bootstring encoding an A-label carries its U-label in.
 * Pure arithmetic over code points, with no table. Null-returning, never throwing, as {@link InternetAddress} is.
 *
 * <p>Neither direction applies IDNA's rules -- {@code xn--}, validity, case -- which are {@link HostName}'s.
 * Basic code points are copied as given, so a caller folds case before decoding.
 */
public final class Punycode {

    private static final int BASE = 36;
    private static final int TMIN = 1;
    private static final int TMAX = 26;
    private static final int SKEW = 38;
    private static final int DAMP = 700;
    private static final int INITIAL_BIAS = 72;
    private static final int INITIAL_N = 128;

    private Punycode() {
    }

    /** {@code text}'s Punycode, or {@code null} where the arithmetic overflows (RFC 3492 §6.4). */
    public static String encode(String text) {
        int[] input = text.codePoints().toArray();
        StringBuilder output = new StringBuilder(input.length + 8);
        for (int cp : input) {
            if (cp < 0x80) {
                output.append((char) cp);
            }
        }
        int basic = output.length();
        int handled = basic;
        if (basic > 0) {
            output.append('-');
        }
        int n = INITIAL_N;
        int delta = 0;
        int bias = INITIAL_BIAS;
        while (handled < input.length) {
            int m = Integer.MAX_VALUE;
            for (int cp : input) {
                if (cp >= n && cp < m) {
                    m = cp;
                }
            }
            if ((long) (m - n) * (handled + 1) > Integer.MAX_VALUE - delta) {
                return null;
            }
            delta += (m - n) * (handled + 1);
            n = m;
            for (int cp : input) {
                if (cp < n && ++delta == 0) {
                    return null;
                }
                if (cp == n) {
                    int q = delta;
                    for (int k = BASE; ; k += BASE) {
                        int t = k <= bias ? TMIN : Math.min(k - bias, TMAX);
                        if (q < t) {
                            break;
                        }
                        output.append(digit(t + (q - t) % (BASE - t)));
                        q = (q - t) / (BASE - t);
                    }
                    output.append(digit(q));
                    bias = adapt(delta, handled + 1, handled == basic);
                    delta = 0;
                    handled++;
                }
            }
            delta++;
            n++;
        }
        return output.toString();
    }

    /**
     * The text {@code encoded} decodes to, or {@code null} where it is not Punycode: a non-basic code point
     * before the last delimiter, a character that is not a digit after it, a truncated integer, or overflow.
     */
    public static String decode(String encoded) {
        int delimiter = encoded.lastIndexOf('-');
        StringBuilder output = new StringBuilder(encoded.length());
        for (int i = 0; i < Math.max(delimiter, 0); i++) {
            char c = encoded.charAt(i);
            if (c >= 0x80) {
                return null;
            }
            output.append(c);
        }
        int[] codePoints = output.codePoints().toArray();
        int length = codePoints.length;
        int[] result = java.util.Arrays.copyOf(codePoints, Math.max(16, encoded.length()));
        int n = INITIAL_N;
        int i = 0;
        int bias = INITIAL_BIAS;
        for (int in = delimiter > 0 ? delimiter + 1 : 0; in < encoded.length(); ) {
            int oldi = i;
            int w = 1;
            for (int k = BASE; ; k += BASE) {
                if (in >= encoded.length()) {
                    return null;
                }
                int digit = digitValue(encoded.charAt(in++));
                if (digit < 0 || digit > (Integer.MAX_VALUE - i) / w) {
                    return null;
                }
                i += digit * w;
                int t = k <= bias ? TMIN : Math.min(k - bias, TMAX);
                if (digit < t) {
                    break;
                }
                if (w > Integer.MAX_VALUE / (BASE - t)) {
                    return null;
                }
                w *= BASE - t;
            }
            bias = adapt(i - oldi, length + 1, oldi == 0);
            if (i / (length + 1) > Integer.MAX_VALUE - n) {
                return null;
            }
            n += i / (length + 1);
            i %= length + 1;
            if (n > Character.MAX_CODE_POINT || (n >= Character.MIN_SURROGATE && n <= Character.MAX_SURROGATE)) {
                return null;
            }
            if (length == result.length) {
                result = java.util.Arrays.copyOf(result, length * 2);
            }
            System.arraycopy(result, i, result, i + 1, length - i);
            result[i++] = n;
            length++;
        }
        return new String(result, 0, length);
    }

    /** RFC 3492 §6.1's bias adaptation. */
    private static int adapt(int delta, int numPoints, boolean first) {
        delta = first ? delta / DAMP : delta / 2;
        delta += delta / numPoints;
        int k = 0;
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN;
            k += BASE;
        }
        return k + (BASE - TMIN + 1) * delta / (delta + SKEW);
    }

    private static char digit(int d) {
        return (char) (d < 26 ? 'a' + d : '0' + d - 26);
    }

    private static int digitValue(char c) {
        if (c >= 'a' && c <= 'z') {
            return c - 'a';
        }
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 26;
        }
        return -1;
    }
}
