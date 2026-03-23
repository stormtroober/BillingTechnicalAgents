package org.example.tools;

import org.example.rag.HybridRetriever;
import org.example.rag.ScoredChunk;

import java.util.*;

/**
 * Tool for retrieving relevant documentation sections.
 * Uses hybrid RAG pipeline (BM25 + Vector + RRF + Reranking).
 * 
 * Implements confidence threshold to reduce hallucinations by filtering
 * out low-relevance results.
 */
public class DocumentRetrievalTool implements Tool {

    private final HybridRetriever retriever;

    /**
     * Minimum confidence score for a result to be considered reliable.
     * Results below this threshold will be flagged as low-confidence.
     * Based on RRF scores which typically range from 0.01 to 0.03 for good matches.
     */
    private static final double CONFIDENCE_THRESHOLD = 0.015;

    /**
     * Shutdown the static retriever instance.
     * Deprecated: Lifecycle is now managed in App.java.
     */
    public static void shutdown() {
        // Handled by App.java shutdown hook now
    }

    // Document-friendly names for better output
    private static final Map<String, String> DOC_NAMES = Map.of(
            "troubleshooting.md", "Troubleshooting Guide",
            "integration_guide.md", "Integration Guide",
            "faq.md", "FAQ",
            "system_requirements.md", "System Requirements",
            "installation.md", "Installation Guide",
            "billing_policy.md", "Billing Policy");

    public DocumentRetrievalTool(HybridRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String getName() {
        return "search_documentation";
    }

    @Override
    public String getDescription() {
        return "Search technical documentation for information related to a query. " +
                "Returns the most relevant sections from troubleshooting guides, integration docs, " +
                "FAQs, system requirements, and installation guides. Uses hybrid semantic + keyword search.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> queryProp = new HashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "The search query to find relevant documentation sections");
        properties.put("query", queryProp);

        schema.put("properties", properties);
        schema.put("required", List.of("query"));

        return schema;
    }

    @Override
    public String execute(Map<String, String> parameters) {
        String query = parameters.get("query");
        if (query == null || query.isEmpty()) {
            return "Error: No search query provided";
        }

        // Use hybrid retriever
        List<ScoredChunk> results = retriever.retrieve(query, 5); // Retrieve more to allow filtering

        if (results.isEmpty()) {
            return "No relevant documentation found for query: \"" + query + "\"\n\n" +
                    "Available documentation covers:\n" +
                    "- Troubleshooting (connection issues, authentication, performance)\n" +
                    "- API Integration (authentication, endpoints, webhooks)\n" +
                    "- System Requirements (SDK, self-hosted, browsers)\n" +
                    "- Installation (Linux, Windows, macOS, SDKs)\n" +
                    "- FAQ (general questions, account management, API & security)";
        }

        // Filter results by confidence threshold
        List<ScoredChunk> highConfidenceResults = results.stream()
                .filter(chunk -> chunk.score() >= CONFIDENCE_THRESHOLD)
                .limit(3)
                .toList();

        // Check if we have any high-confidence results
        boolean lowConfidenceWarning = highConfidenceResults.isEmpty();

        // If no high-confidence results, return "No relevant documentation found"
        if (lowConfidenceWarning) {
            return "No relevant documentation found for query: \"" + query + "\"\n" +
                    "(All results were below confidence threshold)\n\n" +
                    "Available documentation covers:\n" +
                    "- Troubleshooting (connection issues, authentication, performance)\n" +
                    "- API Integration (authentication, endpoints, webhooks)\n" +
                    "- System Requirements (SDK, self-hosted, browsers)\n" +
                    "- Installation (Linux, Windows, macOS, SDKs)\n" +
                    "- FAQ (general questions, account management, API & security)";
        }

        List<ScoredChunk> finalResults = highConfidenceResults;

        // Format results
        StringBuilder output = new StringBuilder();

        // Add low-confidence warning if applicable

        output.append("Documentation Search Results (showing top ").append(finalResults.size())
                .append(" matches)\n\n");

        for (ScoredChunk chunk : finalResults) {
            String docName = DOC_NAMES.getOrDefault(chunk.source(), chunk.source());
            String confidenceLevel = chunk.score() >= CONFIDENCE_THRESHOLD ? "HIGH" : "LOW";
            output.append("----------------------------------------\n");
            output.append("Source: ").append(docName).append("\n");
            output.append("Confidence: ").append(confidenceLevel);
            output.append(" (").append(String.format("%.3f", chunk.score())).append(")\n\n");
            output.append(chunk.content());
            output.append("\n\n");
        }

        return output.toString();
    }
}
