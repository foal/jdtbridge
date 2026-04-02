package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Version;

/**
 * Tests version comparison logic used by WelcomeStartupHandler.
 * CLI version (semver string) vs plugin version (OSGi with qualifier).
 */
public class VersionCompareTest {

    private static Version stripQualifier(Version v) {
        return new Version(v.getMajor(), v.getMinor(), v.getMicro());
    }

    private static boolean isOlder(String cliVersion,
            Version pluginVersion) {
        Version cli;
        try {
            cli = Version.parseVersion(cliVersion);
        } catch (IllegalArgumentException e) {
            cli = Version.emptyVersion;
        }
        return cli.compareTo(pluginVersion) < 0;
    }

    @Test
    void sameVersionNotOlder() {
        var plugin = stripQualifier(
                Version.parseVersion("2.2.0.202604020757"));
        assertFalse(isOlder("2.2.0", plugin));
    }

    @Test
    void olderCliDetected() {
        var plugin = stripQualifier(
                Version.parseVersion("2.2.0.qualifier"));
        assertTrue(isOlder("2.1.0", plugin));
    }

    @Test
    void newerCliNotOlder() {
        var plugin = stripQualifier(
                Version.parseVersion("2.1.0.qualifier"));
        assertFalse(isOlder("2.2.0", plugin));
    }

    @Test
    void qualifierIgnored() {
        var plugin = stripQualifier(
                Version.parseVersion("2.2.0.202604020757"));
        var plain = stripQualifier(
                Version.parseVersion("2.2.0"));
        assertFalse(isOlder("2.2.0", plugin));
        assertFalse(isOlder("2.2.0", plain));
    }

    @Test
    void patchVersionCompared() {
        var plugin = stripQualifier(
                Version.parseVersion("2.2.1.qualifier"));
        assertTrue(isOlder("2.2.0", plugin));
    }

    @Test
    void invalidCliVersionTreatedAsZero() {
        var plugin = stripQualifier(
                Version.parseVersion("2.2.0.qualifier"));
        assertTrue(isOlder("garbage", plugin));
    }
}
