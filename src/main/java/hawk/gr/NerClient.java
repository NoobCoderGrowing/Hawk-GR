package hawk.gr;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java client for the Python NER (Named Entity Recognition) service.
 * <p>
 * Manages a Python subprocess that loads the ModelScope
 * raner_named-entity-recognition_chinese-base-ecom-50cls model once
 * and processes text lines via stdin/stdout JSON protocol.
 *
 * <h3>Usage</h3>
 * <pre>
 *   try (NerClient ner = new NerClient()) {
 *       List{@code <NerEntity>} entities = ner.extract("小米黑色手机壳");
 *       // → [{type: "品牌", span: "小米", prob: 0.97}, ...]
 *   }
 * </pre>
 */
public class NerClient implements AutoCloseable {

    private static final String PYTHON_BIN = "python3";
    private static final String SERVICE_SCRIPT = "src/main/python/ner_service.py";

    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;
    private final Gson gson;
    private final AtomicInteger idCounter;

    public NerClient() throws IOException {
        this(SERVICE_SCRIPT);
    }

    public NerClient(String scriptPath) throws IOException {
        this.gson = new Gson();
        this.idCounter = new AtomicInteger(0);

        // Locate script — try project root, then module root
        File script = new File(scriptPath);
        if (!script.exists()) {
            // Try relative to user.dir (project root)
            String cwd = System.getProperty("user.dir");
            script = new File(cwd, scriptPath);
        }
        if (!script.exists()) {
            throw new FileNotFoundException(
                "NER service script not found: " + scriptPath +
                " (cwd=" + System.getProperty("user.dir") + ")");
        }

        ProcessBuilder pb = new ProcessBuilder(
            PYTHON_BIN, script.getAbsolutePath()
        );
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);  // stderr to console

        this.process = pb.start();
        this.stdout = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.stdin = new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        // Wait for ready signal by reading stderr via redirect above
        // Give the model a moment to load
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Warm-up: send a dummy request to ensure model is fully loaded
        try {
            extract("test");
        } catch (Exception e) {
            // ignore warm-up errors (model still loading)
        }
    }

    /**
     * Extract named entities from raw text.
     *
     * @param text raw input text like "小米黑色手机壳"
     * @return list of extracted entities with type, span, and probability
     */
    public List<NerEntity> extract(String text) throws IOException {
        int reqId = idCounter.incrementAndGet();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", String.valueOf(reqId));
        request.put("text", text);

        // Send request
        String reqJson = gson.toJson(request);
        stdin.write(reqJson);
        stdin.newLine();
        stdin.flush();

        // Read response
        String respJson = stdout.readLine();
        if (respJson == null) {
            throw new IOException("NER service closed unexpectedly");
        }

        Type respType = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> response = gson.fromJson(respJson, respType);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawOutput =
            (List<Map<String, Object>>) response.get("output");

        if (rawOutput == null) {
            return Collections.emptyList();
        }

        List<NerEntity> entities = new ArrayList<>();
        for (Map<String, Object> raw : rawOutput) {
            entities.add(new NerEntity(
                (String) raw.get("type"),
                (String) raw.get("span"),
                ((Number) raw.get("prob")).doubleValue()
            ));
        }
        return entities;
    }

    @Override
    public void close() {
        try {
            // Send close command
            Map<String, Object> closeReq = new LinkedHashMap<>();
            closeReq.put("id", "close");
            closeReq.put("action", "close");
            stdin.write(gson.toJson(closeReq));
            stdin.newLine();
            stdin.flush();
        } catch (IOException e) {
            // ignore
        }
        try { stdin.close(); } catch (IOException e) { /* ignore */ }
        try { stdout.close(); } catch (IOException e) { /* ignore */ }
        process.destroy();
    }

    // ---- entity class ----

    public static class NerEntity {
        private final String type;
        private final String span;
        private final double prob;

        public NerEntity(String type, String span, double prob) {
            this.type = type;
            this.span = span;
            this.prob = prob;
        }

        public String type()  { return type; }
        public String span()  { return span; }
        public double prob()  { return prob; }

        @Override
        public String toString() {
            return type + ":" + span + "(" + String.format("%.2f", prob) + ")";
        }
    }
}
