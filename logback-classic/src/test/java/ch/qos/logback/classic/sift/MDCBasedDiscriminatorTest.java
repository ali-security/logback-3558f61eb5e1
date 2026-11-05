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
                "x/./y", "a..b", "user@example.com", "a,b", "(id)", "tenant:1", "c:x", "u:123", "", new String(new char[200]).replace('\0', 'a') };
        for (String inBounds : inBoundsValues) {
            logbackMDCAdapter.put(key, inBounds);
            assertEquals(inBounds, discriminatingValue(), inBounds);
        }
    }

    private String discriminatingValue() {
        event = new LoggingEvent("a", logger, Level.DEBUG, "", null, null);
        return discriminator.getDiscriminatingValue(event);
    }
}
