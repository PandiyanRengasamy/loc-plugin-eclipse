package com.cts.plugin.eclipse.loc;

import com.cts.plugin.eclipse.loc.listeners.CopilotKeepAllMouseListener;
import com.cts.plugin.eclipse.loc.service.EventDispatcher;
import com.cts.plugin.eclipse.loc.settings.GenAiLocSettings;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * EclipseLocPlugin — OSGi bundle activator.
 *
 * Lifecycle:
 *   start() → initialise settings, EventDispatcher, register mouse listener
 *   stop()  → dispose EventDispatcher (flush remaining queue)
 */
public class EclipseLocPlugin extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.cts.plugin.eclipse.loc";

    private static EclipseLocPlugin instance;
    private static final ILog LOG = Platform.getLog(EclipseLocPlugin.class);

    private EventDispatcher eventDispatcher;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        LOG.info("GenAI-LOC | EclipseLocPlugin starting...");

        GenAiLocSettings.init(getPreferenceStore());
        eventDispatcher = new EventDispatcher();
        CopilotKeepAllMouseListener.register(eventDispatcher);

        LOG.info("GenAI-LOC | EclipseLocPlugin started ✅  backendUrl="
                + GenAiLocSettings.getInstance().getBackendUrl());
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        LOG.info("GenAI-LOC | EclipseLocPlugin stopping...");
        if (eventDispatcher != null) {
            eventDispatcher.dispose();
        }
        instance = null;
        super.stop(context);
    }

    public static EclipseLocPlugin getInstance() { return instance; }
    public EventDispatcher getEventDispatcher()   { return eventDispatcher; }
}

