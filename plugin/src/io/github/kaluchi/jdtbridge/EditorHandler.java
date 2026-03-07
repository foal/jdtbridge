package io.github.kaluchi.jdtbridge;

import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.PlatformUI;

/**
 * Handlers for Eclipse editor interaction: active-editor, open.
 */
class EditorHandler {

    String handleActiveEditor(Map<String, String> params) throws Exception {
        String[] result = new String[1];
        Display.getDefault().syncExec(() -> {
            try {
                var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window == null || window.getActivePage() == null) {
                    result[0] = "{\"file\":null}";
                    return;
                }
                IEditorPart editor = window.getActivePage().getActiveEditor();
                if (editor == null) {
                    result[0] = "{\"file\":null}";
                    return;
                }

                String file = null;
                var input = editor.getEditorInput();
                if (input instanceof IFileEditorInput fileInput) {
                    file = fileInput.getFile().getFullPath().toString();
                }

                int line = -1;
                var sp = editor.getSite().getSelectionProvider();
                if (sp != null) {
                    var sel = sp.getSelection();
                    if (sel instanceof ITextSelection ts) {
                        line = ts.getStartLine() + 1;
                    }
                }

                if (file != null) {
                    result[0] = "{\"file\":\"" + HttpServer.escapeJson(file)
                            + "\",\"line\":" + line + "}";
                } else {
                    result[0] = "{\"file\":null}";
                }
            } catch (Exception e) {
                result[0] = "{\"error\":\""
                        + HttpServer.escapeJson(e.getMessage()) + "\"}";
            }
        });
        return result[0];
    }

    String handleOpen(Map<String, String> params) throws Exception {
        String fqn = params.get("class");
        String methodName = params.get("method");

        if (fqn == null || fqn.isBlank()) {
            return "{\"error\":\"Missing 'class' parameter\"}";
        }

        IType type = findType(fqn);
        if (type == null) {
            return "{\"error\":\"Type not found: "
                    + HttpServer.escapeJson(fqn) + "\"}";
        }

        IJavaElement target = type;
        if (methodName != null && !methodName.isBlank()) {
            for (IMethod m : type.getMethods()) {
                if (m.getElementName().equals(methodName)) {
                    target = m;
                    break;
                }
            }
        }

        final IJavaElement element = target;
        String[] result = new String[1];
        Display.getDefault().syncExec(() -> {
            try {
                IEditorPart editor = JavaUI.openInEditor(element);
                JavaUI.revealInEditor(editor, element);
                result[0] = "{\"ok\":true}";
            } catch (Exception e) {
                result[0] = "{\"error\":\""
                        + HttpServer.escapeJson(e.getMessage()) + "\"}";
            }
        });
        return result[0];
    }

    private IType findType(String fqn) throws JavaModelException {
        var model = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot());
        for (IJavaProject project : model.getJavaProjects()) {
            IType type = project.findType(fqn);
            if (type != null && type.exists()) return type;
        }
        return null;
    }
}
