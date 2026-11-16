package edu.mcw.scge.platform.embedding;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns trial text into dense vectors by calling the on-prem Ollama server.
 *
 * Vectors are generated outside Elasticsearch on purpose: generating them inside the
 * cluster (ELSER, semantic_text, the inference API) needs a Platinum/Enterprise
 * subscription, while storing and searching a dense_vector field is free on Basic. So the
 * embedding step lives here, in the pipeline, and Elasticsearch only ever sees a finished
 * array of floats. Nothing leaves the MCW network.
 *
 * Everything is configured through system properties so the run scripts can override it
 * through PLATFORM_INDEXER_OPTS, the same way the DB and log4j config is passed:
 *
 *   -Dscge.embedding.enabled=true
 *   -Dscge.embedding.url=http://grudge.rgd.mcw.edu:11434
 *   -Dscge.embedding.model=bge-m3
 *   -Dscge.embedding.dims=1024
 *
 * The default model is bge-m3 rather than the mxbai-embed-large the chat/RAG pipeline
 * uses. Both emit 1024 dimensions, so the index mapping is identical and the two are
 * interchangeable without a mapping change, but bge-m3 accepts 8192 tokens against
 * mxbai's 512. Trial descriptions plus intervention text regularly run past 512 tokens
 * and mxbai truncates silently, so the tail of such a record would simply not be
 * represented in its vector. bge-m3 also needs no "represent this sentence for searching"
 * query prefix, which removes one way for indexing and querying to disagree.
 */
public class EmbeddingService {

    protected static final Logger logger = LogManager.getLogger();

    private final boolean enabled;
    private final String baseUrl;
    private final String model;
    private final int dims;
    private final int maxRetries;
    private final Duration timeout;
    private final HttpClient http;

    private int successCount = 0;
    private int failureCount = 0;

    public EmbeddingService() {
        this.enabled = Boolean.parseBoolean(System.getProperty("scge.embedding.enabled", "true"));
        this.baseUrl = trimTrailingSlash(System.getProperty("scge.embedding.url", "http://grudge.rgd.mcw.edu:11434"));
        this.model = System.getProperty("scge.embedding.model", "bge-m3");
        this.dims = Integer.parseInt(System.getProperty("scge.embedding.dims", "1024"));
        this.maxRetries = Integer.parseInt(System.getProperty("scge.embedding.maxRetries", "2"));
        this.timeout = Duration.ofSeconds(Integer.parseInt(System.getProperty("scge.embedding.timeoutSeconds", "60")));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        if (enabled) {
            logger.info("EMBEDDINGS ON - model=" + model + " dims=" + dims + " url=" + baseUrl);
        } else {
            logger.info("EMBEDDINGS OFF - documents will be indexed without a vector");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    /**
     * The vector for one piece of text, or null when embedding is switched off, the text
     * is empty, or the call failed after its retries.
     *
     * A null is deliberately not fatal. A trial indexed without a vector is still found by
     * the keyword query; it is only invisible to the kNN clause. Failing an entire reindex
     * because one embedding call timed out would be the worse trade. A silent gap would be
     * worse still, so every failure is counted and logSummary() reports the total.
     */
    public List<Float> embed(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return null;
        }
        List<List<Float>> vectors = embedBatch(List.of(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    /**
     * Vectors for several texts in one call, in the order given; entries that could not be
     * embedded come back null. The main indexing loop embeds one record at a time because
     * it indexes one record at a time - this form exists for bulk re-embedding.
     */
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> out = new ArrayList<>();
        if (!enabled || texts == null || texts.isEmpty()) {
            return out;
        }

        Exception lastError = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                List<List<Float>> vectors = callOllama(texts);
                successCount += vectors.size();
                return vectors;
            } catch (IllegalStateException e) {
                // A wrong vector width is a configuration error, not a transient one, and
                // retrying cannot fix it. Stop the run rather than write an index whose
                // documents fail one by one.
                if (e.getMessage() != null && e.getMessage().contains("dimensions")) {
                    throw e;
                }
                lastError = e;
                if (attempt < maxRetries) {
                    sleep(500L * (attempt + 1));
                }
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxRetries) {
                    sleep(500L * (attempt + 1));
                }
            }
        }

        failureCount += texts.size();
        logger.error("EMBEDDING FAILED for " + texts.size() + " text(s) after "
                + (maxRetries + 1) + " attempt(s): " + lastError);
        for (int i = 0; i < texts.size(); i++) {
            out.add(null);
        }
        return out;
    }

    private List<List<Float>> callOllama(List<String> texts) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("input", new JSONArray(texts));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/embed"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new RuntimeException("ollama returned HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }

        JSONObject json = new JSONObject(response.body());
        if (!json.has("embeddings")) {
            throw new RuntimeException("ollama response has no embeddings key: " + abbreviate(response.body()));
        }

        JSONArray embeddings = json.getJSONArray("embeddings");
        if (embeddings.length() != texts.size()) {
            throw new RuntimeException("asked for " + texts.size() + " vectors, got " + embeddings.length());
        }

        List<List<Float>> out = new ArrayList<>();
        for (int i = 0; i < embeddings.length(); i++) {
            JSONArray vector = embeddings.getJSONArray(i);
            if (vector.length() != dims) {
                throw new IllegalStateException("model " + model + " returned " + vector.length()
                        + " dimensions but the mapping expects " + dims
                        + " - change -Dscge.embedding.model / -Dscge.embedding.dims and the"
                        + " embedding field in clinicalTrialMappings.json together");
            }
            List<Float> values = new ArrayList<>(vector.length());
            for (int j = 0; j < vector.length(); j++) {
                values.add((float) vector.getDouble(j));
            }
            out.add(values);
        }
        return out;
    }

    public void logSummary() {
        if (!enabled) {
            return;
        }
        logger.info("EMBEDDINGS: " + successCount + " generated, " + failureCount + " failed");
        if (failureCount > 0) {
            logger.error("EMBEDDINGS: " + failureCount + " record(s) were indexed WITHOUT a vector and"
                    + " will not be reachable by semantic search until they are re-embedded");
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
