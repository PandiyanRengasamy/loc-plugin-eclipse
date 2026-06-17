package com.cts.plugin.eclipse.loc.action;

import com.cts.plugin.eclipse.loc.EclipseLocPlugin;
import com.cts.plugin.eclipse.loc.model.FileChange;
import com.cts.plugin.eclipse.loc.model.LOCRequestPayload;
import com.cts.plugin.eclipse.loc.service.EventDispatcher;
import com.cts.plugin.eclipse.loc.settings.GenAiLocSettings;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

/**
 * TestBackendHandler — "GenAI LOC → Test Backend Connection".
 *
 * Sends a single synthetic LOC event through the existing EventDispatcher
 * so the user can verify the backend URL, network reachability, and HTTP
 * status without waiting for a real Copilot accept.
 */
public class TestBackendHandler extends AbstractHandler {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        EclipseLocPlugin plugin = EclipseLocPlugin.getInstance();
        EventDispatcher dispatcher = plugin != null ? plugin.getEventDispatcher() : null;
        if (dispatcher == null) {
            MessageDialog.openWarning(shell, "GenAI LOC Tracker",
                    "Plugin not initialised yet. Please wait and try again.");
            return null;
        }

        GenAiLocSettings s = GenAiLocSettings.getInstance();
        String developerId = s.getDeveloperId().isBlank()
                ? System.getProperty("user.name", "unknown") : s.getDeveloperId();
        String developerName = s.getDeveloperName().isBlank() ? developerId : s.getDeveloperName();
        String projectId = s.getProjectId().isBlank() ? "EclipseProject" : s.getProjectId();
        String sprintId = s.getSprintId().isBlank() ? null : s.getSprintId();
        String timestamp = LocalDateTime.now().format(TS_FMT);

        FileChange fc = FileChange.of("TEST_FILE.java", "TEST_FILE.java", 1, 0, 0);
        LOCRequestPayload testEvent = new LOCRequestPayload(
                developerId, developerName, projectId, sprintId,
                fc.getFilePath(), fc.getFileName(), fc.getClassName(),
                "ECLIPSE", "TEST", "BROWNFIELD",
                1, 0, 0,
                false, null,
                timestamp, dispatcher.getSessionId(),
                Collections.singletonList(fc));

        dispatcher.enqueue(testEvent);
        dispatcher.flushNow();

        MessageDialog.openInformation(shell, "GenAI LOC Tracker — Test",
                "Test event sent.\n\n"
                + "Backend URL : " + s.getBackendUrl() + "\n"
                + "Developer   : " + developerId + "\n"
                + "Plugin on   : " + s.isEnabled() + "\n\n"
                + "Check Window → Show View → Error Log for the HTTP response.");
        return null;
    }
}