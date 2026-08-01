package io.ltr8.tson.cli;

import io.ltr8.tson.compiler.ContentHash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code tson hash <file>} -- computes a document's content hash ([TSON-DATA] §2.2.1) and stamps it
 * onto the {@code !!id} as a {@code ?sha256=<hex>} query, in place. Requires an {@code !!id} on the
 * first line; the id line is excluded from the hash, so stamping it is self-consistent and re-running
 * replaces the previous pin. The first step toward hash-pinned schema references -- once a schema
 * carries its own hash, other documents can pin their {@code !!import}/{@code !!schema} references to it.
 */
final class HashCommand {

    private HashCommand() {
    }

    /** @return exit code: 0 written, 1 the file couldn't be read/written or has no usable {@code !!id} line */
    static int run(Path file) {
        byte[] document;
        try {
            document = Files.readAllBytes(file);
        } catch (IOException e) {
            System.err.println("cannot read " + file + ": " + e.getMessage());
            return 1;
        }

        int contentStart;
        try {
            contentStart = ContentHash.contentStart(document);
        } catch (RuntimeException e) {
            System.err.println(file + ": " + e.getMessage());
            return 1;
        }

        // The prefix (an optional BOM + the !!id line + its terminator), decoded so we can rewrite only
        // the id's URI while leaving the terminator -- and every hashed byte after it -- untouched.
        String header = new String(document, 0, contentStart, StandardCharsets.UTF_8);
        int idStart = header.startsWith("\uFEFF") ? 1 : 0;   // skip a leading BOM if present
        if (!header.startsWith("!!id", idStart)) {
            System.err.println(file + ": the first line must be an !!id directive to content-hash it");
            return 1;
        }
        int openQuote = header.indexOf('"', idStart);
        int closeQuote = openQuote < 0 ? -1 : header.indexOf('"', openQuote + 1);
        if (closeQuote < 0) {
            System.err.println(file + ": the !!id directive has no quoted URI");
            return 1;
        }

        String uri = header.substring(openQuote + 1, closeQuote);
        int query = uri.indexOf('?');
        String base = query < 0 ? uri : uri.substring(0, query);   // drop any prior ?sha256=... (or other query)
        String hash = ContentHash.sha256(document);
        String pinned = base + "?sha256=" + hash;

        String newHeader = header.substring(0, openQuote + 1) + pinned + header.substring(closeQuote);
        byte[] out = concat(newHeader.getBytes(StandardCharsets.UTF_8), document, contentStart);
        try {
            Files.write(file, out);
        } catch (IOException e) {
            System.err.println("cannot write " + file + ": " + e.getMessage());
            return 1;
        }

        System.out.println("Pinned " + file + "  sha256=" + hash);
        return 0;
    }

    private static byte[] concat(byte[] head, byte[] tail, int tailFrom) {
        byte[] out = new byte[head.length + (tail.length - tailFrom)];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, tailFrom, out, head.length, tail.length - tailFrom);
        return out;
    }
}
