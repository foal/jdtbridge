package app.m8.eclipse.jdtsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.Launch;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.launcher.JUnitLaunchConfigurationDelegate;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestElement.FailureTrace;
import org.eclipse.jdt.junit.model.ITestElementContainer;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

/**
 * Handler for /test endpoint: run JUnit tests via Eclipse's built-in runner.
 */
class TestHandler {

    private static final String JUNIT_LAUNCH_TYPE =
            "org.eclipse.jdt.junit.launchconfig";
    private static final String ATTR_TEST_KIND =
            "org.eclipse.jdt.junit.TEST_KIND";
    private static final String ATTR_TEST_NAME =
            "org.eclipse.jdt.junit.TESTNAME";
    private static final String ATTR_TEST_CONTAINER =
            "org.eclipse.jdt.junit.CONTAINER";
    private static final String JUNIT5_KIND =
            "org.eclipse.jdt.junit.loader.junit5";
    private static final String JUNIT4_KIND =
            "org.eclipse.jdt.junit.loader.junit4";

    String handleTest(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");
        String projectName = params.get("project");
        String packageName = params.get("package");
        int timeoutSec = parseTimeout(params.get("timeout"), 120);

        boolean noRefresh = params.containsKey("no-refresh");

        // Refresh project from disk before running (default: on)
        if (!noRefresh) {
            IJavaProject refreshProject = null;
            if (fqn != null && !fqn.isBlank()) {
                IType t = findType(fqn);
                if (t != null) refreshProject = t.getJavaProject();
            } else if (projectName != null && !projectName.isBlank()) {
                var model = JavaCore.create(
                        ResourcesPlugin.getWorkspace().getRoot());
                refreshProject = model.getJavaProject(projectName);
            }
            if (refreshProject != null && refreshProject.exists()) {
                refreshProject.getProject().refreshLocal(
                        org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                        new NullProgressMonitor());
                // Wait for auto-build after refresh
                org.eclipse.core.runtime.jobs.Job.getJobManager().join(
                        ResourcesPlugin.FAMILY_AUTO_BUILD,
                        new NullProgressMonitor());
            }
        }

        ILaunchManager manager =
                DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType launchType =
                manager.getLaunchConfigurationType(JUNIT_LAUNCH_TYPE);
        if (launchType == null) {
            return "{\"error\":\"JUnit launch type not available\"}";
        }

        String configName = "jdt-bridge-test-"
                + System.currentTimeMillis();
        ILaunchConfigurationWorkingCopy wc =
                launchType.newInstance(null, configName);

        // Determine what to run
        if (fqn != null && !fqn.isBlank()) {
            // Run specific class (optionally a method)
            IType type = findType(fqn);
            if (type == null) {
                return "{\"error\":\"Type not found: "
                        + HttpServer.escapeJson(fqn) + "\"}";
            }

            IJavaProject jp = type.getJavaProject();
            wc.setAttribute(
                    IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                    jp.getElementName());
            wc.setAttribute(
                    IJavaLaunchConfigurationConstants.ATTR_MAIN_TYPE_NAME,
                    fqn);
            wc.setAttribute(ATTR_TEST_KIND, detectTestKind(type));

            if (methodName != null && !methodName.isBlank()) {
                wc.setAttribute(ATTR_TEST_NAME, methodName);
            }
        } else if (projectName != null && !projectName.isBlank()) {
            // Run by project/package
            var model = JavaCore.create(
                    ResourcesPlugin.getWorkspace().getRoot());
            IJavaProject jp = model.getJavaProject(projectName);
            if (jp == null || !jp.exists()) {
                return "{\"error\":\"Project not found: "
                        + HttpServer.escapeJson(projectName) + "\"}";
            }

            wc.setAttribute(
                    IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME,
                    projectName);
            wc.setAttribute(ATTR_TEST_KIND, JUNIT5_KIND);

            if (packageName != null && !packageName.isBlank()) {
                // Find package fragment
                IPackageFragment pkg = findPackage(jp, packageName);
                if (pkg == null) {
                    return "{\"error\":\"Package not found: "
                            + HttpServer.escapeJson(packageName) + "\"}";
                }
                wc.setAttribute(ATTR_TEST_CONTAINER,
                        pkg.getHandleIdentifier());
            } else {
                wc.setAttribute(ATTR_TEST_CONTAINER,
                        jp.getHandleIdentifier());
            }
        } else {
            return "{\"error\":\"Missing 'class' or 'project' parameter\"}";
        }

        // Register listener before launch
        CountDownLatch latch = new CountDownLatch(1);
        ResultCollector collector = new ResultCollector(configName, latch);
        JUnitCore.addTestRunListener(collector);

        try {
            // Call delegate.launch() directly, bypassing preLaunchCheck
            // which may show save/build dialogs and block
            JUnitLaunchConfigurationDelegate delegate =
                    new JUnitLaunchConfigurationDelegate();
            ILaunch launch = new Launch(wc, ILaunchManager.RUN_MODE, null);
            DebugPlugin.getDefault().getLaunchManager()
                    .addLaunch(launch);
            delegate.launch(wc, ILaunchManager.RUN_MODE,
                    launch, new NullProgressMonitor());

            if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
                return "{\"error\":\"Test run timed out after "
                        + timeoutSec + "s\"}";
            }

            return collector.toJson();
        } finally {
            JUnitCore.removeTestRunListener(collector);
        }
    }

    // ---- result collector ----

    private static class ResultCollector extends TestRunListener {
        private final String configName;
        private final CountDownLatch latch;
        private final List<TestResult> results = new ArrayList<>();
        private double totalTime;
        private int total;
        private int passed;
        private int failed;
        private int errors;
        private int ignored;

        ResultCollector(String configName, CountDownLatch latch) {
            this.configName = configName;
            this.latch = latch;
        }

        @Override
        public void sessionFinished(ITestRunSession session) {
            if (!session.getTestRunName().equals(configName)) return;
            totalTime = session.getElapsedTimeInSeconds();
            collectResults(session);
            latch.countDown();
        }

        private void collectResults(ITestElementContainer container) {
            try {
                for (ITestElement child : container.getChildren()) {
                    if (child instanceof ITestCaseElement tc) {
                        var result = tc.getTestResult(false);
                        total++;
                        TestResult tr = new TestResult();
                        tr.className = tc.getTestClassName();
                        tr.method = tc.getTestMethodName();

                        if (result == ITestElement.Result.OK) {
                            tr.status = "PASS";
                            passed++;
                        } else if (result == ITestElement.Result.FAILURE) {
                            tr.status = "FAIL";
                            failed++;
                            FailureTrace ft = tc.getFailureTrace();
                            if (ft != null) tr.trace = ft.getTrace();
                        } else if (result == ITestElement.Result.ERROR) {
                            tr.status = "ERROR";
                            errors++;
                            FailureTrace ft = tc.getFailureTrace();
                            if (ft != null) tr.trace = ft.getTrace();
                        } else if (result == ITestElement.Result.IGNORED) {
                            tr.status = "IGNORED";
                            ignored++;
                        } else {
                            tr.status = "UNKNOWN";
                        }
                        results.add(tr);
                    }
                    if (child instanceof ITestElementContainer nested) {
                        collectResults(nested);
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"total\":").append(total)
                    .append(",\"passed\":").append(passed)
                    .append(",\"failed\":").append(failed)
                    .append(",\"errors\":").append(errors)
                    .append(",\"ignored\":").append(ignored)
                    .append(",\"time\":").append(totalTime);

            // Only include failed/error details
            List<TestResult> failures = results.stream()
                    .filter(r -> !"PASS".equals(r.status)
                            && !"IGNORED".equals(r.status))
                    .toList();

            sb.append(",\"failures\":[");
            for (int i = 0; i < failures.size(); i++) {
                if (i > 0) sb.append(",");
                TestResult r = failures.get(i);
                sb.append("{\"class\":\"")
                        .append(HttpServer.escapeJson(r.className))
                        .append("\",\"method\":\"")
                        .append(HttpServer.escapeJson(r.method))
                        .append("\",\"status\":\"").append(r.status)
                        .append("\"");
                if (r.trace != null) {
                    sb.append(",\"trace\":\"")
                            .append(HttpServer.escapeJson(r.trace))
                            .append("\"");
                }
                sb.append("}");
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    private static class TestResult {
        String className;
        String method;
        String status;
        String trace;
    }

    // ---- helpers ----

    private String detectTestKind(IType type) {
        // Check if JUnit 5 annotations are on classpath
        try {
            IType junit5 = type.getJavaProject()
                    .findType("org.junit.jupiter.api.Test");
            if (junit5 != null) return JUNIT5_KIND;
        } catch (JavaModelException e) { /* ignore */ }
        return JUNIT4_KIND;
    }

    private IType findType(String fqn) throws JavaModelException {
        var model = JavaCore.create(
                ResourcesPlugin.getWorkspace().getRoot());
        for (IJavaProject project : model.getJavaProjects()) {
            IType type = project.findType(fqn);
            if (type != null && type.exists()) return type;
        }
        return null;
    }

    private IPackageFragment findPackage(IJavaProject project,
            String packageName) throws JavaModelException {
        for (var root : project.getPackageFragmentRoots()) {
            if (root.getKind()
                    == org.eclipse.jdt.core.IPackageFragmentRoot.K_SOURCE) {
                IPackageFragment pkg =
                        root.getPackageFragment(packageName);
                if (pkg != null && pkg.exists()) return pkg;
            }
        }
        return null;
    }

    private int parseTimeout(String s, int defaultVal) {
        if (s == null) return defaultVal;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultVal; }
    }
}
