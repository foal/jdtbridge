package app.m8.eclipse.jdtsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;

/**
 * Handler for /errors endpoint: compilation diagnostics with optional refresh/build.
 */
class DiagnosticsHandler {

    String handleErrors(Map<String, String> params) throws Exception {
        String filePath = params.get("file");
        String projectName = params.get("project");
        boolean refresh = !params.containsKey("no-refresh");
        boolean build = params.containsKey("build");
        boolean clean = params.containsKey("clean");
        boolean includeWarnings = params.containsKey("warnings");
        boolean all = params.containsKey("all");

        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();

        // Determine scope
        IResource scope;
        if (filePath != null && !filePath.isBlank()) {
            scope = root.findMember(filePath);
            if (scope == null) {
                return "{\"error\":\"Resource not found: "
                        + HttpServer.escapeJson(filePath) + "\"}";
            }
        } else if (projectName != null && !projectName.isBlank()) {
            IProject project = root.getProject(projectName);
            if (!project.exists()) {
                return "{\"error\":\"Project not found: "
                        + HttpServer.escapeJson(projectName) + "\"}";
            }
            scope = project;
        } else {
            scope = root;
        }

        // Determine project for build operations
        IProject buildProject = null;
        if (scope instanceof IProject p) {
            buildProject = p;
        } else if (!(scope instanceof IWorkspaceRoot)) {
            buildProject = scope.getProject();
        }

        // Refresh from disk
        if (refresh) {
            int depth = (scope instanceof IFile)
                    ? IResource.DEPTH_ZERO : IResource.DEPTH_INFINITE;
            scope.refreshLocal(depth, null);
        }

        // Build
        if (clean) {
            if (buildProject == null) {
                return "{\"error\":\"clean requires a specific project"
                        + " (use 'project' param)\"}";
            }
            buildProject.build(IncrementalProjectBuilder.CLEAN_BUILD, null);
            buildProject.build(IncrementalProjectBuilder.FULL_BUILD, null);
        } else if (build) {
            if (buildProject != null) {
                buildProject.build(
                        IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
            } else {
                ResourcesPlugin.getWorkspace().build(
                        IncrementalProjectBuilder.INCREMENTAL_BUILD, null);
            }
        } else if (refresh) {
            // Auto-build is on — wait for it to finish after refresh
            Job.getJobManager().join(
                    ResourcesPlugin.FAMILY_AUTO_BUILD, null);
        }

        // Read markers
        String markerType = all ? IMarker.PROBLEM
                : "org.eclipse.jdt.core.problem";
        IMarker[] markers = scope.findMarkers(
                markerType, true, IResource.DEPTH_INFINITE);

        List<String> results = new ArrayList<>();
        for (IMarker marker : markers) {
            int severity = marker.getAttribute(IMarker.SEVERITY, -1);
            if (!includeWarnings && severity != IMarker.SEVERITY_ERROR) {
                continue;
            }
            String sevStr = switch (severity) {
                case IMarker.SEVERITY_ERROR -> "ERROR";
                case IMarker.SEVERITY_WARNING -> "WARNING";
                default -> "INFO";
            };
            String file = marker.getResource().getFullPath().toString();
            int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
            String message = marker.getAttribute(IMarker.MESSAGE, "");

            StringBuilder entry = new StringBuilder();
            entry.append("{\"file\":\"").append(HttpServer.escapeJson(file))
                    .append("\",\"line\":").append(line)
                    .append(",\"severity\":\"").append(sevStr)
                    .append("\",\"message\":\"")
                    .append(HttpServer.escapeJson(message)).append("\"");
            if (all) {
                entry.append(",\"source\":\"")
                        .append(HttpServer.escapeJson(
                                shortMarkerType(marker.getType())))
                        .append("\"");
            }
            entry.append("}");
            results.add(entry.toString());
        }

        return "[" + String.join(",", results) + "]";
    }

    private String shortMarkerType(String type) {
        if (type == null) return "unknown";
        if (type.contains("jdt")) return "jdt";
        if (type.contains("eclipsecs") || type.contains("checkstyle"))
            return "checkstyle";
        if (type.contains("m2e") || type.contains("maven")) return "maven";
        // Last segment: org.foo.bar.Problem → Problem
        int dot = type.lastIndexOf('.');
        return dot >= 0 ? type.substring(dot + 1) : type;
    }
}
