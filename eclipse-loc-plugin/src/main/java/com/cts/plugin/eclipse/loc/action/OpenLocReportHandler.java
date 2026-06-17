package com.cts.plugin.eclipse.loc.action;

import com.cts.plugin.eclipse.loc.views.LocReportView;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * OpenLocReportHandler — "GenAI LOC → Show LOC Report".
 *
 * Opens (or brings to the front) the {@link LocReportView}, the Eclipse
 * counterpart of the IntelliJ "GenAI LOC Report" tool window.
 */
public class OpenLocReportHandler extends AbstractHandler {

    private static final ILog LOG = Platform.getLog(OpenLocReportHandler.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            LOG.warn("GenAI-LOC | OpenLocReportHandler: no active workbench window");
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            LOG.warn("GenAI-LOC | OpenLocReportHandler: no active workbench page");
            return null;
        }
        try {
            page.showView(LocReportView.VIEW_ID);
        } catch (PartInitException ex) {
            LOG.error("GenAI-LOC | OpenLocReportHandler: failed to open view: " + ex.getMessage(), ex);
            MessageDialog.openError(window.getShell(), "GenAI LOC Tracker",
                    "Could not open GenAI LOC Report view:\n" + ex.getMessage());
        }
        return null;
    }
}