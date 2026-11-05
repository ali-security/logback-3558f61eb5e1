/**
 * Logback: the reliable, generic, fast and flexible logging framework.
 * Copyright (C) 1999-2015, QOS.ch. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 */
package ch.qos.logback.classic.sift;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.sift.AbstractDiscriminator;
import ch.qos.logback.core.spi.ScanException;
import ch.qos.logback.core.util.OptionHelper;

import java.util.Map;

/**
 * MDCBasedDiscriminator essentially returns the value mapped to an MDC key. If
 * the said value is null, then a default value is returned.
 * <p/>
 * <p>
 * Both Key and the DefaultValue are user specified properties.
 * </p>
 * <p>
 * MDC values which could point outside the directory of a file name built from
 * them, e.g. {@code ../x}, {@code /x}, {@code x${file.separator}..} or
 * {@code x%d{/.}.}, are rejected and the DefaultValue is returned instead.
 * </p>
 *
 * @author Ceki G&uuml;lc&uuml;
 */
public class MDCBasedDiscriminator extends AbstractDiscriminator<ILoggingEvent> {

    private static final char FORWARD_SLASH = '/';
    private static final char BACKWARD_SLASH = '\\';
    private static final char DOT = '.';
    private static final char SPACE = ' ';
    private static final char PERCENT = '%';
    private static final String VARIABLE_START = "${";
    private static final String REGEX_ALTERNATION_AND_CLASS_CHARACTERS = "|[]";
    private static final String REGEX_QUANTIFIER_CHARACTERS = "+*?";
    private static final char RIGHT_PARENTHESIS = ')';
    private static final char LEFT_BRACE = '{';
    private static final char RIGHT_BRACE = '}';
    private static final char COMMA = ',';

    private String key;
    private String defaultValue;

    /**
     * Return the value associated with an MDC entry designated by the Key property.
     * If that value is null, then return the value assigned to the DefaultValue
     * property.
     * <p>
     * The value assigned to the DefaultValue property is also returned if the MDC
     * value could point outside the directory of a file name built from it, see
     * {@link #isSafeDiscriminatingValue(String)}.
     * </p>
     */
    public String getDiscriminatingValue(ILoggingEvent event) {
        // http://jira.qos.ch/browse/LBCLASSIC-213
        Map<String, String> mdcMap = event.getMDCPropertyMap();
        if (mdcMap == null) {
            return defaultValue;
        }
        String mdcValue = mdcMap.get(key);
        if (mdcValue == null || !isSafeDiscriminatingValue(mdcValue)) {
            return defaultValue;
        } else {
            return mdcValue;
        }
    }

    /**
     * Returns {@code false} if the value, once substituted into the file name of a
     * nested appender, could point outside the configured directory. That is the
     * case if it starts with {@code /} or {@code \}, or if one of its {@code /} or
     * {@code \} separated segments is a parent reference such as {@code ..}.
     * <p>
     * A value which starts with a drive prefix such as {@code C:} is also rejected
     * if it contains {@code /}, {@code \} or {@code ..}, because on Windows it is
     * then an absolute path or a drive-relative path leaving the directory, as in
     * the official fix. A bare {@code C:x} is accepted, as in the official fix.
     * </p>
     * <p>
     * It is also the case if the value contains {@code ${}, which the nested
     * configuration would expand as a variable reference such as
     * <code>${file.separator}</code>, or {@code %}, which a rolling file name
     * pattern would expand as a conversion such as <code>%d{/}</code>. Both can
     * produce path separators and parent references which are not visible in the
     * value itself.
     * </p>
     * <p>
     * A rolling file name pattern copies the value unescaped into the regex which
     * selects archived files for removal, so values are rejected as well if they
     * contain {@code |}, {@code [} or {@code ]}, or a quantifier which could apply
     * to more than a literal character of the value and let a value such as
     * {@code .+} match the archives of other values: {@code +}, {@code *} or
     * {@code ?} as the first character or right after {@code .} or {@code )}, or
     * a repetition such as {@code {2}} or <code>{0,40}</code>.
     * </p>
     * <p>
     * The nested configuration also parses the value again, where a {@code :-}
     * outside a variable reference or an unmatched <code>}</code> ends it, so
     * {@code ..:-x} or <code>..}x</code> turns into {@code ..}. Such a value is
     * rejected if what is left of it is.
     * </p>
     * <p>
     * An empty value is rejected too, since it disappears from the file name and
     * can turn a relative path such as <code>${key}/app.log</code> into an
     * absolute one.
     * </p>
     * <p>
     * Separators inside the value are allowed, so that values such as
     * {@code tenantA/user1} keep mapping to nested directories.
     * </p>
     * <p>
     * A first or last segment made only of a single dot and spaces, as in
     * {@code ./x} or {@code x/.}, is rejected too, since a literal dot of the
     * file name template next to the value, as in <code>.${key}</code>, turns it
     * into a parent reference.
     * </p>
     * <p>
     * These checks apply to the value with leading and trailing whitespace
     * removed, since logback-core trims the file name it builds
     * ({@code FileAppender.setFile}), so {@code " /x"} would otherwise become the
     * absolute path {@code /x} when the value starts the file name.
     * </p>
     */
    static boolean isSafeDiscriminatingValue(String value) {
        // logback-core trims the file name, so " /x" turns into the absolute path "/x"
        value = value.trim();
        final int len = value.length();
        // an empty value disappears from the file name, so a template such as
        // "${key}/app.log" would turn into the absolute path "/app.log"
        if (len == 0) {
            return false;
        }
        char first = value.charAt(0);
        if (isPathSeparator(first)) {
            return false;
        }
        // on Windows a drive prefix makes the value absolute or drive-relative; the official fix
        // rejects every separator and "..", so reject those behind a drive prefix to match it
        if (len > 1 && value.charAt(1) == ':' && isAsciiLetter(first)
                && (value.indexOf(FORWARD_SLASH) != -1 || value.indexOf(BACKWARD_SLASH) != -1
                        || value.contains(".."))) {
            return false;
        }
        if (value.indexOf(VARIABLE_START) != -1 || value.indexOf(PERCENT) != -1) {
            return false;
        }
        for (int i = 0; i < REGEX_ALTERNATION_AND_CLASS_CHARACTERS.length(); i++) {
            if (value.indexOf(REGEX_ALTERNATION_AND_CLASS_CHARACTERS.charAt(i)) != -1) {
                return false;
            }
        }
        if (containsRegexQuantifier(value)) {
            return false;
        }
        // the nested configuration parses the value again, where a ":-" outside a
        // variable reference or an unmatched '}' ends it, so "..:-x" turns into "..";
        // parsing what is left again returns it unchanged
        String parsed = parsedByNestedConfiguration(value);
        if (!parsed.equals(value) && !isSafeDiscriminatingValue(parsed)) {
            return false;
        }

        // a segment made only of dots and spaces with two or more dots, such as "..",
        // is treated as a parent reference since Windows trims trailing dots and
        // spaces from path segments; the first and the last segment are rejected with
        // a single dot already, since a literal dot of the file name template next to
        // the value, as in ".${key}", turns "./x" into "../x"; characters up to ' '
        // count as spaces, since logback-core trims them from the file name
        int segmentStart = 0;
        int dots = 0;
        boolean onlyDotsAndSpaces = true;
        for (int i = 0; i <= len; i++) {
            char c = i < len ? value.charAt(i) : FORWARD_SLASH;
            if (isPathSeparator(c)) {
                int minDots = (segmentStart == 0 || i == len) ? 1 : 2;
                if (onlyDotsAndSpaces && dots >= minDots) {
                    return false;
                }
                segmentStart = i + 1;
                dots = 0;
                onlyDotsAndSpaces = true;
            } else if (c == DOT) {
                dots++;
            } else if (c > SPACE) {
                onlyDotsAndSpaces = false;
            }
        }
        return true;
    }

    // only called once values containing "${" are rejected, so no variable is looked up
    private static String parsedByNestedConfiguration(String value) {
        if (value.indexOf(CoreConstants.CURLY_RIGHT) == -1
                && !value.contains(CoreConstants.DEFAULT_VALUE_SEPARATOR)) {
            return value;
        }
        try {
            return OptionHelper.substVars(value, null, null);
        } catch (ScanException | RuntimeException e) {
            // the nested configuration fails on it too and builds no file name from it;
            // its parser throws an IllegalArgumentException on input such as "{}{"
            return value;
        }
    }

    /**
     * Returns {@code true} if the value contains a regex quantifier which could
     * apply to more than a literal character of the value: {@code +}, {@code *} or
     * {@code ?} as the first character, where a literal of the file name template
     * such as {@code .} may precede it, or right after {@code .} or {@code )}; or
     * a repetition matching <code>\{\d+(,\d*)?\}</code>.
     */
    private static boolean containsRegexQuantifier(String value) {
        final int len = value.length();
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (REGEX_QUANTIFIER_CHARACTERS.indexOf(c) != -1) {
                if (i == 0) {
                    return true;
                }
                char previous = value.charAt(i - 1);
                if (previous == DOT || previous == RIGHT_PARENTHESIS) {
                    return true;
                }
            } else if (c == LEFT_BRACE && isRepetitionAt(value, i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the value matches <code>\{\d+(,\d*)?\}</code> at
     * index {@code i}, which holds a <code>{</code>.
     */
    private static boolean isRepetitionAt(String value, int i) {
        final int len = value.length();
        int j = i + 1;
        while (j < len && isAsciiDigit(value.charAt(j))) {
            j++;
        }
        if (j == i + 1) {
            return false;
        }
        if (j < len && value.charAt(j) == COMMA) {
            j++;
            while (j < len && isAsciiDigit(value.charAt(j))) {
                j++;
            }
        }
        return j < len && value.charAt(j) == RIGHT_BRACE;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isPathSeparator(char c) {
        return c == FORWARD_SLASH || c == BACKWARD_SLASH;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    @Override
    public void start() {
        int errors = 0;
        if (OptionHelper.isNullOrEmpty(key)) {
            errors++;
            addError("The \"Key\" property must be set");
        }
        if (OptionHelper.isNullOrEmpty(defaultValue)) {
            errors++;
            addError("The \"DefaultValue\" property must be set");
        }
        if (errors == 0) {
            started = true;
        }
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    /**
     * @return
     * @see #setDefaultValue(String)
     */
    public String getDefaultValue() {
        return defaultValue;
    }

    /**
     * The default MDC value in case the MDC is not set for {@link #setKey(String)
     * mdcKey}.
     * <p/>
     * <p>
     * For example, if {@link #setKey(String) Key} is set to the value "someKey",
     * and the MDC is not set for "someKey", then this appender will use the default
     * value, which you can set with the help of this method.
     *
     * @param defaultValue
     */
    public void setDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
    }
}
