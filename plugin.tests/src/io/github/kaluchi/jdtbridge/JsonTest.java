package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for Json builder and parser.
 */
public class JsonTest {

    // ---- Builder ----

    @Nested
    class Builder {

        @Test
        void emptyObject() {
            assertEquals("{}", Json.object().toString());
        }

        @Test
        void emptyArray() {
            assertEquals("[]", Json.array().toString());
        }

        @Test
        void objectWithStringValue() {
            String json = Json.object().put("name", "test").toString();
            assertEquals("{\"name\":\"test\"}", json);
        }

        @Test
        void objectWithIntValue() {
            String json = Json.object().put("port", 8080).toString();
            assertEquals("{\"port\":8080}", json);
        }

        @Test
        void objectWithLongValue() {
            String json = Json.object().put("pid", 12345L).toString();
            assertEquals("{\"pid\":12345}", json);
        }

        @Test
        void objectWithBooleanValue() {
            String json = Json.object().put("ok", true).toString();
            assertEquals("{\"ok\":true}", json);
        }

        @Test
        void objectWithNullString() {
            String json = Json.object().put("key", (String) null)
                    .toString();
            assertEquals("{\"key\":null}", json);
        }

        @Test
        void objectMultipleKeys() {
            String json = Json.object()
                    .put("a", "1").put("b", 2).toString();
            assertEquals("{\"a\":\"1\",\"b\":2}", json);
        }

        @Test
        void nestedObject() {
            String json = Json.object()
                    .put("inner", Json.object().put("x", 1))
                    .toString();
            assertEquals("{\"inner\":{\"x\":1}}", json);
        }

        @Test
        void arrayOfStrings() {
            String json = Json.array().add("a").add("b").toString();
            assertEquals("[\"a\",\"b\"]", json);
        }

        @Test
        void putIfTrue() {
            String json = Json.object()
                    .putIf(true, "key", "val").toString();
            assertEquals("{\"key\":\"val\"}", json);
        }

        @Test
        void putIfFalse() {
            String json = Json.object()
                    .putIf(false, "key", "val").toString();
            assertEquals("{}", json);
        }

        @Test
        void errorHelper() {
            String json = Json.error("boom");
            assertEquals("{\"error\":\"boom\"}", json);
        }
    }

    // ---- Escape ----

    @Nested
    class Escape {

        @Test
        void plainString() {
            assertEquals("hello", Json.escape("hello"));
        }

        @Test
        void quotes() {
            assertEquals("say \\\"hi\\\"", Json.escape("say \"hi\""));
        }

        @Test
        void backslash() {
            assertEquals("C:\\\\path", Json.escape("C:\\path"));
        }

        @Test
        void newlineAndTab() {
            assertEquals("a\\nb\\tc", Json.escape("a\nb\tc"));
        }

        @Test
        void controlCharacter() {
            assertEquals("\\u0001", Json.escape("\u0001"));
        }

        @Test
        void nullReturnsLiteral() {
            assertEquals("null", Json.escape(null));
        }
    }

    // ---- Parser ----

    @Nested
    class Parser {

        @Test
        void parseStringValue() {
            var map = Json.parse("{\"name\":\"test\"}");
            assertEquals("test", Json.getString(map, "name"));
        }

        @Test
        void parseIntValue() {
            var map = Json.parse("{\"port\":8080}");
            assertEquals(8080, Json.getInt(map, "port", 0));
        }

        @Test
        void parseLongValue() {
            var map = Json.parse("{\"pid\":9999999999}");
            assertEquals(9999999999L, map.get("pid"));
        }

        @Test
        void parseBooleanTrue() {
            var map = Json.parse("{\"ok\":true}");
            assertTrue(Json.getBool(map, "ok", false));
        }

        @Test
        void parseBooleanFalse() {
            var map = Json.parse("{\"ok\":false}");
            assertFalse(Json.getBool(map, "ok", true));
        }

        @Test
        void parseNullValue() {
            var map = Json.parse("{\"key\":null}");
            assertTrue(map.containsKey("key"));
            assertNull(map.get("key"));
        }

        @Test
        void parseMultipleKeys() {
            var map = Json.parse(
                    "{\"port\":8080,\"token\":\"abc\",\"ok\":true}");
            assertEquals(8080, Json.getInt(map, "port", 0));
            assertEquals("abc", Json.getString(map, "token"));
            assertTrue(Json.getBool(map, "ok", false));
        }

        @Test
        void parseWithWhitespace() {
            var map = Json.parse(
                    "{ \"port\" : 8080 , \"name\" : \"test\" }");
            assertEquals(8080, Json.getInt(map, "port", 0));
            assertEquals("test", Json.getString(map, "name"));
        }

        @Test
        void parseWithNewlines() {
            String json = """
                    {
                      "port": 8080,
                      "token": "abc"
                    }
                    """;
            var map = Json.parse(json);
            assertEquals(8080, Json.getInt(map, "port", 0));
            assertEquals("abc", Json.getString(map, "token"));
        }

        @Test
        void parseEscapedString() {
            var map = Json.parse(
                    "{\"path\":\"C:\\\\Users\\\\test\"}");
            assertEquals("C:\\Users\\test",
                    Json.getString(map, "path"));
        }

        @Test
        void parseEscapedQuotes() {
            var map = Json.parse(
                    "{\"msg\":\"say \\\"hi\\\"\"}");
            assertEquals("say \"hi\"", Json.getString(map, "msg"));
        }

        @Test
        void parseUnicodeEscape() {
            var map = Json.parse("{\"ch\":\"\\u0041\"}");
            assertEquals("A", Json.getString(map, "ch"));
        }

        @Test
        void parseMissingKeyReturnsDefault() {
            var map = Json.parse("{\"a\":1}");
            assertNull(Json.getString(map, "b"));
            assertEquals(0, Json.getInt(map, "b", 0));
            assertFalse(Json.getBool(map, "b", false));
        }

        @Test
        void parseEmptyObject() {
            var map = Json.parse("{}");
            assertNotNull(map);
            assertTrue(map.isEmpty());
        }

        @Test
        void parseNullInput() {
            var map = Json.parse(null);
            assertNotNull(map);
            assertTrue(map.isEmpty());
        }

        @Test
        void parseGarbageInput() {
            var map = Json.parse("not json");
            assertNotNull(map);
            assertTrue(map.isEmpty());
        }

        @Test
        void parseNestedObjectAsRawString() {
            var map = Json.parse(
                    "{\"deps\":{\"a\":{\"version\":\"1.0\"}}}");
            Object deps = map.get("deps");
            assertNotNull(deps);
            assertTrue(deps instanceof String);
            assertTrue(((String) deps).contains("version"));
        }

        @Test
        void parseNegativeNumber() {
            var map = Json.parse("{\"offset\":-5}");
            assertEquals(-5, Json.getInt(map, "offset", 0));
        }

        @Test
        void parseDoubleValue() {
            var map = Json.parse("{\"rate\":3.14}");
            Object val = map.get("rate");
            assertTrue(val instanceof Double);
            assertEquals(3.14, (Double) val, 0.001);
        }

        // ---- Roundtrip: build then parse ----

        @Test
        void roundtripBridgeFile() {
            String json = Json.object()
                    .put("port", 54321)
                    .put("token", "abc123")
                    .put("pid", 9876L)
                    .put("workspace", "D:\\eclipse-workspace")
                    .put("version", "1.1.0.qualifier")
                    .put("location", "file:plugins/bundle.jar")
                    .toString();
            var map = Json.parse(json);
            assertEquals(54321, Json.getInt(map, "port", 0));
            assertEquals("abc123", Json.getString(map, "token"));
            assertEquals("D:\\eclipse-workspace",
                    Json.getString(map, "workspace"));
            assertEquals("1.1.0.qualifier",
                    Json.getString(map, "version"));
            assertEquals("file:plugins/bundle.jar",
                    Json.getString(map, "location"));
        }

        @Test
        void roundtripNpmListOutput() {
            // Simulate nested npm list --json output
            String json = """
                    {
                      "name": "npm",
                      "dependencies": {
                        "@kaluchi/jdtbridge": {
                          "version": "1.1.1",
                          "resolved": "file:cli"
                        }
                      }
                    }
                    """;
            var outer = Json.parse(json);
            String depsRaw = (String) outer.get("dependencies");
            assertNotNull(depsRaw);
            var deps = Json.parse(depsRaw);
            String pkgRaw = (String) deps.get("@kaluchi/jdtbridge");
            assertNotNull(pkgRaw);
            var pkg = Json.parse(pkgRaw);
            assertEquals("1.1.1", Json.getString(pkg, "version"));
        }
    }
}
