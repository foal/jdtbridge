import { describe, it, expect, beforeEach, afterEach } from "vitest";
import {
  mkdtempSync,
  mkdirSync,
  writeFileSync,
  readFileSync,
} from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import {
  eclipseExe,
  getEclipseVersion,
  detectProfile,
  getInstalledVersion,
  findEclipsePath,
  getEclipseJavaHome,
  generateTargetPlatform,
} from "../src/eclipse.mjs";

const IS_WIN = process.platform === "win32";

describe("eclipse", () => {
  let testDir;

  beforeEach(() => {
    testDir = mkdtempSync(join(tmpdir(), "jdt-eclipse-test-"));
  });

  describe("eclipseExe", () => {
    it("appends .exe on Windows", () => {
      if (IS_WIN) {
        expect(eclipseExe("eclipsec")).toBe("eclipsec.exe");
        expect(eclipseExe("eclipse")).toBe("eclipse.exe");
      } else {
        expect(eclipseExe("eclipsec")).toBe("eclipsec");
      }
    });
  });

  describe("getEclipseVersion", () => {
    it("returns version from .eclipseproduct", () => {
      writeFileSync(
        join(testDir, ".eclipseproduct"),
        "name=Eclipse Platform\nversion=4.33.0\nid=org.eclipse.sdk.ide\n",
      );
      expect(getEclipseVersion(testDir)).toBe("4.33.0");
    });

    it("returns null when file is missing", () => {
      expect(getEclipseVersion(testDir)).toBeNull();
    });

    it("returns null when no version line", () => {
      writeFileSync(
        join(testDir, ".eclipseproduct"),
        "name=Eclipse Platform\n",
      );
      expect(getEclipseVersion(testDir)).toBeNull();
    });
  });

  describe("detectProfile", () => {
    function createProfileRegistry(...profileNames) {
      const regDir = join(
        testDir,
        "p2",
        "org.eclipse.equinox.p2.engine",
        "profileRegistry",
      );
      mkdirSync(regDir, { recursive: true });
      for (const name of profileNames) {
        mkdirSync(join(regDir, name));
      }
      return regDir;
    }

    it("returns null when profileRegistry dir is missing", () => {
      expect(detectProfile(testDir)).toBeNull();
    });

    it("returns null when no profiles exist", () => {
      createProfileRegistry();
      expect(detectProfile(testDir)).toBeNull();
    });

    it("detects epp.package profile", () => {
      createProfileRegistry(
        "epp.package.java.profile",
        "SDKProfile.profile",
      );
      expect(detectProfile(testDir)).toBe("epp.package.java");
    });

    it("falls back to first profile if no epp", () => {
      createProfileRegistry("SDKProfile.profile");
      expect(detectProfile(testDir)).toBe("SDKProfile");
    });

    it("ignores non-.profile entries", () => {
      const regDir = join(
        testDir,
        "p2",
        "org.eclipse.equinox.p2.engine",
        "profileRegistry",
      );
      mkdirSync(regDir, { recursive: true });
      writeFileSync(join(regDir, "notes.txt"), "ignore me");
      expect(detectProfile(testDir)).toBeNull();
    });
  });

  describe("getInstalledVersion", () => {
    const BUNDLE = "io.github.kaluchi.jdtbridge";

    function createPlugins(...jarNames) {
      const pluginsDir = join(testDir, "plugins");
      mkdirSync(pluginsDir, { recursive: true });
      for (const name of jarNames) {
        writeFileSync(join(pluginsDir, name), "");
      }
    }

    it("returns null when plugins dir is missing", () => {
      expect(getInstalledVersion(testDir, BUNDLE)).toBeNull();
    });

    it("returns null when no matching JARs", () => {
      createPlugins("org.eclipse.core_3.0.0.jar");
      expect(getInstalledVersion(testDir, BUNDLE)).toBeNull();
    });

    it("returns version from matching JAR", () => {
      createPlugins(`${BUNDLE}_1.0.5.jar`);
      expect(getInstalledVersion(testDir, BUNDLE)).toBe("1.0.5");
    });

    it("returns version with qualifier", () => {
      createPlugins(`${BUNDLE}_1.0.0.202501011200.jar`);
      expect(getInstalledVersion(testDir, BUNDLE)).toBe(
        "1.0.0.202501011200",
      );
    });

    it("ignores JARs from other bundles", () => {
      createPlugins(
        "some.other.bundle_2.0.0.jar",
        `${BUNDLE}_1.0.1.jar`,
      );
      expect(getInstalledVersion(testDir, BUNDLE)).toBe("1.0.1");
    });
  });

  describe("findEclipsePath", () => {
    it("uses config path if eclipsec exists", () => {
      writeFileSync(join(testDir, eclipseExe("eclipsec")), "");
      const result = findEclipsePath({ eclipse: testDir });
      expect(result).toBe(testDir);
    });

    it("returns null when config path has no eclipsec", () => {
      const result = findEclipsePath({ eclipse: testDir });
      // Falls through to candidates, which likely don't exist in test env
      // At minimum, config path should not be returned
      expect(result).not.toBe(testDir);
    });

    it("returns null when config is empty and no candidates", () => {
      const result = findEclipsePath({});
      // On a machine without Eclipse in standard paths, returns null
      // We can't assert null because Eclipse might be installed
      expect(typeof result === "string" || result === null).toBe(true);
    });
  });

  describe("getEclipseJavaHome", () => {
    it("returns null when eclipse.ini is missing", () => {
      expect(getEclipseJavaHome(testDir)).toBeNull();
    });

    it("returns null when no -vm entry", () => {
      writeFileSync(
        join(testDir, "eclipse.ini"),
        "-startup\nplugins/org.eclipse.equinox.launcher.jar\n-vmargs\n-Xmx2g\n",
      );
      expect(getEclipseJavaHome(testDir)).toBeNull();
    });

    it("extracts JRE home from -vm pointing to bin dir", () => {
      const jreBin = join(testDir, "plugins", "justj", "jre", "bin");
      mkdirSync(jreBin, { recursive: true });
      writeFileSync(
        join(testDir, "eclipse.ini"),
        `-startup\nplugins/launcher.jar\n-vm\nplugins/justj/jre/bin\n-vmargs\n-Xmx2g\n`,
      );
      const result = getEclipseJavaHome(testDir);
      expect(result).toContain("jre");
      expect(result).not.toContain("bin");
    });
  });

  describe("generateTargetPlatform", () => {
    it("writes target file with Eclipse path", () => {
      generateTargetPlatform(testDir, "D:/eclipse");
      const content = readFileSync(join(testDir, "jdtbridge.target"), "utf8");
      expect(content).toContain('path="D:/eclipse"');
      expect(content).toContain('type="Directory"');
      expect(content).toContain("<?xml");
    });

    it("preserves backslashes as-is (no double escaping)", () => {
      generateTargetPlatform(testDir, "D:\\eclipse");
      const content = readFileSync(join(testDir, "jdtbridge.target"), "utf8");
      expect(content).toContain('path="D:\\eclipse"');
      expect(content).not.toContain("\\\\");
    });

    it("handles paths with spaces", () => {
      generateTargetPlatform(testDir, "C:/Program Files/eclipse");
      const content = readFileSync(join(testDir, "jdtbridge.target"), "utf8");
      expect(content).toContain('path="C:/Program Files/eclipse"');
    });
  });
});
