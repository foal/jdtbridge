package app.m8.eclipse.jdtsearch;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;

/**
 * Handlers for JDT search and code inspection operations.
 */
class SearchHandler {

    String handleProjects() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("[");

        IJavaProject[] projects = JavaCore
                .create(ResourcesPlugin.getWorkspace().getRoot())
                .getJavaProjects();
        for (int i = 0; i < projects.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(HttpServer.escapeJson(projects[i].getElementName())).append("\"");
        }

        sb.append("]");
        return sb.toString();
    }

    String handleFind(Map<String, String> params) throws CoreException {
        String name = params.get("name");
        if (name == null || name.isBlank()) {
            return "{\"error\":\"Missing 'name' parameter\"}";
        }

        boolean sourceOnly = params.containsKey("source");
        int matchRule = (name.contains("*") || name.contains("?"))
                ? SearchPattern.R_PATTERN_MATCH | SearchPattern.R_CASE_SENSITIVE
                : SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE;

        List<String> results = new ArrayList<>();
        SearchEngine engine = new SearchEngine();
        SearchPattern pattern = SearchPattern.createPattern(
                name,
                IJavaSearchConstants.TYPE,
                IJavaSearchConstants.DECLARATIONS,
                matchRule);

        engine.search(pattern,
                new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()},
                SearchEngine.createWorkspaceScope(),
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getElement() instanceof IType type) {
                            if (sourceOnly && type.isBinary()) return;
                            String fqn = type.getFullyQualifiedName();
                            String file = match.getResource() != null
                                    ? match.getResource().getFullPath().toString()
                                    : "?";
                            results.add("{\"fqn\":\"" + HttpServer.escapeJson(fqn)
                                    + "\",\"file\":\"" + HttpServer.escapeJson(file) + "\"}");
                        }
                    }
                },
                null);

        return "[" + String.join(",", results) + "]";
    }

    String handleReferences(Map<String, String> params) throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return "{\"error\":\"Missing 'class' parameter\"}";
        }

        IType type = findType(fqn);
        if (type == null) {
            return "{\"error\":\"Type not found: " + HttpServer.escapeJson(fqn) + "\"}";
        }

        String methodName = params.get("method");
        String fieldName = params.get("field");
        int arity = parseArity(params.get("arity"));
        IJavaElement target;
        if (fieldName != null && !fieldName.isBlank()) {
            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return "{\"error\":\"Field not found: " + HttpServer.escapeJson(fieldName)
                        + " in " + HttpServer.escapeJson(fqn) + "\"}";
            }
            target = field;
        } else if (methodName != null && !methodName.isBlank()) {
            IMethod method = findMethod(type, methodName, arity);
            if (method == null) {
                return "{\"error\":\"Method not found: " + HttpServer.escapeJson(methodName)
                        + " in " + HttpServer.escapeJson(fqn) + "\"}";
            }
            target = method;
        } else {
            target = type;
        }

        List<String> results = new ArrayList<>();
        SearchEngine engine = new SearchEngine();
        SearchPattern pattern = SearchPattern.createPattern(target, IJavaSearchConstants.REFERENCES);

        engine.search(pattern,
                new SearchParticipant[] {SearchEngine.getDefaultSearchParticipant()},
                SearchEngine.createWorkspaceScope(),
                new SearchRequestor() {
                    @Override
                    public void acceptSearchMatch(SearchMatch match) {
                        if (match.getAccuracy() != SearchMatch.A_ACCURATE) return;
                        if (match.isInsideDocComment()) return;

                        String file = getMatchFile(match);
                        int line = getLine(match);
                        String enclosing = getEnclosingName(match);
                        String content = getLineContent(match);

                        String project = getMatchProject(match);

                        StringBuilder entry = new StringBuilder();
                        entry.append("{\"file\":\"").append(HttpServer.escapeJson(file))
                                .append("\",\"line\":").append(line);
                        if (project != null) {
                            entry.append(",\"project\":\"").append(HttpServer.escapeJson(project)).append("\"");
                        }
                        if (enclosing != null) {
                            entry.append(",\"in\":\"").append(HttpServer.escapeJson(enclosing)).append("\"");
                        }
                        if (content != null) {
                            entry.append(",\"content\":\"").append(HttpServer.escapeJson(content)).append("\"");
                        }
                        entry.append("}");
                        results.add(entry.toString());
                    }
                },
                null);

        return "[" + String.join(",", results) + "]";
    }

    String handleSubtypes(Map<String, String> params) throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return "{\"error\":\"Missing 'class' parameter\"}";
        }

        IType type = findType(fqn);
        if (type == null) {
            return "{\"error\":\"Type not found: " + HttpServer.escapeJson(fqn) + "\"}";
        }

        ITypeHierarchy hierarchy = type.newTypeHierarchy(null);
        IType[] subtypes = hierarchy.getAllSubtypes(type);

        List<String> results = new ArrayList<>();
        for (IType sub : subtypes) {
            String subFqn = sub.getFullyQualifiedName();
            String file = sub.getResource() != null
                    ? sub.getResource().getFullPath().toString()
                    : "?";
            results.add("{\"fqn\":\"" + HttpServer.escapeJson(subFqn)
                    + "\",\"file\":\"" + HttpServer.escapeJson(file) + "\"}");
        }

        return "[" + String.join(",", results) + "]";
    }

    // ---- /hierarchy?class=FQN ----

    String handleHierarchy(Map<String, String> params) throws CoreException {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return "{\"error\":\"Missing 'class' parameter\"}";
        }

        IType type = findType(fqn);
        if (type == null) {
            return "{\"error\":\"Type not found: " + HttpServer.escapeJson(fqn) + "\"}";
        }

        ITypeHierarchy hierarchy = type.newTypeHierarchy(null);

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Superclass chain
        sb.append("\"supers\":[");
        IType current = type;
        boolean first = true;
        while (true) {
            IType superType = hierarchy.getSuperclass(current);
            if (superType == null) break;
            if (!first) sb.append(",");
            first = false;
            appendTypeEntry(sb, superType);
            current = superType;
        }
        sb.append("]");

        // All super interfaces (full hierarchy)
        sb.append(",\"interfaces\":[");
        IType[] allInterfaces = hierarchy.getAllSuperInterfaces(type);
        for (int i = 0; i < allInterfaces.length; i++) {
            if (i > 0) sb.append(",");
            appendTypeEntry(sb, allInterfaces[i]);
        }
        sb.append("]");

        // Subtypes
        sb.append(",\"subtypes\":[");
        IType[] subtypes = hierarchy.getAllSubtypes(type);
        for (int i = 0; i < subtypes.length; i++) {
            if (i > 0) sb.append(",");
            appendTypeEntry(sb, subtypes[i]);
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private void appendTypeEntry(StringBuilder sb, IType type) {
        String typeFqn = type.getFullyQualifiedName();
        String file = type.getResource() != null
                ? type.getResource().getFullPath().toString()
                : type.getFullyQualifiedName().replace('.', '/') + ".java";
        boolean binary = type.isBinary();
        sb.append("{\"fqn\":\"").append(HttpServer.escapeJson(typeFqn))
                .append("\",\"file\":\"").append(HttpServer.escapeJson(file))
                .append("\"");
        if (binary) sb.append(",\"binary\":true");
        sb.append("}");
    }

    // ---- /implementors?class=FQN&method=name ----

    String handleImplementors(Map<String, String> params) throws CoreException {
        String fqn = params.get("class");
        String methodName = params.get("method");
        if (fqn == null || fqn.isBlank()) {
            return "{\"error\":\"Missing 'class' parameter\"}";
        }
        if (methodName == null || methodName.isBlank()) {
            return "{\"error\":\"Missing 'method' parameter\"}";
        }

        IType type = findType(fqn);
        if (type == null) {
            return "{\"error\":\"Type not found: " + HttpServer.escapeJson(fqn) + "\"}";
        }

        int arity = parseArity(params.get("arity"));
        IMethod method = findMethod(type, methodName, arity);
        if (method == null) {
            return "{\"error\":\"Method not found: " + HttpServer.escapeJson(methodName)
                    + " in " + HttpServer.escapeJson(fqn) + "\"}";
        }

        ITypeHierarchy hierarchy = type.newTypeHierarchy(null);
        IType[] subtypes = hierarchy.getAllSubtypes(type);

        List<String> results = new ArrayList<>();
        for (IType sub : subtypes) {
            if (sub.isAnonymous()) continue;
            try {
                for (IMethod m : sub.getMethods()) {
                    if (m.getElementName().equals(methodName)
                            && (arity < 0 || m.getNumberOfParameters() == arity)) {
                        String file = sub.getResource() != null
                                ? sub.getResource().getFullPath().toString()
                                : sub.getFullyQualifiedName().replace('.', '/') + ".java";
                        int line = getLineOfMember(m);
                        results.add("{\"fqn\":\"" + HttpServer.escapeJson(sub.getFullyQualifiedName())
                                + "\",\"file\":\"" + HttpServer.escapeJson(file)
                                + "\",\"line\":" + line + "}");
                        break;
                    }
                }
            } catch (Exception e) { /* skip problematic types */ }
        }

        return "[" + String.join(",", results) + "]";
    }

    // ---- /type-info?class=FQN ----

    String handleTypeInfo(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        if (fqn == null || fqn.isBlank()) {
            return "{\"error\":\"Missing 'class' parameter\"}";
        }

        IType type = findType(fqn);
        if (type == null) {
            return "{\"error\":\"Type not found: " + HttpServer.escapeJson(fqn) + "\"}";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");

        // Basic info
        sb.append("\"fqn\":\"").append(HttpServer.escapeJson(type.getFullyQualifiedName())).append("\"");

        String kind;
        if (type.isInterface()) kind = "interface";
        else if (type.isEnum()) kind = "enum";
        else if (type.isAnnotation()) kind = "annotation";
        else kind = "class";
        sb.append(",\"kind\":\"").append(kind).append("\"");

        String file = getFilePath(type);
        sb.append(",\"file\":\"").append(HttpServer.escapeJson(file)).append("\"");
        if (type.isBinary()) {
            sb.append(",\"binary\":true");
        }

        // Superclass
        String superSig = type.getSuperclassTypeSignature();
        if (superSig != null) {
            sb.append(",\"superclass\":\"")
                    .append(HttpServer.escapeJson(Signature.toString(superSig)))
                    .append("\"");
        }

        // Interfaces
        String[] ifaceSigs = type.getSuperInterfaceTypeSignatures();
        sb.append(",\"interfaces\":[");
        for (int i = 0; i < ifaceSigs.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(HttpServer.escapeJson(Signature.toString(ifaceSigs[i])))
                    .append("\"");
        }
        sb.append("]");

        // Fields
        sb.append(",\"fields\":[");
        IField[] fields = type.getFields();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(",");
            IField f = fields[i];
            String fieldType = Signature.toString(f.getTypeSignature());
            String mods = Flags.toString(f.getFlags());
            int line = getLineOfMember(f);
            sb.append("{\"name\":\"").append(HttpServer.escapeJson(f.getElementName())).append("\"");
            sb.append(",\"type\":\"").append(HttpServer.escapeJson(fieldType)).append("\"");
            if (!mods.isEmpty()) {
                sb.append(",\"modifiers\":\"").append(HttpServer.escapeJson(mods)).append("\"");
            }
            sb.append(",\"line\":").append(line).append("}");
        }
        sb.append("]");

        // Methods
        sb.append(",\"methods\":[");
        IMethod[] methods = type.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (i > 0) sb.append(",");
            IMethod m = methods[i];
            String sig = buildSignature(m);
            int line = getLineOfMember(m);
            sb.append("{\"name\":\"").append(HttpServer.escapeJson(m.getElementName())).append("\"");
            sb.append(",\"signature\":\"").append(HttpServer.escapeJson(sig)).append("\"");
            sb.append(",\"line\":").append(line).append("}");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    // ---- /source?class=FQN[&method=name] ----

    HttpServer.Response handleSource(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");

        if (fqn == null || fqn.isBlank()) {
            return HttpServer.Response.json("{\"error\":\"Missing 'class' parameter\"}");
        }

        IType type = findType(fqn);
        if (type == null) {
            return HttpServer.Response.json("{\"error\":\"Type not found: " + HttpServer.escapeJson(fqn) + "\"}");
        }

        String file = getFilePath(type);
        String fullSource = getFullSource(type);

        if (methodName != null && !methodName.isBlank()) {
            int arity = parseArity(params.get("arity"));
            List<IMethod> methods = findMethods(type, methodName, arity);
            if (methods.isEmpty()) {
                return HttpServer.Response.json("{\"error\":\"Method not found: " + HttpServer.escapeJson(methodName)
                        + " in " + HttpServer.escapeJson(fqn) + "\"}");
            }

            if (methods.size() == 1) {
                return singleMemberResponse(methods.get(0), file, fullSource);
            }

            // Multiple overloads — each block prefixed with :startLine-endLine
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < methods.size(); i++) {
                if (i > 0) sb.append("\n\n");
                IMethod method = methods.get(i);
                String source = method.getSource();
                if (source == null) continue;
                int[] lines = memberLines(method, fullSource);
                sb.append(":").append(lines[0]).append("-").append(lines[1]).append("\n");
                sb.append(source);
            }
            return HttpServer.Response.text(sb.toString(), Map.of(
                    "X-File", file,
                    "X-Start-Line", "-1",
                    "X-End-Line", "-1"));
        }

        // Full class source
        return singleMemberResponse(type, file, fullSource);
    }

    private HttpServer.Response singleMemberResponse(IMember member, String file, String fullSource) throws Exception {
        String source = member.getSource();
        if (source == null) {
            return HttpServer.Response.json("{\"error\":\"Source not available\"}");
        }
        int[] lines = memberLines(member, fullSource);
        return HttpServer.Response.text(source, Map.of(
                "X-File", file,
                "X-Start-Line", String.valueOf(lines[0]),
                "X-End-Line", String.valueOf(lines[1])));
    }

    private int[] memberLines(IMember member, String fullSource) throws Exception {
        ISourceRange range = member.getSourceRange();
        if (fullSource != null && range != null && range.getOffset() >= 0) {
            return new int[] {
                    offsetToLine(fullSource, range.getOffset()),
                    offsetToLine(fullSource, range.getOffset() + range.getLength())
            };
        }
        return new int[] { -1, -1 };
    }

    private String getFullSource(IType type) throws Exception {
        ICompilationUnit cu = type.getCompilationUnit();
        if (cu != null) return cu.getSource();
        IClassFile cf = type.getClassFile();
        if (cf != null) return cf.getSource();
        return null;
    }

    // ---- helpers ----

    private String getFilePath(IType type) {
        if (type.getResource() != null) {
            return type.getResource().getFullPath().toString();
        }
        // Binary type — show FQN-based path
        return type.getFullyQualifiedName().replace('.', '/') + ".java";
    }

    private IType findType(String fqn) throws JavaModelException {
        var model = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot());
        for (IJavaProject project : model.getJavaProjects()) {
            IType type = project.findType(fqn);
            if (type != null && type.exists()) return type;
        }
        return null;
    }

    private List<IMethod> findMethods(IType type, String name, int arity) throws JavaModelException {
        List<IMethod> result = new ArrayList<>();
        for (IMethod m : type.getMethods()) {
            if (m.getElementName().equals(name)) {
                if (arity < 0 || m.getNumberOfParameters() == arity) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    private IMethod findMethod(IType type, String name, int arity) throws JavaModelException {
        for (IMethod m : type.getMethods()) {
            if (m.getElementName().equals(name)) {
                if (arity < 0 || m.getNumberOfParameters() == arity) return m;
            }
        }
        return null;
    }

    private int parseArity(String s) {
        if (s == null) return -1;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return -1; }
    }

    private String buildSignature(IMethod m) throws JavaModelException {
        StringBuilder sig = new StringBuilder();
        String mods = Flags.toString(m.getFlags());
        if (!mods.isEmpty()) sig.append(mods).append(" ");
        if (!m.isConstructor()) {
            sig.append(Signature.toString(m.getReturnType())).append(" ");
        }
        sig.append(m.getElementName()).append("(");
        String[] paramTypes = m.getParameterTypes();
        String[] paramNames = m.getParameterNames();
        for (int j = 0; j < paramTypes.length; j++) {
            if (j > 0) sig.append(", ");
            sig.append(Signature.toString(paramTypes[j]))
                    .append(" ").append(paramNames[j]);
        }
        sig.append(")");
        return sig.toString();
    }

    private int getLineOfMember(IMember member) {
        try {
            ISourceRange range = member.getNameRange();
            if (range != null && range.getOffset() >= 0) {
                ICompilationUnit cu = member.getCompilationUnit();
                String source;
                if (cu != null) {
                    source = cu.getSource();
                } else {
                    IClassFile cf = member.getClassFile();
                    source = cf != null ? cf.getSource() : null;
                }
                if (source != null) {
                    return offsetToLine(source, range.getOffset());
                }
            }
        } catch (Exception e) { /* ignore */ }
        return -1;
    }

    private int offsetToLine(String source, int offset) {
        int line = 1;
        int limit = Math.min(offset, source.length());
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }

    private String getMatchProject(SearchMatch match) {
        if (match.getElement() instanceof IMember member) {
            IJavaProject jp = member.getJavaProject();
            if (jp != null) return jp.getElementName();
        }
        return null;
    }

    private String getEnclosingName(SearchMatch match) {
        Object element = match.getElement();
        if (element instanceof IMember member) {
            IType declaringType = member.getDeclaringType();
            String typeFqn = declaringType != null
                    ? declaringType.getFullyQualifiedName()
                    : (member instanceof IType t
                            ? t.getFullyQualifiedName()
                            : member.getElementName());
            if (member instanceof IMethod m) {
                try {
                    return typeFqn + "." + compactSignature(m);
                } catch (JavaModelException e) {
                    return typeFqn + "." + m.getElementName() + "()";
                }
            }
            if (member instanceof IField f) {
                return typeFqn + "." + f.getElementName();
            }
            if (member instanceof IType t) {
                return t.getFullyQualifiedName();
            }
        }
        return null;
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

    private String getMatchFile(SearchMatch match) {
        // Binary type — resolve jar path from classpath
        if (match.getElement() instanceof IMember member && member.isBinary()) {
            try {
                IPackageFragmentRoot root = (IPackageFragmentRoot)
                        member.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
                if (root != null && root.getPath() != null) {
                    return root.getPath().toOSString();
                }
            } catch (Exception e) { /* ignore */ }
        }
        if (match.getResource() != null) {
            return match.getResource().getFullPath().toString();
        }
        return "?";
    }

    private String getLineContent(SearchMatch match) {
        try {
            String source = null;
            if (match.getResource() instanceof IFile file) {
                ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
                source = cu.getSource();
            } else if (match.getElement() instanceof IMember member) {
                IClassFile cf = member.getClassFile();
                if (cf != null) source = cf.getSource();
            }
            if (source != null) {
                int offset = match.getOffset();
                int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
                int lineEnd = source.indexOf('\n', offset);
                if (lineEnd < 0) lineEnd = source.length();
                return source.substring(lineStart, lineEnd).trim();
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    private int getLine(SearchMatch match) {
        try {
            if (match.getResource() instanceof IFile file) {
                ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
                String source = cu.getSource();
                if (source != null) {
                    return offsetToLine(source, match.getOffset());
                }
            }
        } catch (Exception e) { /* ignore */ }
        return -1;
    }
}
