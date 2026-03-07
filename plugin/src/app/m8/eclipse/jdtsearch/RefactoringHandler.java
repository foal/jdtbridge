package app.m8.eclipse.jdtsearch;

import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.text.edits.TextEdit;

/**
 * Handlers for /organize-imports and /format endpoints.
 */
class RefactoringHandler {

    String handleOrganizeImports(Map<String, String> params) throws Exception {
        String filePath = params.get("file");
        if (filePath == null || filePath.isBlank()) {
            return "{\"error\":\"Missing 'file' parameter\"}";
        }

        ICompilationUnit cu = findCompilationUnit(filePath);
        if (cu == null) {
            return "{\"error\":\"Java file not found: "
                    + HttpServer.escapeJson(filePath) + "\"}";
        }

        // Refresh from disk in case file was edited externally
        cu.getResource().refreshLocal(IResource.DEPTH_ZERO, null);

        // For ambiguous imports, pick the first candidate
        OrganizeImportsOperation.IChooseImportQuery query =
                (openChoices, ranges) -> {
                    TypeNameMatch[] result =
                            new TypeNameMatch[openChoices.length];
                    for (int i = 0; i < openChoices.length; i++) {
                        result[i] = openChoices[i][0];
                    }
                    return result;
                };

        // Use working copy so changes are saved to disk
        cu.becomeWorkingCopy(null);
        try {
            OrganizeImportsOperation op = new OrganizeImportsOperation(
                    cu,
                    null,  // astRoot — let it parse internally
                    true,  // restoreExistingImports
                    false, // save — we commit manually
                    true,  // allowSyntaxErrors
                    query);
            op.run(null);

            int added = op.getNumberOfImportsAdded();
            int removed = op.getNumberOfImportsRemoved();

            if (added > 0 || removed > 0) {
                cu.commitWorkingCopy(true, null);
            }

            return "{\"added\":" + added + ",\"removed\":" + removed + "}";
        } finally {
            cu.discardWorkingCopy();
        }
    }

    String handleFormat(Map<String, String> params) throws Exception {
        String filePath = params.get("file");
        if (filePath == null || filePath.isBlank()) {
            return "{\"error\":\"Missing 'file' parameter\"}";
        }

        ICompilationUnit cu = findCompilationUnit(filePath);
        if (cu == null) {
            return "{\"error\":\"Java file not found: "
                    + HttpServer.escapeJson(filePath) + "\"}";
        }

        // Refresh from disk in case file was edited externally
        cu.getResource().refreshLocal(IResource.DEPTH_ZERO, null);

        String source = cu.getSource();
        Map<String, String> options = cu.getJavaProject().getOptions(true);

        CodeFormatter formatter = ToolFactory.createCodeFormatter(options);
        TextEdit edit = formatter.format(
                CodeFormatter.K_COMPILATION_UNIT,
                source, 0, source.length(),
                0, System.lineSeparator());

        if (edit == null) {
            return "{\"modified\":false,\"reason\":"
                    + "\"formatter returned no edits (syntax error?)\"}";
        }

        Document document = new Document(source);
        edit.apply(document);
        String formatted = document.get();

        if (formatted.equals(source)) {
            return "{\"modified\":false}";
        }

        // Write back via working copy
        cu.becomeWorkingCopy(null);
        try {
            cu.getBuffer().setContents(formatted);
            cu.commitWorkingCopy(true, null);
        } finally {
            cu.discardWorkingCopy();
        }

        return "{\"modified\":true}";
    }

    private ICompilationUnit findCompilationUnit(String filePath) {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IResource resource = root.findMember(filePath);
        if (resource == null || !(resource instanceof IFile file)) {
            return null;
        }
        ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
        if (cu == null || !cu.exists()) {
            return null;
        }
        return cu;
    }

    // ---- /rename ----

    String handleRename(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        String newName = params.get("newName");

        if (fqn == null || fqn.isBlank()) {
            return "{\"error\":\"Missing 'class' parameter\"}";
        }
        if (newName == null || newName.isBlank()) {
            return "{\"error\":\"Missing 'newName' parameter\"}";
        }

        IType type = findType(fqn);
        if (type == null) {
            return "{\"error\":\"Type not found: "
                    + HttpServer.escapeJson(fqn) + "\"}";
        }

        String methodName = params.get("method");
        String fieldName = params.get("field");
        int arity = parseArity(params.get("arity"));

        IJavaElement element;
        String refactoringId;

        if (fieldName != null && !fieldName.isBlank()) {
            IField field = type.getField(fieldName);
            if (field == null || !field.exists()) {
                return "{\"error\":\"Field not found: "
                        + HttpServer.escapeJson(fieldName)
                        + " in " + HttpServer.escapeJson(fqn) + "\"}";
            }
            element = field;
            refactoringId = IJavaRefactorings.RENAME_FIELD;
        } else if (methodName != null && !methodName.isBlank()) {
            IMethod method = findMethod(type, methodName, arity);
            if (method == null) {
                return "{\"error\":\"Method not found: "
                        + HttpServer.escapeJson(methodName)
                        + " in " + HttpServer.escapeJson(fqn) + "\"}";
            }
            element = method;
            refactoringId = IJavaRefactorings.RENAME_METHOD;
        } else {
            element = type;
            refactoringId = IJavaRefactorings.RENAME_TYPE;
        }

        RenameJavaElementDescriptor descriptor = (RenameJavaElementDescriptor)
                RefactoringCore.getRefactoringContribution(refactoringId)
                        .createDescriptor();
        descriptor.setJavaElement(element);
        descriptor.setNewName(newName);
        descriptor.setUpdateReferences(true);

        RefactoringStatus status = new RefactoringStatus();
        Refactoring refactoring = descriptor.createRefactoring(status);
        if (status.hasFatalError()) {
            return "{\"error\":\""
                    + HttpServer.escapeJson(status.getMessageMatchingSeverity(
                            RefactoringStatus.FATAL)) + "\"}";
        }

        status.merge(refactoring.checkInitialConditions(
                new NullProgressMonitor()));
        if (status.hasFatalError()) {
            return "{\"error\":\""
                    + HttpServer.escapeJson(status.getMessageMatchingSeverity(
                            RefactoringStatus.FATAL)) + "\"}";
        }

        status.merge(refactoring.checkFinalConditions(
                new NullProgressMonitor()));
        if (status.hasFatalError()) {
            return "{\"error\":\""
                    + HttpServer.escapeJson(status.getMessageMatchingSeverity(
                            RefactoringStatus.FATAL)) + "\"}";
        }

        Change change = refactoring.createChange(new NullProgressMonitor());
        change.perform(new NullProgressMonitor());

        return "{\"ok\":true}";
    }

    private IType findType(String fqn) throws JavaModelException {
        var model = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot());
        for (IJavaProject project : model.getJavaProjects()) {
            IType type = project.findType(fqn);
            if (type != null && type.exists()) return type;
        }
        return null;
    }

    private IMethod findMethod(IType type, String name, int arity)
            throws JavaModelException {
        for (IMethod m : type.getMethods()) {
            if (m.getElementName().equals(name)) {
                if (arity < 0 || m.getNumberOfParameters() == arity)
                    return m;
            }
        }
        return null;
    }

    private int parseArity(String s) {
        if (s == null) return -1;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return -1; }
    }
}
