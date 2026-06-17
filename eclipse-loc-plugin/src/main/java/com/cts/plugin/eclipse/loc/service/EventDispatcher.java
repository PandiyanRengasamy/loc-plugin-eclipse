package com.cts.plugin.eclipse.loc.service;

import com.cts.plugin.eclipse.loc.model.FileChange;
import com.cts.plugin.eclipse.loc.model.LOCRequestPayload;
import com.cts.plugin.eclipse.loc.settings.GenAiLocSettings;
import com.google.gson.Gson;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * EventDispatcher — thread-safe event queue.
 *
 * Identical logic to the IntelliJ plugin's EventDispatcher, ported to use
 * Eclipse logging instead of IntelliJ's Logger. All HTTP/queue/retry logic
 * is preserved 1-to-1 so the same backend service works for both IDEs.
 */
public class EventDispatcher {

    private static final ILog  LOG  = Platform.getLog(EventDispatcher.class);
    private static final Gson  GSON = new Gson();

    private final List<LOCRequestPayload>   queue     = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService  scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GenAI-LOC-Dispatcher");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient   httpClient;
    private final AtomicInteger sentTotal = new AtomicInteger(0);
    private final AtomicInteger failTotal = new AtomicInteger(0);
    private final String        sessionId = UUID.randomUUID().toString();

    private volatile boolean serviceDown = false;

    public EventDispatcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        GenAiLocSettings s = GenAiLocSettings.getInstance();
        scheduler.scheduleAtFixedRate(
                this::flush,
                s.getFlushIntervalSeconds(),
                s.getFlushIntervalSeconds(),
                TimeUnit.SECONDS);

        LOG.info("GenAI-LOC | EventDispatcher initialised  flushInterval=" + s.getFlushIntervalSeconds()
                + "s  batchSize=" + s.getBatchSize()
                + "  backendUrl=" + s.getBackendUrl()
                + "  session=" + sessionId);
    }

    public String getSessionId() { return sessionId; }

    // ── Enqueue ───────────────────────────────────────────────────────────────

    public void enqueue(LOCRequestPayload event) {
        GenAiLocSettings s = GenAiLocSettings.getInstance();
        if (!s.isEnabled()) {
            LOG.info("GenAI-LOC | EventDispatcher.enqueue — plugin disabled, event dropped for file="
                    + event.getFileName());
            return;
        }
        queue.add(event);
        LOG.info("GenAI-LOC | EventDispatcher.enqueue — file=" + event.getFileName()
                + " tool=" + event.getGenAiTool()
                + " +=" + event.getLinesAdded()
                + " ~=" + event.getLinesModified()
                + " -=" + event.getLinesDeleted()
                + " queueSize=" + queue.size());

        if (queue.size() >= s.getBatchSize()) {
            LOG.info("GenAI-LOC | Batch threshold reached, triggering immediate flush");
            scheduler.execute(this::flush);
        }
    }

    public void flushNow() {
        scheduler.execute(this::flush);
    }

    // ── Flush ─────────────────────────────────────────────────────────────────

    public synchronized void flush() {
        if (queue.isEmpty()) return;

        GenAiLocSettings gas = GenAiLocSettings.getInstance();
        List<LOCRequestPayload> batch = new ArrayList<>(queue);
        queue.clear();

        String url = gas.getBackendUrl();
        LOG.info("GenAI-LOC | EventDispatcher.flush — START: flushing " + batch.size() + " event(s) to " + url);

        int okCount = 0, failCount = 0;
        List<LOCRequestPayload> failed = new ArrayList<>();

        for (LOCRequestPayload e : batch) {
            String json = buildPayload(e);
            try {
                int status = post(url, json);
                if (status >= 200 && status < 300) {
                    okCount++;
                    sentTotal.incrementAndGet();
                    LOG.info("GenAI-LOC | POST OK: file=" + e.getFileName()
                            + " http=" + status + " totalSent=" + sentTotal.get());
                } else {
                    failCount++;
                    failed.add(e);
                    LOG.warn("GenAI-LOC | POST FAILED: file=" + e.getFileName() + " http=" + status);
                }
            } catch (Exception ex) {
                failCount++;
                failed.add(e);
                LOG.warn("GenAI-LOC | POST ERROR: file=" + e.getFileName() + " err=" + ex.getMessage());
            }
        }

        LOG.info("GenAI-LOC | EventDispatcher.flush — END: ok=" + okCount + " failed=" + failCount
                + " totalSent=" + sentTotal.get());

        if (failCount > 0) {
            serviceDown = true;
            for (LOCRequestPayload fe : failed) {
                retryAfterDelay(url, buildPayload(fe), Collections.singletonList(fe));
            }
        } else if (serviceDown) {
            serviceDown = false;
        }
    }

    // ── Dispose ───────────────────────────────────────────────────────────────

    public void dispose() {
        LOG.info("GenAI-LOC | EventDispatcher.dispose — flushing remaining queue (size=" + queue.size() + ")");
        scheduler.shutdown();
        flush();
        LOG.info("GenAI-LOC | EventDispatcher.dispose — DONE. sent=" + sentTotal.get()
                + " failed=" + failTotal.get());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds the standard LOC JSON payload — identical contract to the IntelliJ plugin
     * so the same Spring Boot LocEventController accepts it.
     */
    private String buildPayload(LOCRequestPayload e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("developerId",          e.getDeveloperId());
        payload.put("developerName",        e.getDeveloperName());
        payload.put("projectId",            e.getProjectId());
        payload.put("sprintId",             e.getSprintId());
        payload.put("filePath",             e.getFilePath());
        payload.put("fileName",             e.getFileName());
        payload.put("className",            e.getClassName());
        payload.put("ideType",              e.getIdeType()          != null ? e.getIdeType()          : "ECLIPSE");
        payload.put("genAiTool",            e.getGenAiTool()        != null ? e.getGenAiTool()        : "COPILOT");
        payload.put("developmentMode",      e.getDevelopmentMode()  != null ? e.getDevelopmentMode()  : "BROWNFIELD");
        payload.put("linesAdded",           e.getLinesAdded());
        payload.put("linesModified",        e.getLinesModified());
        payload.put("linesDeleted",         e.getLinesDeleted());
        payload.put("genAiGenerated",       e.isGenAiGenerated());
        payload.put("genAiConfidenceScore", e.getGenAiConfidenceScore());
        payload.put("eventTimestamp",       e.getEventTimestamp());
        payload.put("sessionId",            e.getSessionId());
        payload.put("fileChanges",          e.getFileChanges());
        String json = GSON.toJson(payload);
        LOG.info("GenAI-LOC | buildPayload: file=" + e.getFileName()
                + " fileChanges=" + e.getFileChanges().size()
                + " +=" + e.getLinesAdded() + " ~=" + e.getLinesModified()
                + " -=" + e.getLinesDeleted());
        return json;
    }

    private int post(String url, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        LOG.info("GenAI-LOC | POST " + url + " → " + resp.statusCode()
                + "  body=" + (resp.body() != null ? resp.body().substring(0, Math.min(200, resp.body().length())) : "(empty)"));
        return resp.statusCode();
    }

    private void retryAfterDelay(String url, String json, List<LOCRequestPayload> batch) {
        scheduler.schedule(() -> {
            LOG.info("GenAI-LOC | retryAfterDelay — retrying " + batch.size() + " event(s)");
            try {
                int status = post(url, json);
                if (status >= 200 && status < 300) {
                    sentTotal.addAndGet(batch.size());
                    LOG.info("GenAI-LOC | Retry OK: " + batch.size() + " events delivered");
                } else {
                    failTotal.addAndGet(batch.size());
                    LOG.warn("GenAI-LOC | Retry FAILED http=" + status);
                }
            } catch (Exception ex) {
                failTotal.addAndGet(batch.size());
                LOG.warn("GenAI-LOC | Retry ERROR: " + ex.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
    }
}

