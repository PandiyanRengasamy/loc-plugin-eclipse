package com.cts.plugin.eclipse.loc.listeners;

import com.cts.plugin.eclipse.loc.model.FileChange;
import com.cts.plugin.eclipse.loc.model.LOCRequestPayload;
import com.cts.plugin.eclipse.loc.service.EventDispatcher;
import com.cts.plugin.eclipse.loc.settings.GenAiLocSettings;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.texteditor.IDocumentProvider;

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CopilotKeepAllMouseListener — Eclipse port of the IntelliJ plugin.
 *
 * Eclipse + GitHub Copilot Chat behaviour:
 *   Copilot applies its generated code to the editor as a LIVE PREVIEW before
 *   the user clicks "Accept" / "Keep All". This means PRE == POST at click time → diff = 0.
 *
 * Solution (same as IntelliJ plugin):
 *   • An Eclipse IDocumentListener (registered via PartListener) fires
 *     documentAboutToBeChanged() BEFORE every editor write — including Copilot's preview.
 *     We capture {lineCount, charCount} into PRE_STATE_CACHE at that moment.
 *   • MOUSE_PRESSED arms on "Accept" / "Keep All" / "Keep".
 *   • MOUSE_RELEASED schedules a POST snapshot 800ms later, diffs PRE_STATE_CACHE
 *     vs POST, then sends one bundled LOCRequestPayload to the REST service.
 *
 * Eclipse button text variants (Copilot Chat):
 *   "Accept", "Accept All", "Keep", "Keep All"
 */
public class CopilotKeepAllMouseListener implements AWTEventListener {

    private static final ILog LOG = Platform.getLog(CopilotKeepAllMouseListener.class);
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** Exact button labels (lowercase) that trigger LOC capture in Eclipse Copilot Chat. */
    private static final Set<String> KEEP_BUTTON_EXACT = new HashSet<>(Arrays.asList(
            "accept", "accept all", "keep", "keep all"
    ));

    private static final int SNAPSHOT_DELAY_MS = 800;

    /**
     * PRE_STATE_CACHE — populated by the document listener BEFORE every write.
     * Key = IDocument identity hash (stable per-editor document object).
     * Value = int[2]{lineCount, charCount} captured just before the write.
     */
    public static final ConcurrentHashMap<String, int[]> PRE_STATE_CACHE
            = new ConcurrentHashMap<>();

    private final EventDispatcher dispatcher;
    private final AtomicReference<String> armedButtonText = new AtomicReference<>(null);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "GenAI-LOC-EclipseMouse");
                t.setDaemon(true);
                return t;
            });

    private CopilotKeepAllMouseListener(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    // ── AWTEventListener ─────────────────────────────────────────────────────

    @Override
    public void eventDispatched(AWTEvent event) {
        if (!(event instanceof MouseEvent)) return;
        MouseEvent me = (MouseEvent) event;
        Component clicked = me.getComponent();
        if (clicked == null) return;

        // ── MOUSE_PRESSED: arm on Accept / Keep / Keep All ────────────────────
        if (me.getID() == MouseEvent.MOUSE_PRESSED) {
            String text = extractButtonText(clicked);
            if (text == null) return;
            String lower = text.trim().toLowerCase();
            if (!KEEP_BUTTON_EXACT.contains(lower)) return;
            armedButtonText.set(text);
            LOG.info("GenAI-LOC | ⬇ MOUSE_PRESSED armed: '" + text + "'"
                    + "  component=" + clicked.getClass().getName());
            return;
        }

        // ── MOUSE_RELEASED: schedule diff + send ──────────────────────────────
        if (me.getID() != MouseEvent.MOUSE_RELEASED) return;
        String armed = armedButtonText.getAndSet(null);
        if (armed == null) return;

        LOG.info("GenAI-LOC | ⬆ MOUSE_RELEASED — scheduling POST snapshot (armed='" + armed + "')");

        scheduler.schedule(() -> {
            // Take POST snapshot on SWT UI thread
            Map<String, EditorSnapshot> postSnapshots = snapshotAllEditors();
            LOG.info("GenAI-LOC | POST snapshots: " + postSnapshots.size() + " file(s)");

            List<FileDiff> diffs = new ArrayList<>();
            for (Map.Entry<String, EditorSnapshot> entry : postSnapshots.entrySet()) {
                String cacheKey = entry.getKey();
                EditorSnapshot post = entry.getValue();

                int[] pre = PRE_STATE_CACHE.get(cacheKey);
                if (pre == null) {
                    LOG.info("GenAI-LOC |   SKIP (no PRE cache) file=" + post.fileName);
                    continue;
                }
                int preLines = pre[0], preChars = pre[1];
                LOG.info("GenAI-LOC |   PRE(cache) file=" + post.fileName
                        + "  lines=" + preLines + "  chars=" + preChars);
                LOG.info("GenAI-LOC |   POST       file=" + post.fileName
                        + "  lines=" + post.lineCount + "  chars=" + post.charCount);

                double avgCharsPerLine = preLines > 0 ? (double) preChars / preLines : 40.0;
                int charDelta = post.charCount - preChars;
                int netLines  = post.lineCount - preLines;

                int linesAdded   = Math.max(0, netLines);
                int linesDeleted = Math.max(0, -netLines);

                // Hidden deletions: lines replaced but count increased
                if (linesAdded > 0 && charDelta < (int)(linesAdded * avgCharsPerLine)) {
                    int deficit = (int)(linesAdded * avgCharsPerLine) - charDelta;
                    int hidden  = (int)(deficit / avgCharsPerLine);
                    if (hidden > 0) linesDeleted = Math.max(linesDeleted, hidden);
                }

                // Modified lines heuristic
                int linesMod = 0;
                if (post.charCount != preChars) {
                    if (linesAdded == 0 && linesDeleted == 0) {
                        linesMod = Math.max(1, Math.abs(charDelta) / 40);
                    } else {
                        int expectedDelta = (int)((linesAdded - linesDeleted) * avgCharsPerLine);
                        int excess = Math.abs(charDelta - expectedDelta);
                        if (excess > (int)(avgCharsPerLine * 2)) {
                            linesMod = Math.max(1, excess / 40);
                        }
                    }
                }

                LOG.info("GenAI-LOC |   DIFF file=" + post.fileName
                        + "  +lines=" + linesAdded + "  -lines=" + linesDeleted
                        + "  ~lines=" + linesMod);

                if (linesAdded == 0 && linesDeleted == 0 && linesMod == 0) continue;
                diffs.add(new FileDiff(post, linesAdded, linesMod, linesDeleted));
            }

            if (diffs.isEmpty()) {
                LOG.info("GenAI-LOC | No file changes detected — zero-change event skipped");
                return;
            }

            // Build bundled request
            GenAiLocSettings s = GenAiLocSettings.getInstance();
            if (!s.isEnabled()) { LOG.info("GenAI-LOC | Plugin disabled"); return; }

            String developerId   = s.getDeveloperId().isBlank()
                    ? System.getProperty("user.name", "unknown") : s.getDeveloperId();
            String developerName = s.getDeveloperName().isBlank() ? developerId : s.getDeveloperName();
            String projectId     = s.getProjectId().isBlank() ? "ECLIPSE_PROJECT" : s.getProjectId();
            String sprintId      = s.getSprintId().isBlank() ? null : s.getSprintId();
            String tool          = "COPILOT";
            String sessionId     = dispatcher.getSessionId();
            String timestamp     = LocalDateTime.now().format(TS_FMT);

            List<FileChange> allFileChanges = new ArrayList<>();
            for (FileDiff d : diffs) {
                String cls = d.snap.fileName.contains(".")
                        ? d.snap.fileName.substring(0, d.snap.fileName.lastIndexOf('.'))
                        : d.snap.fileName;
                allFileChanges.add(new FileChange(d.snap.filePath, d.snap.fileName, cls,
                        d.linesAdded, d.linesModified, d.linesDeleted));
            }

            int totalAdded    = allFileChanges.stream().mapToInt(FileChange::getLinesAdded).sum();
            int totalModified = allFileChanges.stream().mapToInt(FileChange::getLinesModified).sum();
            int totalDeleted  = allFileChanges.stream().mapToInt(FileChange::getLinesDeleted).sum();

            FileChange first = allFileChanges.get(0);
            LOCRequestPayload bundled = new LOCRequestPayload(
                    developerId, developerName, projectId, sprintId,
                    first.getFilePath(), first.getFileName(), first.getClassName(),
                    "ECLIPSE", tool, "BROWNFIELD",
                    totalAdded, totalModified, totalDeleted,
                    true, 0.95, timestamp, sessionId, allFileChanges);

            LOG.info("GenAI-LOC | ENQUEUE bundled: files=" + allFileChanges.size()
                    + " +=" + totalAdded + " ~=" + totalModified + " -=" + totalDeleted);
            dispatcher.enqueue(bundled);
            dispatcher.flushNow();

            // Clear PRE cache for these files
            for (String k : postSnapshots.keySet()) PRE_STATE_CACHE.remove(k);

        }, SNAPSHOT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    // ── Editor snapshot (runs on SWT UI thread) ───────────────────────────────

    private static class EditorSnapshot {
        final String fileName, filePath, cacheKey;
        final int lineCount, charCount;
        EditorSnapshot(String fileName, String filePath, String cacheKey, int lineCount, int charCount) {
            this.fileName = fileName; this.filePath = filePath; this.cacheKey = cacheKey;
            this.lineCount = lineCount; this.charCount = charCount;
        }
    }

    private static class FileDiff {
        final EditorSnapshot snap;
        final int linesAdded, linesModified, linesDeleted;
        FileDiff(EditorSnapshot s, int a, int m, int d) {
            snap = s; linesAdded = a; linesModified = m; linesDeleted = d;
        }
    }

    /**
     * Snapshots all open text editors.
     * Must be called on or marshalled to the SWT Display thread.
     */
    private Map<String, EditorSnapshot> snapshotAllEditors() {
        final Map<String, EditorSnapshot>[] result = new Map[]{Collections.emptyMap()};
        Display.getDefault().syncExec(() -> {
            try {
                Map<String, EditorSnapshot> map = new LinkedHashMap<>();
                for (IWorkbenchWindow win : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                    for (IWorkbenchPage page : win.getPages()) {
                        for (IEditorReference ref : page.getEditorReferences()) {
                            IEditorPart part = ref.getEditor(false);
                            if (!(part instanceof ITextEditor)) continue;
                            ITextEditor te = (ITextEditor) part;
                            IDocumentProvider dp = te.getDocumentProvider();
                            if (dp == null) continue;
                            IDocument doc = dp.getDocument(te.getEditorInput());
                            if (doc == null) continue;
                            String path = getEditorPath(te);
                            if (path == null) continue;
                            String cacheKey = System.identityHashCode(doc) + ":" + path;
                            if (map.containsKey(cacheKey)) continue;
                            String fileName = path.contains("/")
                                    ? path.substring(path.lastIndexOf('/') + 1)
                                    : path.contains("\\")
                                    ? path.substring(path.lastIndexOf('\\') + 1)
                                    : path;
                            map.put(cacheKey, new EditorSnapshot(
                                    fileName, path, cacheKey,
                                    doc.getNumberOfLines(), doc.getLength()));
                        }
                    }
                }
                result[0] = map;
            } catch (Exception ex) {
                LOG.warn("GenAI-LOC | snapshotAllEditors: " + ex.getMessage());
            }
        });
        return result[0];
    }

    private static String getEditorPath(ITextEditor te) {
        try {
            org.eclipse.core.runtime.IPath loc = te.getEditorInput().getAdapter(
                    org.eclipse.core.runtime.IPath.class);
            if (loc != null) return loc.toOSString();
            // Fallback: IFile adapter
            org.eclipse.core.resources.IFile file = te.getEditorInput().getAdapter(
                    org.eclipse.core.resources.IFile.class);
            if (file != null) return file.getLocation().toOSString();
        } catch (Exception ignored) {}
        return null;
    }

    // ── Button text extraction ─────────────────────────────────────────────────

    private static String extractButtonText(Component c) {
        Component current = c;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            String t = getComponentText(current);
            if (t != null && !t.isBlank()) return t;
            String a = getAccessibleText(current);
            if (a != null && !a.isBlank()) return a;
            current = current.getParent();
        }
        return null;
    }

    private static String getComponentText(Component c) {
        try {
            if (c instanceof javax.swing.AbstractButton)
                return ((javax.swing.AbstractButton) c).getText();
            if (c instanceof javax.swing.JLabel)
                return ((javax.swing.JLabel) c).getText();
            try {
                java.lang.reflect.Method m = c.getClass().getMethod("getText");
                Object r = m.invoke(c);
                if (r instanceof String) return (String) r;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ex) {
                LOG.warn("GenAI-LOC | getComponentText reflective: " + ex.getMessage());
            }
        } catch (Exception ex) {
            LOG.warn("GenAI-LOC | getComponentText: " + ex.getMessage());
        }
        return null;
    }

    private static String getAccessibleText(Component c) {
        try {
            javax.accessibility.AccessibleContext ac = c.getAccessibleContext();
            if (ac != null) {
                String name = ac.getAccessibleName();
                if (name != null && !name.isBlank()) return name;
            }
            if (c instanceof javax.swing.JComponent) {
                String tip = ((javax.swing.JComponent) c).getToolTipText();
                if (tip != null && !tip.isBlank()) return tip;
            }
        } catch (Exception ex) {
            LOG.warn("GenAI-LOC | getAccessibleText: " + ex.getMessage());
        }
        return null;
    }

    // ── Registration ──────────────────────────────────────────────────────────

    public static void register(EventDispatcher dispatcher) {
        try {
            CopilotKeepAllMouseListener listener = new CopilotKeepAllMouseListener(dispatcher);

            // Register AWT mouse listener (works even inside Eclipse SWT shell
            // because Copilot Chat panel is rendered via embedded Chromium/CEF or Swing)
            Toolkit.getDefaultToolkit().addAWTEventListener(
                    listener, AWTEvent.MOUSE_EVENT_MASK);
            LOG.info("GenAI-LOC | CopilotKeepAllMouseListener registered ✅"
                    + "  matching: " + KEEP_BUTTON_EXACT);

            // Register Eclipse PartListener to hook document changes for PRE cache
            EclipseDocumentPartListener.register(dispatcher);
        } catch (Exception ex) {
            LOG.warn("GenAI-LOC | register failed: " + ex.getMessage());
        }
    }
}

