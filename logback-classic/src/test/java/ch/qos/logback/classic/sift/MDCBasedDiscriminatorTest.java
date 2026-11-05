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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;
import ch.qos.logback.core.status.testUtil.StatusChecker;
import ch.qos.logback.core.testUtil.RandomUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Ceki G&uuml;lc&uuml;
 */
public class MDCBasedDiscriminatorTest {

    static String DEFAULT_VAL = "DEFAULT_VAL";

    MDCBasedDiscriminator discriminator = new MDCBasedDiscriminator();
    LoggerContext loggerContext = new LoggerContext();
    LogbackMDCAdapter logbackMDCAdapter = new LogbackMDCAdapter();
    Logger logger = loggerContext.getLogger(this.getClass());

    int diff = RandomUtil.getPositiveInt();
    String key = "MDCBasedDiscriminatorTest_key" + diff;
    String value = "MDCBasedDiscriminatorTest_val" + diff;
    LoggingEvent event;

    @BeforeEach
    public void setUp() {
        loggerContext.setMDCAdapter(logbackMDCAdapter);
        discriminator.setContext(loggerContext);
        discriminator.setKey(key);
        discriminator.setDefaultValue(DEFAULT_VAL);
        discriminator.start();
        assertTrue(discriminator.isStarted());
    }

    @AfterEach
    public void teaDown() {
        MDC.clear();
    }

    @Test
    public void smoke() {
        logbackMDCAdapter.put(key, value);
        event = new LoggingEvent("a", logger, Level.DEBUG, "", null, null);

        String discriminatorValue = discriminator.getDiscriminatingValue(event);
        assertEquals(value, discriminatorValue);
    }

    @Test
    public void nullMDC() {
        event = new LoggingEvent("a", logger, Level.DEBUG, "", null, null);
        assertEquals(new HashMap<String, String>(), event.getMDCPropertyMap());
        String discriminatorValue = discriminator.getDiscriminatingValue(event);
        assertEquals(DEFAULT_VAL, discriminatorValue);
    }

    /**
     * Path characters in the MDC value which could point outside the directory of
     * a file name built from it cause the value to be rejected (e.g. SiftingAppender
     * nested FileAppenders).
     */
    @Test
    public void pathCharactersYieldDefaultValue() {
        logbackMDCAdapter.put(key, "a/b\\..\\..\\c" + value);
        event = new LoggingEvent("a", logger, Level.DEBUG, "", null, null);
        assertEquals(DEFAULT_VAL, discriminator.getDiscriminatingValue(event));

        logbackMDCAdapter.put(key, "///foo\\\\bar//baz\\");
        event = new LoggingEvent("a", logger, Level.DEBUG, "", null, null);
        assertEquals(DEFAULT_VAL, discriminator.getDiscriminatingValue(event));
    }

    /**
     * MDC values which could point outside the directory of a file name built from
     * them yield the default value.
     */
    @Test
    public void pathTraversalValuesYieldDefaultValue() {
        String[] traversalValues = { "..", "../x", "..\\x", "x/../../y", "x\\..\\y", "x/..", "x/ ../y", "x/.. /y",
                "x/.../y", "/etc/passwd", "\\\\server\\share", "C:\\x", "c:/x", "C:sub\\x", "C:..\\x", "C:..",
                "C:a..b" };
        for (String traversal : traversalValues) {
            logbackMDCAdapter.put(key, traversal);
            assertEquals(DEFAULT_VAL, discriminatingValue(), traversal);
        }
    }

    /**
     * A first or last segment made only of a single dot and spaces yields the
     * default value, since a literal dot of the file name template next to the
     * value, as in ".${key}", turns "./x" into "../x". Characters up to ' '
     * count as spaces, since logback-core trims them from the file name. A single
     * dot inside the value is returned unchanged.
     */
    @Test
    public void singleDotFirstOrLastSegmentYieldsDefaultValue() {
        String[] dotSegmentValues = { ".", "./x", "x/.", " ./x", "\t./x", ".\\x", "x\\.", ". /x", "x/ .", "./",
                "x/..\t/y" };
        for (String dotSegment : dotSegmentValues) {
            logbackMDCAdapter.put(key, dotSegment);
            assertEquals(DEFAULT_VAL, discriminatingValue(), dotSegment);
        }

        String[] innerDotValues = { "x/./y", ".x", "x.", "x/.y/z", "x./y" };
        for (String innerDot : innerDotValues) {
            logbackMDCAdapter.put(key, innerDot);
            assertEquals(innerDot, discriminatingValue(), innerDot);
        }
    }

    /**
     * logback-core trims the file name, so leading whitespace must not hide a
     * leading separator, a drive prefix or a parent reference.
     */
    @Test
    public void whitespaceWrappedTraversalValuesYieldDefaultValue() {
        String[] whitespaceValues = { " /x", "\t/x", "\t../x", " C:\\x", "x/..\t" };
        for (String whitespace : whitespaceValues) {
            logbackMDCAdapter.put(key, whitespace);
            assertEquals(DEFAULT_VAL, discriminatingValue(), whitespace);
        }
    }

    /**
     * Values which stay within the directory, including ones with inner path
     * separators or dots, are returned unchanged.
     */
    @Test
    public void inBoundsValuesAreReturnedUnchanged() {
        String[] inBoundsValues = { value, "tenantA/user1", "tenantA\\user1", "a.b", ".hidden", "trailing.",
                "x/./y", "a..b", "user@example.com", "a,b", "(id)", "tenant:1", "c:x", "u:123", new String(new char[200]).replace('\0', 'a'),
                "a+b@x.com" };
        for (String inBounds : inBoundsValues) {
            logbackMDCAdapter.put(key, inBounds);
            assertEquals(inBounds, discriminatingValue(), inBounds);
        }
    }

    /**
     * An empty value vanishes from a file name built from it, which can turn a
     * relative path such as "${key}/app.log" into the absolute path "/app.log",
     * so it is treated like an unset MDC key.
     */
    @Test
    public void emptyValueYieldsDefaultValue() {
        logbackMDCAdapter.put(key, "");
        assertEquals(DEFAULT_VAL, discriminatingValue());
    }

    /**
     * A blank value is trimmed to an empty one, which vanishes from the file
     * name, so it yields the default value too.
     */
    @Test
    public void blankValueYieldsDefaultValue() {
        String[] blankValues = { " ", "\t", " \t " };
        for (String blank : blankValues) {
            logbackMDCAdapter.put(key, blank);
            assertEquals(DEFAULT_VAL, discriminatingValue(), blank);
        }
    }

    /**
     * The nested configuration parses the value again, where a ":-" outside a
     * variable reference or an unmatched '}' ends it. Values which that cuts down
     * to a parent reference or to nothing yield the default value, the others are
     * returned unchanged.
     */
    @Test
    public void valuesCutByTheNestedParserAreCheckedAsCut() {
        String[] cutToUnsafeValues = { "..:-x", "..}x", " ..}x", "x/..}y", ":-x", "}x" };
        for (String cutToUnsafe : cutToUnsafeValues) {
            logbackMDCAdapter.put(key, cutToUnsafe);
            assertEquals(DEFAULT_VAL, discriminatingValue(), cutToUnsafe);
        }

        String[] cutToSafeValues = { "x:-y", "a}b", "tenant}/..x", "{..:-x}", "{guid", "{}{" };
        for (String cutToSafe : cutToSafeValues) {
            logbackMDCAdapter.put(key, cutToSafe);
            assertEquals(cutToSafe, discriminatingValue(), cutToSafe);
        }
    }

    /**
     * Variable references and file name pattern conversions can produce path
     * separators and parent references once expanded, so values containing them
     * yield the default value.
     */
    @Test
    public void expandableValuesYieldDefaultValue() {
        String[] expandableValues = { "${file.separator}", "x${file.separator}..${file.separator}y",
                "${sys:user.home}", "x%d{/.}.%d{/}y", "%d", "a%b" };
        for (String expandable : expandableValues) {
            logbackMDCAdapter.put(key, expandable);
            assertEquals(DEFAULT_VAL, discriminatingValue(), expandable);
        }
    }

    /**
     * Clean MDC values, including ones with single dots, are returned unchanged
     * and no status warning should be produced.
     */
    @Test
    public void noWarnWhenMdcValueIsClean() {
        StatusChecker statusChecker = new StatusChecker(loggerContext);
        String[] cleanValues = { value, "a.b", "host.example.com", "192.168.0.1", ".hidden", "trailing." };
        for (String clean : cleanValues) {
            logbackMDCAdapter.put(key, clean);
            assertEquals(clean, discriminatingValueAt(1_000L));
        }
        statusChecker.assertIsWarningOrErrorFree();
    }

    /**
     * MDC values containing a parent reference, a variable reference or a file
     * name pattern conversion, at any position, are rejected and the default value
     * is returned instead.
     */
    @Test
    public void forbiddenCharactersYieldDefaultValue() {
        String[] forbiddenValues = { "..", "...", "../" + value, "..${file.separator}" + value,
                "${file.separator}" };
        for (String forbidden : forbiddenValues) {
            logbackMDCAdapter.put(key, forbidden);
            assertEquals(DEFAULT_VAL, discriminatingValueAt(1_000L), forbidden);
        }

        for (String c : new String[] { "${", "%" }) {
            for (String forbidden : new String[] { c + "a", "a" + c + "b", "a" + c }) {
                logbackMDCAdapter.put(key, forbidden);
                assertEquals(DEFAULT_VAL, discriminatingValueAt(1_000L), forbidden);
            }
        }
    }

    /**
     * A lone '$', '{' or '}' is not expanded and is returned unchanged.
     */
    @Test
    public void nonExpandableValuesAreReturnedUnchanged() {
        String[] nonExpandableValues = { "price$", "$x", "{guid}", "a}b", "a$-{b}" };
        for (String nonExpandable : nonExpandableValues) {
            logbackMDCAdapter.put(key, nonExpandable);
            assertEquals(nonExpandable, discriminatingValue(), nonExpandable);
        }
    }

    private String discriminatingValueAt(long timestamp) {
        LoggingEvent le = new LoggingEvent("a", logger, Level.DEBUG, "", null, null);
        le.setTimeStamp(timestamp);
        return discriminator.getDiscriminatingValue(le);
    }

    /**
     * Values which escape the directory or are parser syntax yield the default
     * value: parent references cut out by the nested parser, single-dot first or
     * last segments, blank values, variable references, conversions and regex
     * quantifiers which let the value match the archives of other values.
     */
    @Test
    public void unsafeValuesYieldDefaultValue() {
        String[] unsafeValues = { "..}x", "..}", "..:-x", "\t../x", "..\t", "./x", "x/.", ".", " ", "\t", ".+", ".*",
                ".{0,40}", "(.)+", "${file.separator}", "50%off" };
        for (String unsafe : unsafeValues) {
            logbackMDCAdapter.put(key, unsafe);
            assertEquals(DEFAULT_VAL, discriminatingValue(), unsafe);
        }
    }

    /**
     * Emails including '+', ids in braces, long ids and values with inner
     * separators are returned unchanged.
     */
    @Test
    public void safeValuesAreReturnedUnchanged() {
        String[] safeValues = { "alice+tag@gmail.com", "{guid}", "a+b", "a,b", "x(1)", "tenantA/user1", "price$",
                "host.example.com", new String(new char[100]).replace('\0', 'a') };
        for (String safe : safeValues) {
            logbackMDCAdapter.put(key, safe);
            assertEquals(safe, discriminatingValue(), safe);
        }
    }

    /**
     * A rolling file name pattern copies the value unescaped into a regex, so
     * '|', '[' and ']', a quantifier at the start or after '.' or ')' and a
     * repetition such as "{2}" yield the default value; any other '+', '*', '?',
     * '{' or '}' is matched as a literal and returned unchanged.
     */
    @Test
    public void regexSyntaxYieldsDefaultValue() {
        String[] regexValues = { ".?", "(a)*", "a{2}", "x{1,}", "+x", "*x", "?x", " +x", "|", "a|b", "[a]", "a]",
                "a[1]" };
        for (String regex : regexValues) {
            logbackMDCAdapter.put(key, regex);
            assertEquals(DEFAULT_VAL, discriminatingValue(), regex);
        }

        String[] literalValues = { "a+b@x.com", "x{y}", "a*b", "what?", "{}", "x{,3}", "{a}", "a{b,c}" };
        for (String literal : literalValues) {
            logbackMDCAdapter.put(key, literal);
            assertEquals(literal, discriminatingValue(), literal);
        }
    }

    private String discriminatingValue() {
        event = new LoggingEvent("a", logger, Level.DEBUG, "", null, null);
        return discriminator.getDiscriminatingValue(event);
    }
}
