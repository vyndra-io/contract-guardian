package io.contractguardian.util;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Matches file paths against glob patterns.
 *
 * <p>Supports standard glob syntax: {@code *} (any except /),
 * {@code **} (any including /), {@code ?}, and {@code {a,b}} alternatives.
 */
public class GlobMatcher {

    private final List<Pattern> patterns;

    /**
     * Creates a matcher from the given glob patterns.
     *
     * @param globs the glob patterns to match against
     */
    public GlobMatcher(final List<String> globs) {
        this.patterns = globs.stream()
                .map(GlobMatcher::globToRegex)
                .map(Pattern::compile)
                .toList();
    }

    /**
     * Tests whether the given path matches any of the glob patterns.
     *
     * @param relativePath the file path to test
     * @return {@code true} if the path matches at least one pattern
     */
    public boolean matches(final String relativePath) {
        final String normalized = relativePath.replace('\\', '/');
        return patterns.stream().anyMatch(p -> p.matcher(normalized).matches());
    }

    /**
     * Tests whether the given path matches any of the glob patterns.
     *
     * @param relativePath the file path to test
     * @return {@code true} if the path matches at least one pattern
     */
    public boolean matches(final Path relativePath) {
        return matches(relativePath.toString());
    }

    /**
     * Converts a glob pattern to a regular expression string.
     *
     * @param glob the glob pattern
     * @return the equivalent regex
     */
    public static String globToRegex(final String glob) {
        final StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            final char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                            regex.append("(.*/)?");
                            i += 3;
                        } else {
                            regex.append(".*");
                            i += 2;
                        }
                    } else {
                        regex.append("[^/]*");
                        i++;
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    i++;
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                    regex.append('\\').append(c);
                    i++;
                    break;
                case '{':
                    regex.append('(');
                    i++;
                    break;
                case '}':
                    regex.append(')');
                    i++;
                    break;
                case ',':
                    regex.append('|');
                    i++;
                    break;
                default:
                    regex.append(c);
                    i++;
                    break;
            }
        }
        return regex.toString();
    }
}
