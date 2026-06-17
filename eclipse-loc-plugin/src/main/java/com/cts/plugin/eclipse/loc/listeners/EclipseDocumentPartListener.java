package com.cts.plugin.eclipse.loc.listeners;

import com.cts.plugin.eclipse.loc.service.EventDispatcher;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.ui.*;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import java.util.HashSet;
import java.util.Set;

/**
 * EclipseDocumentPartListener
 *
 * Listens for editor open/close events via IPartListener2 and attaches an
 * IDocumentListener to each opened text editor. The document listener fires
 * documentAboutToBeChanged() BEFORE every write — including Copilot's live preview.
 *
 * At that moment we store {lineCount, charCount} into PRE_STATE_CACHE so the
 * mouse listener can always find the true PRE state when "Accept"/"Keep All" is clicked.
 *
 * This is the Eclipse equivalent of:
 *   EditorFactory.getInstance().getEventMulticaster().addDocumentListener(...)
 * in the IntelliJ plugin.
 */
public class EclipseDocumentPartListener implements IPartListener2 {

    private static final ILog LOG = Platform.getLog(EclipseDocumentPartListener.class);

    /** Tracks documents we've already attached a listener to (avoid duplicates). */
    private final Set<IDocument> attached = new HashSet<>();

    private EclipseDocumentPartListener() {}

    // ── IPartListener2 ────────────────────────────────────────────────────────

    @Override
    public void partOpened(IWorkbenchPartReference ref) {
        attachToEditor(ref);
    }

    @Override
    public void partActivated(IWorkbenchPartReference ref) {
        attachToEditor(ref); // re-attach on activation in case editor was reused
    }

    @Override public void partBroughtToTop(IWorkbenchPartReference ref) { attachToEditor(ref); }
    @Override public void partClosed(IWorkbenchPartReference ref) { detachFromEditor(ref); }
    @Override public void partDeactivated(IWorkbenchPartReference ref) {}
    @Override public void partHidden(IWorkbenchPartReference ref) {}
    @Override public void partVisible(IWorkbenchPartReference ref) {}
    @Override public void partInputChanged(IWorkbenchPartReference ref) { attachToEditor(ref); }

    // ── Attach / Detach ───────────────────────────────────────────────────────

    private void attachToEditor(IWorkbenchPartReference ref) {
        IWorkbenchPart part = ref.getPart(false);
        if (!(part instanceof ITextEditor)) return;
        ITextEditor te = (ITextEditor) part;
        IDocumentProvider dp = te.getDocumentProvider();
        if (dp == null) return;
        IDocument doc = dp.getDocument(te.getEditorInput());
        if (doc == null || attached.contains(doc)) return;

        String path = getPath(te);
        doc.addDocumentListener(new IDocumentListener() {
            @Override
            public void documentAboutToBeChanged(DocumentEvent e) {
                // Capture PRE state BEFORE the write
                String cacheKey = System.identityHashCode(doc) + ":" + (path != null ? path : "?");
                int lines = doc.getNumberOfLines();
                int chars = doc.getLength();
                CopilotKeepAllMouseListener.PRE_STATE_CACHE.put(cacheKey, new int[]{lines, chars});
                LOG.info("GenAI-LOC | [DocListener] PRE captured: "
                        + (path != null ? path.substring(path.lastIndexOf('\\') + 1) : "?")
                        + "  lines=" + lines + "  chars=" + chars
                        + "  key=" + cacheKey);
            }

            @Override
            public void documentChanged(DocumentEvent e) {
                // No action needed — POST snapshot is taken by mouse listener
            }
        });

        attached.add(doc);
        LOG.info("GenAI-LOC | DocumentListener attached: " + (path != null ? path : "unknown"));
    }

    private void detachFromEditor(IWorkbenchPartReference ref) {
        IWorkbenchPart part = ref.getPart(false);
        if (!(part instanceof ITextEditor)) return;
        ITextEditor te = (ITextEditor) part;
        IDocumentProvider dp = te.getDocumentProvider();
        if (dp == null) return;
        IDocument doc = dp.getDocument(te.getEditorInput());
        if (doc != null) {
            attached.remove(doc);
            LOG.info("GenAI-LOC | DocumentListener detached (editor closed)");
        }
    }

    private static String getPath(ITextEditor te) {
        try {
            org.eclipse.core.resources.IFile file =
                    te.getEditorInput().getAdapter(org.eclipse.core.resources.IFile.class);
            if (file != null) return file.getLocation().toOSString();
        } catch (Exception ignored) {}
        return null;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    public static void register(EventDispatcher dispatcher) {
        try {
            EclipseDocumentPartListener listener = new EclipseDocumentPartListener();
            IWorkbench wb = PlatformUI.getWorkbench();
            for (IWorkbenchWindow win : wb.getWorkbenchWindows()) {
                for (IWorkbenchPage page : win.getPages()) {
                    page.addPartListener(listener);
                    // Attach to already-open editors
                    for (IEditorReference ref : page.getEditorReferences()) {
                        listener.attachToEditor(ref);
                    }
                }
            }
            // Also listen to future windows
            wb.addWindowListener(new IWindowListener() {
                @Override
                public void windowOpened(IWorkbenchWindow win) {
                    for (IWorkbenchPage page : win.getPages()) {
                        page.addPartListener(listener);
                    }
                }
                @Override public void windowClosed(IWorkbenchWindow w) {}
                @Override public void windowActivated(IWorkbenchWindow w) {}
                @Override public void windowDeactivated(IWorkbenchWindow w) {}
            });
            LOG.info("GenAI-LOC | EclipseDocumentPartListener registered ✅");
        } catch (Exception ex) {
            LOG.warn("GenAI-LOC | EclipseDocumentPartListener.register failed: " + ex.getMessage());
        }
    }
}

