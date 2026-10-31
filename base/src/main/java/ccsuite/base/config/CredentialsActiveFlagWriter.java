package ccsuite.base.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.Pattern;

/**
 * Surgically toggles the {@code claudecodeactive} attribute on {@code <CCAPICredential>}
 * elements inside CCAPIsCredentials.xml <em>without</em> re-serializing the file. The file
 * is hand-maintained and carries XML comments (including commented-out credential blocks);
 * a JAXB round-trip would wipe all of that, so we edit the raw text instead.
 *
 * <p>Used by claudeswap after a switch to mark the just-activated account so the usage
 * tracker can list it first. Elements inside {@code <!-- ... -->} are left untouched.
 */
public final class CredentialsActiveFlagWriter {

    private CredentialsActiveFlagWriter() {}

    private static final String TAG = "<CCAPICredential";
    private static final Pattern ID = Pattern.compile("\\bid\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ACTIVE = Pattern.compile("\\bclaudecodeactive\\s*=\\s*\"([^\"]*)\"");

    /**
     * Ensure {@code claudecodeactive="true"} on the credential whose id equals {@code activeId},
     * and flip any other credential currently advertising {@code "true"} back to {@code "false"}.
     * Credentials without the attribute are left as-is. Writes atomically only if something changed.
     *
     * @return true if the file was modified
     */
    public static boolean setActive(Path xmlFile, String activeId) throws IOException {
        if (xmlFile.getFileName().toString().endsWith(".json")) return setActiveJson(xmlFile, activeId);
        var src = Files.readString(xmlFile, StandardCharsets.UTF_8);
        var out = new StringBuilder(src.length() + 64);
        var changed = false;
        var i = 0;
        var n = src.length();
        while (i < n) {
            if (src.startsWith("<!--", i)) {
                var end = src.indexOf("-->", i + 4);
                end = end < 0 ? n : end + 3;
                out.append(src, i, end);
                i = end;
            } else if (src.startsWith(TAG, i) && !isNameChar(src, i + TAG.length())) {
                var end = elementEnd(src, i);
                var elem = src.substring(i, end);
                var rewritten = rewrite(elem, activeId);
                if (!rewritten.equals(elem)) changed = true;
                out.append(rewritten);
                i = end;
            } else {
                out.append(src.charAt(i));
                i++;
            }
        }
        if (changed) atomicWrite(xmlFile, out.toString());
        return changed;
    }

    /** The JSON file has no comments to keep, so it is loaded, changed and saved whole. */
    static boolean setActiveJson(Path file, String activeId) throws IOException {
        try {
            var changed = new boolean[1];
            new CredentialsManager(file).update(all -> {
                for (var c : all.credentials()) {
                    var want = c.id().equals(activeId);
                    if (c.claudecodeactive() != want) { c.claudecodeactive(want); changed[0] = true; }
                }
            });
            return changed[0];
        } catch (jakarta.xml.bind.JAXBException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Index of just past the element's closing {@code >}, honoring quoted attribute values. */
    private static int elementEnd(String s, int start) {
        var inStr = false;
        for (var p = start; p < s.length(); p++) {
            var c = s.charAt(p);
            if (inStr) { if (c == '"') inStr = false; }
            else if (c == '"') inStr = true;
            else if (c == '>') return p + 1;
        }
        return s.length();
    }

    private static String rewrite(String elem, String activeId) {
        var idm = ID.matcher(elem);
        if (!idm.find()) return elem;
        var isTarget = idm.group(1).equals(activeId);
        var desired = isTarget ? "true" : "false";

        var am = ACTIVE.matcher(elem);
        if (am.find()) {
            if (am.group(1).equals(desired)) return elem;        // already correct
            if (!isTarget && !"true".equals(am.group(1))) return elem; // non-target already non-true
            return elem.substring(0, am.start()) + "claudecodeactive=\"" + desired + "\"" + elem.substring(am.end());
        }
        if (!isTarget) return elem;                              // don't add the attr to other accounts
        // Insert right after id="...", reusing the indentation of the id line.
        var lineStart = elem.lastIndexOf('\n', idm.start());
        var sep = lineStart >= 0 ? elem.substring(lineStart, idm.start()) : " ";
        return elem.substring(0, idm.end()) + sep + "claudecodeactive=\"true\"" + elem.substring(idm.end());
    }

    private static boolean isNameChar(String s, int idx) {
        if (idx >= s.length()) return false;
        var c = s.charAt(idx);
        return Character.isLetterOrDigit(c) || c == '_' || c == '-';
    }

    /** Same-dir temp file + atomic rename, so a concurrent reader never sees a partial file. */
    private static void atomicWrite(Path target, String content) throws IOException {
        var dir = target.getParent();
        var tmp = Files.createTempFile(dir, ".ccaflag-", ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            SafeFiles.replace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
