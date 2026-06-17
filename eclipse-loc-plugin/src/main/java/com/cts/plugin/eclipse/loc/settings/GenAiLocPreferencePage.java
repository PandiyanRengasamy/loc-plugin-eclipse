package com.cts.plugin.eclipse.loc.settings;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import com.cts.plugin.eclipse.loc.EclipseLocPlugin;

/**
 * GenAiLocPreferencePage — Eclipse Preferences page.
 * Accessible via Window → Preferences → GenAI LOC Tracker.
 */
public class GenAiLocPreferencePage extends FieldEditorPreferencePage
        implements IWorkbenchPreferencePage {

    public GenAiLocPreferencePage() {
        super(GRID);
        setDescription("Configure the GenAI LOC Tracker Eclipse Plugin.\n"
                + "Events are sent to the REST backend when you Accept/Keep Copilot suggestions.");
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(EclipseLocPlugin.getInstance().getPreferenceStore());
    }

    @Override
    protected void createFieldEditors() {
        addField(new StringFieldEditor(
                GenAiLocSettings.KEY_BACKEND_URL,
                "Backend URL:",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                GenAiLocSettings.KEY_DEVELOPER_ID,
                "Developer ID:",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                GenAiLocSettings.KEY_DEVELOPER_NAME,
                "Developer Name:",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                GenAiLocSettings.KEY_PROJECT_ID,
                "Project ID:",
                getFieldEditorParent()));

        addField(new StringFieldEditor(
                GenAiLocSettings.KEY_SPRINT_ID,
                "Sprint ID:",
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                GenAiLocSettings.KEY_ENABLED,
                "Enable GenAI LOC Tracker",
                getFieldEditorParent()));

        addField(new IntegerFieldEditor(
                GenAiLocSettings.KEY_FLUSH_INTERVAL,
                "Flush Interval (seconds):",
                getFieldEditorParent()));

        addField(new IntegerFieldEditor(
                GenAiLocSettings.KEY_BATCH_SIZE,
                "Batch Size (events before immediate flush):",
                getFieldEditorParent()));
    }
}

