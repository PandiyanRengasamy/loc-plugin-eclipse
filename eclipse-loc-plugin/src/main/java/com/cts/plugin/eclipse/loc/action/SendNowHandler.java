package com.cts.plugin.eclipse.loc.action;

import com.cts.plugin.eclipse.loc.EclipseLocPlugin;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * SendNowHandler — handles the "GenAI LOC → Send LOC Data Now" menu action.
 * Manually triggers an immediate flush of pending LOC events to the REST service.
 */
public class SendNowHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        EclipseLocPlugin plugin = EclipseLocPlugin.getInstance();
        if (plugin == null || plugin.getEventDispatcher() == null) {
            MessageDialog.openWarning(shell, "GenAI LOC Tracker",
                    "Plugin not initialised yet. Please wait and try again.");
            return null;
        }
        plugin.getEventDispatcher().flushNow();
        MessageDialog.openInformation(shell, "GenAI LOC Tracker",
                "LOC data flush triggered. Check Eclipse Error Log for details.");
        return null;
    }
}

