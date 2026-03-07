package io.github.kaluchi.jdtbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for utility methods in HttpServer.
 * These run without a live Eclipse workspace.
 */
public class HttpServerTest {

    @Test
    public void escapeJsonPlainString() {
        assertEquals("hello", HttpServer.escapeJson("hello"));
    }

    @Test
    public void escapeJsonSpecialChars() {
        assertEquals("a\\\"b", HttpServer.escapeJson("a\"b"));
        assertEquals("a\\\\b", HttpServer.escapeJson("a\\b"));
        assertEquals("a\\nb", HttpServer.escapeJson("a\nb"));
        assertEquals("a\\rb", HttpServer.escapeJson("a\rb"));
        assertEquals("a\\tb", HttpServer.escapeJson("a\tb"));
    }

    @Test
    public void escapeJsonControlChars() {
        // Control char below 0x20 (e.g. 0x01) should be \\u0001
        String input = "a" + (char) 0x01 + "b";
        assertEquals("a\\u0001b", HttpServer.escapeJson(input));
    }

    @Test
    public void escapeJsonNull() {
        assertEquals("null", HttpServer.escapeJson(null));
    }

    @Test
    public void escapeJsonEmpty() {
        assertEquals("", HttpServer.escapeJson(""));
    }

    @Test
    public void escapeJsonUnicode() {
        // Non-ASCII chars should pass through unchanged
        assertEquals("Привет", HttpServer.escapeJson("Привет"));
        assertEquals("日本語", HttpServer.escapeJson("日本語"));
    }
}
