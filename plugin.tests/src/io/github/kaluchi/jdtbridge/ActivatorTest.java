package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Tests for Activator utilities: token generation and bridge file format.
 * Verifies the contract between plugin (writer) and jdt.mjs (reader).
 */
public class ActivatorTest {

    private static final Pattern HEX_32 =
            Pattern.compile("^[0-9a-f]{32}$");

    // ---- generateToken ----

    @Test
    public void tokenIs32HexChars() {
        String token = invokeGenerateToken();
        assertEquals(32, token.length(), "Token should be 32 chars");
        assertTrue(HEX_32.matcher(token).matches(),
                "Token should be lowercase hex: " + token);
    }

    @Test
    public void tokenIsUnique() {
        String token1 = invokeGenerateToken();
        String token2 = invokeGenerateToken();
        assertNotEquals(token1, token2, "Tokens should differ");
    }

    @Test
    public void tokenContainsNoUpperCase() {
        String token = invokeGenerateToken();
        assertEquals(token, token.toLowerCase(),
                "Should be lowercase");
    }

    // ---- Bridge file format ----

    @Test
    public void bridgeFileJsonFormat() {
        String json = Json.object()
                .put("port", 12345)
                .put("token", "abcdef0123456789abcdef0123456789")
                .put("pid", 42L)
                .put("workspace", "D:\\eclipse-workspace")
                .put("version", "1.0.0.202603181541")
                .put("location", "reference:file:plugins/io.github.kaluchi.jdtbridge_1.0.0.jar")
                .toString();

        var map = Json.parse(json);
        assertEquals(12345, Json.getInt(map, "port", 0));
        assertEquals("abcdef0123456789abcdef0123456789",
                Json.getString(map, "token"));
        assertEquals("D:\\eclipse-workspace",
                Json.getString(map, "workspace"));
        assertEquals("1.0.0.202603181541",
                Json.getString(map, "version"));
        assertTrue(Json.getString(map, "location")
                .startsWith("reference:file:plugins/"));
    }

    @Test
    public void bridgeFileWriteAndRead() throws IOException {
        Path tempFile = Files.createTempFile("jdtbridge-test-", ".json");
        try {
            String token = invokeGenerateToken();
            String content = Json.object()
                    .put("port", 54321)
                    .put("token", token)
                    .put("pid", ProcessHandle.current().pid())
                    .put("workspace", "D:/test-workspace")
                    .put("version", "1.0.0.qualifier")
                    .put("location", "reference:file:dropins/jdtbridge.jar")
                    .toString() + "\n";
            Files.writeString(tempFile, content);

            var map = Json.parse(Files.readString(tempFile));
            assertEquals(54321, Json.getInt(map, "port", 0));
            assertEquals(token, Json.getString(map, "token"));
            assertEquals("1.0.0.qualifier",
                    Json.getString(map, "version"));
            assertTrue(Json.getString(map, "location")
                    .startsWith("reference:file:dropins/"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void bridgeFileHasAllRequiredKeys() {
        String json = Json.object()
                .put("port", 8080)
                .put("token", "abc")
                .put("pid", 1L)
                .put("workspace", "/w")
                .put("version", "1.0.0")
                .put("location", "file:plugins/bundle.jar")
                .toString();

        var map = Json.parse(json);
        assertNotEquals(0, Json.getInt(map, "port", 0), "Must have port");
        assertNotNull(Json.getString(map, "token"), "Must have token");
        assertTrue(map.containsKey("pid"), "Must have pid");
        assertNotNull(Json.getString(map, "workspace"), "Must have workspace");
        assertNotNull(Json.getString(map, "version"), "Must have version");
        assertNotNull(Json.getString(map, "location"), "Must have location");
    }

    // ---- Helpers ----

    private String invokeGenerateToken() {
        try {
            var method = Activator.class
                    .getDeclaredMethod("generateToken");
            method.setAccessible(true);
            return (String) method.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
