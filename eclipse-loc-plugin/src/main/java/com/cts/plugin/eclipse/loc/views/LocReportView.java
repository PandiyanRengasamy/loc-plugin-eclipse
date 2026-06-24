package com.cts.plugin.eclipse.loc.views;

import com.cts.plugin.eclipse.loc.EclipseLocPlugin;
import com.cts.plugin.eclipse.loc.settings.GenAiLocSettings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * LocReportView — Eclipse port of the IntelliJ {@code LocReportPanel}.
 *
 * Provides the same dashboard as the IntelliJ "GenAI LOC Report" tool window:
 *
 * <ul>
 *   <li>Filter toolbar: <em>View by</em> (Developer / Project / Developer + Project),
 *       date range, auto-refresh toggle.</li>
 *   <li>Summary cards: Total Events, GenAI / Manual Events, Lines +/~/-, Adoption %,
 *       Human Contribution %, Files Updated / Added / Deleted.</li>
 *   <li>Human LOC % editor for the selected row (PUTs to
 *       {@code /events/{id}/human-loc}).</li>
 *   <li>Events table: latest 50 events with every LOC detail returned by the
 *       backend (Tool, Model, Agent, Location, etc.).</li>
 *   <li>Refresh + Download CSV actions.</li>
 *   <li>30 s auto-refresh timer (off by default).</li>
 * </ul>
 *
 * Data only loads when the user clicks <strong>Refresh</strong> — identical to
 * the IntelliJ behaviour.
 */
public class LocReportView extends ViewPart {

    /** Matches the id used in plugin.xml under {@code org.eclipse.ui.views}. */
    public static final String VIEW_ID = "com.cts.plugin.eclipse.loc.views.LocReportView";

    private static final ILog LOG  = Platform.getLog(LocReportView.class);
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    // Column indices — must match COLUMN_NAMES order below.
    private static final int COL_ID             = 0;
    private static final int COL_TIMESTAMP      = 1;
    private static final int COL_FILE           = 2;
    private static final int COL_TOOL           = 3;
    private static final int COL_LINES_ADDED    = 4;
    private static final int COL_LINES_MODIFIED = 5;
    private static final int COL_LINES_DELETED  = 6;
    private static final int COL_GENAI_FLAG     = 7;
    private static final int COL_MODEL          = 8;
    private static final int COL_AGENT          = 9;
    private static final int COL_LOCATION       = 10;
    private static final int COL_FILES_UPDATED  = 11;
    private static final int COL_FILES_ADDED    = 12;
    private static final int COL_FILES_DELETED  = 13;
    private static final int COL_HUMAN_LOC_PCT  = 14;
    private static final int COL_HUMAN_LOC      = 15;
    private static final int COL_GENAI_LOC      = 16;
    private static final int COL_INPUT_TOKENS   = 17;
    private static final int COL_OUTPUT_TOKENS  = 18;

    private static final String[] COLUMN_NAMES = {
            "ID", "Timestamp", "File", "Tool",
            "Lines +", "Lines ~", "Lines -",
            "GenAI", "Model", "Agent", "Location",
            "Files Updated", "Files Added", "Files Deleted",
            "Human LOC %", "Human LOC", "GenAI LOC",
            "Input Tokens", "Output Tokens"
    };

    // ── Filter controls ───────────────────────────────────────────────────────
    private Combo  cmbViewBy;
    private Combo  cmbDateRange;
    private Button chkAutoRefresh;
    private Display display;
    private Runnable autoRefreshLoop;

    // ── Summary cards ────────────────────────────────────────────────────────
    private Label lblTotalEvents;
    private Label lblGenAiEvents;
    private Label lblManualEvents;
    private Label lblLinesAdded;
    private Label lblLinesModified;
    private Label lblLinesDeleted;
    private Label lblGenAiAdoption;
    private Label lblHumanContribution;
    private Label lblTotalFilesUpdated;
    private Label lblTotalFilesAdded;
    private Label lblTotalFilesDeleted;
    private Label lblTotalInputTokens;
    private Label lblTotalOutputTokens;
    private Label lblStatus;

    // ── Human LOC % controls ─────────────────────────────────────────────────
    private Text   txtHumanLocPercent;
    private Button btnSaveHumanLoc;
    private int    selectedRow = -1;

    /** Local cache of edited Human LOC % values keyed by table row index. */
    private final Map<Integer, Double> humanLocPercentMap = new HashMap<>();

    // ── Events table ─────────────────────────────────────────────────────────
    private Table eventsTable;

    @Override
    public void createPartControl(Composite parent) {
        this.display = parent.getDisplay();

        ScrolledComposite scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);

        Composite root = new Composite(scroll, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        createFilterBar(root);
        createSummaryCards(root);
        createHumanLocBar(root);
        createActionBar(root);
        createEventsTable(root);
        createStatusBar(root);

        scroll.setContent(root);
        scroll.setMinSize(root.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  UI construction
    // ──────────────────────────────────────────────────────────────────────────

    private void createFilterBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(6, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        new Label(bar, SWT.NONE).setText("View by:");
        cmbViewBy = new Combo(bar, SWT.READ_ONLY);
        cmbViewBy.setItems("Developer", "Project", "Developer + Project");
        cmbViewBy.select(0);

        new Label(bar, SWT.NONE).setText("Date range:");
        cmbDateRange = new Combo(bar, SWT.READ_ONLY);
        cmbDateRange.setItems("Last 7 days", "Last 14 days", "Last 30 days", "Last 90 days", "All time");
        cmbDateRange.select(2);

        chkAutoRefresh = new Button(bar, SWT.CHECK);
        chkAutoRefresh.setText("Auto-refresh (30s)");
        chkAutoRefresh.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) { toggleAutoRefresh(); }
        });
        // Spacer to balance the row
        new Label(bar, SWT.NONE).setText("");
    }

    private void createSummaryCards(Composite parent) {
        Composite cards = new Composite(parent, SWT.NONE);
        cards.setLayout(new GridLayout(5, true));
        GridData gd = new GridData(SWT.FILL, SWT.BEGINNING, true, false);
        cards.setLayoutData(gd);

        lblTotalEvents       = createCard(cards, "Total Events");
        lblGenAiEvents       = createCard(cards, "GenAI Events");
        lblManualEvents      = createCard(cards, "Manual Events");
        lblLinesAdded        = createCard(cards, "Lines Added");
        lblLinesModified     = createCard(cards, "Lines Modified");

        lblLinesDeleted      = createCard(cards, "Lines Deleted");
        lblGenAiAdoption     = createCard(cards, "GenAI Adoption");
        lblHumanContribution = createCard(cards, "Human Contribution %");
        lblTotalFilesUpdated = createCard(cards, "Files Updated");
        lblTotalFilesAdded   = createCard(cards, "Files Added");

        lblTotalFilesDeleted = createCard(cards, "Files Deleted");
        lblTotalInputTokens  = createCard(cards, "Input Tokens");
        lblTotalOutputTokens = createCard(cards, "Output Tokens");
        // Fill remaining slots so the grid stays aligned at 5 columns.
        for (int i = 0; i < 2; i++) {
            new Label(cards, SWT.NONE);
        }
    }

    private Label createCard(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayout(new GridLayout(1, false));
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        group.setLayoutData(gd);

        Label value = new Label(group, SWT.NONE);
        value.setText("—");                    // em-dash placeholder
        GridData vd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        value.setLayoutData(vd);
        return value;
    }

    private void createHumanLocBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(3, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        new Label(bar, SWT.NONE).setText("Human LOC % for selected row:");
        txtHumanLocPercent = new Text(bar, SWT.BORDER);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, false, false);
        gd.widthHint = 60;
        txtHumanLocPercent.setLayoutData(gd);
        txtHumanLocPercent.setToolTipText("Enter human-written LOC percent (0-100)");
        txtHumanLocPercent.setEnabled(false);

        btnSaveHumanLoc = new Button(bar, SWT.PUSH);
        btnSaveHumanLoc.setText("Save");
        btnSaveHumanLoc.setEnabled(false);
        btnSaveHumanLoc.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) { saveSelectedHumanLocPercent(); }
        });
    }

    private void createActionBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(2, false));
        GridData gd = new GridData(SWT.END, SWT.BEGINNING, true, false);
        bar.setLayoutData(gd);

        Button btnRefresh = new Button(bar, SWT.PUSH);
        btnRefresh.setText("↻ Refresh");
        btnRefresh.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) { refreshData(); }
        });

        Button btnDownload = new Button(bar, SWT.PUSH);
        btnDownload.setText("Download CSV");
        btnDownload.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) { downloadTableAsCsv(); }
        });
    }

    private void createEventsTable(Composite parent) {
        eventsTable = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        eventsTable.setHeaderVisible(true);
        eventsTable.setLinesVisible(true);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 280;
        eventsTable.setLayoutData(gd);

        int[] widths = { 0,       // ID hidden
                         140, 220, 90,
                         60, 60, 60,
                         60, 110, 110, 130,
                         100, 100, 100,
                         90, 90, 90,
                         100, 100 };
        for (int i = 0; i < COLUMN_NAMES.length; i++) {
            TableColumn col = new TableColumn(eventsTable, SWT.NONE);
            col.setText(COLUMN_NAMES[i]);
            col.setWidth(widths[i]);
            col.setResizable(true);
            col.setMoveable(false);
        }

        eventsTable.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int previous = selectedRow;
                if (previous >= 0) {
                    persistRowEditToMap(previous);
                }
                selectedRow = eventsTable.getSelectionIndex();
                if (selectedRow >= 0) {
                    txtHumanLocPercent.setEnabled(true);
                    btnSaveHumanLoc.setEnabled(true);
                    Double v = humanLocPercentMap.get(selectedRow);
                    txtHumanLocPercent.setText(v != null ? String.valueOf(v) : "");
                } else {
                    txtHumanLocPercent.setEnabled(false);
                    btnSaveHumanLoc.setEnabled(false);
                    txtHumanLocPercent.setText("");
                }
            }
        });
    }

    private void createStatusBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(1, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.BEGINNING, true, false));

        lblStatus = new Label(bar, SWT.NONE);
        lblStatus.setText("Click Refresh to load data");
        lblStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    @Override
    public void setFocus() {
        if (eventsTable != null && !eventsTable.isDisposed()) {
            eventsTable.setFocus();
        }
    }

    @Override
    public void dispose() {
        autoRefreshLoop = null;
        super.dispose();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Auto-refresh
    // ──────────────────────────────────────────────────────────────────────────

    private void toggleAutoRefresh() {
		LOG.info("GenAI-LOC | Toggling auto-refresh: now " + (chkAutoRefresh.getSelection() ? "ON" : "OFF"));
        if (chkAutoRefresh.getSelection()) {
            LOG.info("GenAI-LOC | Report auto-refresh ON (30s)");
            autoRefreshLoop = new Runnable() {
                @Override
                public void run() {
                    if (display.isDisposed() || autoRefreshLoop != this
                            || !chkAutoRefresh.getSelection() || chkAutoRefresh.isDisposed()) {
                        return;
                    }
                    refreshData();
                    display.timerExec(30_000, this);
                }
            };
            display.timerExec(30_000, autoRefreshLoop);
        } else {
            LOG.info("GenAI-LOC | Report auto-refresh OFF");
            autoRefreshLoop = null;   // pending Runnable will see the change and stop
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Data loading
    // ──────────────────────────────────────────────────────────────────────────

    private int dateRangeDays() {
        String sel = cmbDateRange.getText();
        return switch (sel) {
            case "Last 7 days"  -> 7;
            case "Last 14 days" -> 14;
            case "Last 90 days" -> 90;
            case "All time"     -> 3650;
            default             -> 30;
        };
    }

    private void refreshData() {
	    LOG.info("GenAI-LOC | Report: Refresh button clicked");
	    if (eventsTable.isDisposed()) {
	        LOG.warn("GenAI-LOC | Report: refreshData called but eventsTable is already disposed");
	        return;
	    }
	    LOG.info("GenAI-LOC | Report: refresh triggered");
	    lblStatus.setText("Loading...");
	
	    // 1. Fetch UI values on the Main UI Thread BEFORE starting the background thread
	    int days = dateRangeDays();
	    String viewBy = cmbViewBy.getText();
	    
	    Thread worker = new Thread(() -> {
	        try {
	            GenAiLocSettings s = GenAiLocSettings.getInstance();
	            String baseUrl = s.getBackendUrl().replace("/events", "");
	            String devId   = s.getDeveloperId();
	            String projId  = s.getProjectId().isBlank() ? "EclipseProject" : s.getProjectId();
	            
	            // 2. These local variables (days, viewBy) are now safely used here
	            String to      = LocalDate.now().format(DateTimeFormatter.ISO_DATE) + "T23:59:59";
	            String from    = LocalDate.now().minusDays(days).format(DateTimeFormatter.ISO_DATE) + "T00:00:00";
	            String url     = buildEventsUrl(baseUrl, viewBy, devId, projId, from, to);
	
	            LOG.info("GenAI-LOC | Report: GET " + url);
	            String body = httpGet(url);
	            LOG.info("GenAI-LOC | Report: Received response (" + body.length() + " chars)");
	            JsonArray events = GSON.fromJson(body, JsonArray.class);
	            
	            runOnUi(() -> populateTable(events));
	
	            runOnUi(() -> {
	                // Safeguard against widget disposal while network call was running
	                if (!lblStatus.isDisposed()) {
	                    lblStatus.setText(
	                        "Last refreshed: "
	                                + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
	                                + "  |  " + buildFilterInfo(viewBy, devId, projId, days));
	                }
	            });
	        } catch (Exception ex) {
	            LOG.error("GenAI-LOC | Report refresh failed: " + ex.getMessage(), ex);
	            runOnUi(() -> {
	                if (!eventsTable.isDisposed()) {
	                    eventsTable.removeAll();
	                }
	                resetSummaryToNA();
	                if (!lblStatus.isDisposed()) {
	                    lblStatus.setText("⚠ Error: " + ex.getMessage());
	                }
	            });
	        }
	    }, "GenAI-LOC-ReportFetcher");
	    
	    worker.setDaemon(true);
	    worker.start();
	}


    private static String buildEventsUrl(String baseUrl, String viewBy, String devId, String projId,
                                         String from, String to) {
        return switch (viewBy) {
            case "Project" ->
                    baseUrl + "/events/project/" + enc(projId)
                            + "?from=" + from + "&to=" + to + "&sort=eventTimestamp,desc";
            case "Developer + Project" ->
                    baseUrl + "/events/developer/" + enc(devId) + "/project/" + enc(projId)
                            + "?from=" + from + "&to=" + to + "&sort=eventTimestamp,desc";
            default ->
                    baseUrl + "/events/developer/" + enc(devId) + "/all"
                            + "?from=" + from + "&to=" + to;
        };
    }

    private static String enc(String s) {
        return s == null ? "" : java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String buildFilterInfo(String viewBy, String devId, String projId, int days) {
        String range = days >= 3650 ? "All time" : "Last " + days + " days";
        return switch (viewBy) {
            case "Project"             -> "Project: " + projId + "  |  " + range;
            case "Developer + Project" -> "Developer: " + devId + "  |  Project: " + projId + "  |  " + range;
            default                    -> "Developer: " + devId + "  |  " + range;
        };
    }

    private void populateTable(JsonArray events) {
        eventsTable.removeAll();
        humanLocPercentMap.clear();
        for (int i = 0; i < events.size(); i++) {
            JsonObject obj = events.get(i).getAsJsonObject();
            int linesAdded    = intOf(obj, "linesAdded");
            int linesModified = intOf(obj, "linesModified");
            int linesDeleted  = intOf(obj, "linesDeleted");
            int totalLoc      = linesAdded + linesModified + linesDeleted;
            Double humanPct   = doubleOrNull(obj, "humanLocPercent");
            if (humanPct == null) {
                humanPct = 0.0;
            }
            double humanLoc   = totalLoc * (humanPct / 100.0);
            double genAiLoc   = totalLoc - humanLoc;
            humanLocPercentMap.put(i, humanPct);

            TableItem item = new TableItem(eventsTable, SWT.NONE);
            item.setText(COL_ID,             strOf(obj, "id"));
            item.setText(COL_TIMESTAMP,      strOf(obj, "eventTimestamp"));
            item.setText(COL_FILE,           strOf(obj, "fileName"));
            item.setText(COL_TOOL,           strOf(obj, "genAiTool"));
            item.setText(COL_LINES_ADDED,    String.valueOf(linesAdded));
            item.setText(COL_LINES_MODIFIED, String.valueOf(linesModified));
            item.setText(COL_LINES_DELETED,  String.valueOf(linesDeleted));
            item.setText(COL_GENAI_FLAG,
                    obj.has("genAiGenerated") && obj.get("genAiGenerated").getAsBoolean() ? "Yes" : "No");
            item.setText(COL_MODEL,          strOf(obj, "llmModel"));
            item.setText(COL_AGENT,          strOf(obj, "agentName"));
            item.setText(COL_LOCATION,       strOf(obj, "acceptedLocation"));
            item.setText(COL_FILES_UPDATED,  String.valueOf(intOf(obj, "totalFilesUpdated")));
            item.setText(COL_FILES_ADDED,    String.valueOf(intOf(obj, "totalFilesAdded")));
            item.setText(COL_FILES_DELETED,  String.valueOf(intOf(obj, "totalFilesDeleted")));
            item.setText(COL_HUMAN_LOC_PCT,  String.valueOf(humanPct));
            item.setText(COL_HUMAN_LOC,      String.format("%.2f", humanLoc));
            item.setText(COL_GENAI_LOC,      String.format("%.2f", genAiLoc));
            item.setText(COL_INPUT_TOKENS,   String.valueOf(intOf(obj, "inputTokens")));
            item.setText(COL_OUTPUT_TOKENS,  String.valueOf(intOf(obj, "outputTokens")));
        }
        updateSummaryFromTable();
    }

    private void resetSummaryToNA() {
        lblTotalEvents.setText("N/A");
        lblGenAiEvents.setText("N/A");
        lblManualEvents.setText("N/A");
        lblLinesAdded.setText("N/A");
        lblLinesModified.setText("N/A");
        lblLinesDeleted.setText("N/A");
        lblGenAiAdoption.setText("N/A");
        lblHumanContribution.setText("N/A");
        lblTotalFilesUpdated.setText("N/A");
        lblTotalFilesAdded.setText("N/A");
        lblTotalFilesDeleted.setText("N/A");
        lblTotalInputTokens.setText("N/A");
        lblTotalOutputTokens.setText("N/A");
    }

    /** Mirrors {@code LocReportPanel#updateSummaryFromTable} in the IntelliJ plugin. */
    private void updateSummaryFromTable() {
        int rowCount = eventsTable.getItemCount();
        int genAi = 0, manual = 0;
        int linesAdded = 0, linesModified = 0, linesDeleted = 0;
        int filesUpdated = 0, filesAdded = 0, filesDeleted = 0;
        int inputTokens = 0, outputTokens = 0;
        double humanLoc = 0.0, genAiLoc = 0.0;

        for (int i = 0; i < rowCount; i++) {
            TableItem r = eventsTable.getItem(i);
            if ("Yes".equals(r.getText(COL_GENAI_FLAG))) {
                genAi++;
            } else {
                manual++;
            }
            linesAdded    += toInt(r.getText(COL_LINES_ADDED));
            linesModified += toInt(r.getText(COL_LINES_MODIFIED));
            linesDeleted  += toInt(r.getText(COL_LINES_DELETED));
            filesUpdated  += toInt(r.getText(COL_FILES_UPDATED));
            filesAdded    += toInt(r.getText(COL_FILES_ADDED));
            filesDeleted  += toInt(r.getText(COL_FILES_DELETED));
            inputTokens   += toInt(r.getText(COL_INPUT_TOKENS));
            outputTokens  += toInt(r.getText(COL_OUTPUT_TOKENS));
            humanLoc      += Math.max(0, toDouble(r.getText(COL_HUMAN_LOC)));
            genAiLoc      += Math.max(0, toDouble(r.getText(COL_GENAI_LOC)));
        }

        double total = humanLoc + genAiLoc;
        double adoption = total > 0 ? clampPct(genAiLoc / total * 100.0) : 0.0;
        double human    = total > 0 ? clampPct(humanLoc  / total * 100.0) : 0.0;

        lblTotalEvents.setText(String.valueOf(rowCount));
        lblGenAiEvents.setText(String.valueOf(genAi));
        lblManualEvents.setText(String.valueOf(manual));
        lblLinesAdded.setText(String.valueOf(linesAdded));
        lblLinesModified.setText(String.valueOf(linesModified));
        lblLinesDeleted.setText(String.valueOf(linesDeleted));
        lblGenAiAdoption.setText(String.format("%.1f%%", adoption));
        lblHumanContribution.setText(String.format("%.1f%%", human));
        lblTotalFilesUpdated.setText(String.valueOf(filesUpdated));
        lblTotalFilesAdded.setText(String.valueOf(filesAdded));
        lblTotalFilesDeleted.setText(String.valueOf(filesDeleted));
        lblTotalInputTokens.setText(String.valueOf(inputTokens));
        lblTotalOutputTokens.setText(String.valueOf(outputTokens));
    }

    private static double clampPct(double v) {
        if (v < 0) return 0;
        if (v > 100) return 100;
        return v;
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Human LOC %
    // ──────────────────────────────────────────────────────────────────────────

    private void persistRowEditToMap(int row) {
        String raw = txtHumanLocPercent.getText().trim();
        if (row < 0 || raw.isEmpty()) {
            return;
        }
        try {
            double percent = Double.parseDouble(raw);
            if (percent >= 0 && percent <= 100) {
                humanLocPercentMap.put(row, percent);
                if (row < eventsTable.getItemCount()) {
                    eventsTable.getItem(row).setText(COL_HUMAN_LOC_PCT, String.valueOf(percent));
                }
            }
        } catch (NumberFormatException ignored) { /* validation happens on Save */ }
    }

    private void saveSelectedHumanLocPercent() {
        if (selectedRow < 0) {
            MessageDialog.openInformation(getSite().getShell(), "Nothing to Save",
                    "Select a row in the table first.");
            return;
        }
        String raw = txtHumanLocPercent.getText().trim();
        if (raw.isEmpty()) {
            MessageDialog.openWarning(getSite().getShell(), "Missing Value",
                    "Please enter a Human LOC % value before saving.");
            return;
        }
        double percent;
        try {
            percent = Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            MessageDialog.openError(getSite().getShell(), "Invalid Input",
                    "Please enter a valid number.");
            return;
        }
        if (percent < 0 || percent > 100) {
            MessageDialog.openError(getSite().getShell(), "Invalid Input",
                    "Please enter a value between 0 and 100.");
            return;
        }

        String eventId = eventsTable.getItem(selectedRow).getText(COL_ID);
        LOG.info("GenAI-LOC | Report: PUT human-loc eventId=" + eventId + " pct=" + percent);

        boolean ok = sendHumanLocToBackend(eventId, percent);
        if (ok) {
            humanLocPercentMap.put(selectedRow, percent);
            eventsTable.getItem(selectedRow).setText(COL_HUMAN_LOC_PCT, String.valueOf(percent));
            lblStatus.setText("Saved Human LOC % for event " + eventId);
            refreshData();          // re-sync with the backend so derived columns update
        } else {
            lblStatus.setText("Failed to save Human LOC % for event " + eventId);
        }
        txtHumanLocPercent.setText("");
        eventsTable.deselectAll();
        selectedRow = -1;
        txtHumanLocPercent.setEnabled(false);
        btnSaveHumanLoc.setEnabled(false);
    }

    private boolean sendHumanLocToBackend(String eventId, double percent) {
        try {
            String baseUrl = GenAiLocSettings.getInstance().getBackendUrl().replace("/events", "");
            String url = baseUrl + "/events/" + enc(eventId) + "/human-loc";
            JsonObject body = new JsonObject();
            body.addProperty("humanLocPercent", percent);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            LOG.warn("GenAI-LOC | Report: human-loc PUT failed HTTP " + code + ": " + resp.body());
            return false;
        } catch (Exception ex) {
            LOG.error("GenAI-LOC | Report: human-loc PUT error " + ex.getMessage(), ex);
            return false;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  CSV download
    // ──────────────────────────────────────────────────────────────────────────

    private void downloadTableAsCsv() {
        FileDialog dlg = new FileDialog(getSite().getShell(), SWT.SAVE);
        dlg.setText("Save Table Data as CSV");
        dlg.setFilterExtensions(new String[] { "*.csv" });
        dlg.setFileName("genai-loc-report.csv");
        String chosen = dlg.open();
        if (chosen == null) {
            return;
        }
        try (FileWriter fw = new FileWriter(chosen)) {
            for (int i = 0; i < COLUMN_NAMES.length; i++) {
                fw.write(csvEscape(COLUMN_NAMES[i]));
                if (i < COLUMN_NAMES.length - 1) fw.write(",");
            }
            fw.write("\n");
            for (int row = 0; row < eventsTable.getItemCount(); row++) {
                TableItem item = eventsTable.getItem(row);
                for (int col = 0; col < COLUMN_NAMES.length; col++) {
                    fw.write(csvEscape(item.getText(col)));
                    if (col < COLUMN_NAMES.length - 1) fw.write(",");
                }
                fw.write("\n");
            }
            fw.flush();
            MessageDialog.openInformation(getSite().getShell(), "Export Complete",
                    "Table data exported to:\n" + chosen);
        } catch (Exception ex) {
            MessageDialog.openError(getSite().getShell(), "Export Error",
                    "Failed to export table data: " + ex.getMessage());
        }
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  HTTP & misc helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private static String strOf(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return "—";
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static int intOf(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try { return obj.get(key).getAsInt(); } catch (Exception e) { return 0; }
    }

    private static Double doubleOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try { return obj.get(key).getAsDouble(); } catch (Exception e) { return null; }
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    private static double toDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }

    private void runOnUi(Runnable r) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            if (!eventsTable.isDisposed()) {
                r.run();
            }
        });
    }

    /** Used by tests to keep the dependency on EclipseLocPlugin visible. */
    @SuppressWarnings("unused")
    private static EclipseLocPlugin pluginInstance() {
        return EclipseLocPlugin.getInstance();
    }
}