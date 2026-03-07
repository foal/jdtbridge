package io.github.kaluchi.jdtbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Handler for /project-info endpoint: project overview with adaptive detail.
 */
class ProjectHandler {

    private static final int DEFAULT_MEMBERS_THRESHOLD = 200;

    String handleProjectInfo(Map<String, String> params) throws Exception {
        String projectName = params.get("project");
        if (projectName == null || projectName.isBlank()) {
            return "{\"error\":\"Missing 'project' parameter\"}";
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot()
                .getProject(projectName);
        if (!project.exists()) {
            return "{\"error\":\"Project not found: "
                    + HttpServer.escapeJson(projectName) + "\"}";
        }

        IJavaProject javaProject = JavaCore.create(project);
        if (javaProject == null || !javaProject.exists()) {
            return "{\"error\":\"Not a Java project: "
                    + HttpServer.escapeJson(projectName) + "\"}";
        }

        int membersThreshold = DEFAULT_MEMBERS_THRESHOLD;
        String thresholdParam = params.get("members-threshold");
        if (thresholdParam != null) {
            try {
                membersThreshold = Integer.parseInt(thresholdParam);
            } catch (NumberFormatException e) { /* use default */ }
        }

        // Quick count pass
        int totalTypes = 0;
        List<IPackageFragmentRoot> sourceRoots = new ArrayList<>();
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            sourceRoots.add(root);
            for (IJavaElement child : root.getChildren()) {
                if (child instanceof IPackageFragment pkg) {
                    for (ICompilationUnit u : pkg.getCompilationUnits()) {
                        totalTypes += u.getTypes().length;
                    }
                }
            }
        }
        boolean includeMembers = totalTypes <= membersThreshold;

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Name
        sb.append("\"name\":\"")
                .append(HttpServer.escapeJson(projectName)).append("\"");

        // Location
        String location = project.getLocation() != null
                ? project.getLocation().toOSString() : "?";
        sb.append(",\"location\":\"")
                .append(HttpServer.escapeJson(location)).append("\"");

        // Natures
        sb.append(",\"natures\":[");
        String[] natureIds = project.getDescription().getNatureIds();
        for (int i = 0; i < natureIds.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"")
                    .append(HttpServer.escapeJson(shortNature(natureIds[i])))
                    .append("\"");
        }
        sb.append("]");

        // Dependencies
        String[] deps = javaProject.getRequiredProjectNames();
        sb.append(",\"dependencies\":[");
        for (int i = 0; i < deps.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(HttpServer.escapeJson(deps[i]))
                    .append("\"");
        }
        sb.append("]");

        sb.append(",\"totalTypes\":").append(totalTypes);
        sb.append(",\"membersIncluded\":").append(includeMembers);

        // Source roots
        sb.append(",\"sourceRoots\":[");
        boolean firstRoot = true;
        for (IPackageFragmentRoot root : sourceRoots) {
            if (!firstRoot) sb.append(",");
            firstRoot = false;

            String rootPath = root.getResource()
                    .getProjectRelativePath().toString();
            sb.append("{\"path\":\"")
                    .append(HttpServer.escapeJson(rootPath)).append("\"");

            // Packages
            sb.append(",\"packages\":[");
            boolean firstPkg = true;
            int rootTypeCount = 0;
            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                ICompilationUnit[] units = pkg.getCompilationUnits();
                if (units.length == 0) continue;

                if (!firstPkg) sb.append(",");
                firstPkg = false;

                String pkgName = pkg.getElementName();
                if (pkgName.isEmpty()) pkgName = "(default)";
                sb.append("{\"name\":\"")
                        .append(HttpServer.escapeJson(pkgName)).append("\"");

                // Types
                sb.append(",\"types\":[");
                boolean firstType = true;
                int pkgTypeCount = 0;
                for (ICompilationUnit unit : units) {
                    for (IType type : unit.getTypes()) {
                        if (!firstType) sb.append(",");
                        firstType = false;
                        pkgTypeCount++;

                        sb.append("{\"name\":\"")
                                .append(HttpServer.escapeJson(
                                        type.getElementName()))
                                .append("\"");
                        sb.append(",\"kind\":\"")
                                .append(typeKind(type)).append("\"");

                        sb.append(",\"fields\":")
                                .append(type.getFields().length);
                        if (includeMembers) {
                            appendMethodSignatures(sb, type);
                        } else {
                            sb.append(",\"methods\":")
                                    .append(type.getMethods().length);
                        }

                        sb.append("}");
                    }
                }
                sb.append("]");

                rootTypeCount += pkgTypeCount;
                sb.append("}");
            }
            sb.append("]");
            sb.append(",\"typeCount\":").append(rootTypeCount);
            sb.append("}");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private void appendMethodSignatures(StringBuilder sb, IType type)
            throws JavaModelException {
        List<String> pub = new ArrayList<>();
        List<String> prot = new ArrayList<>();
        List<String> def = new ArrayList<>();
        List<String> priv = new ArrayList<>();

        for (IMethod m : type.getMethods()) {
            String sig = compactSignature(m);
            int flags = m.getFlags();
            if (Flags.isPublic(flags)) pub.add(sig);
            else if (Flags.isProtected(flags)) prot.add(sig);
            else if (Flags.isPrivate(flags)) priv.add(sig);
            else def.add(sig);
        }

        sb.append(",\"methods\":{");
        appendMethodGroup(sb, "public", pub);
        sb.append(",");
        appendMethodGroup(sb, "protected", prot);
        sb.append(",");
        appendMethodGroup(sb, "default", def);
        sb.append(",");
        appendMethodGroup(sb, "private", priv);
        sb.append("}");
    }

    private void appendMethodGroup(StringBuilder sb, String name,
            List<String> methods) {
        sb.append("\"").append(name).append("\":[");
        for (int i = 0; i < methods.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(HttpServer.escapeJson(methods.get(i)))
                    .append("\"");
        }
        sb.append("]");
    }

    private String compactSignature(IMethod m) throws JavaModelException {
        StringBuilder sig = new StringBuilder();
        sig.append(m.getElementName()).append("(");
        String[] paramTypes = m.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.toString(paramTypes[i]));
        }
        sig.append(")");
        return sig.toString();
    }

    private static String typeKind(IType type) throws JavaModelException {
        if (type.isAnnotation()) return "annotation";
        if (type.isEnum()) return "enum";
        if (type.isInterface()) return "interface";
        return "class";
    }

    private static String shortNature(String natureId) {
        if (natureId.contains("javanature")) return "java";
        if (natureId.contains("maven")) return "maven";
        if (natureId.contains("pde") || natureId.contains("Plugin")) {
            return "pde";
        }
        if (natureId.contains("gradle")) return "gradle";
        int dot = natureId.lastIndexOf('.');
        return dot >= 0 ? natureId.substring(dot + 1) : natureId;
    }
}
