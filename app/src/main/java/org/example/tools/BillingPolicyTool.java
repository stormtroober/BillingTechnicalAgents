package org.example.tools;

import org.example.rag.HybridRetriever;
import org.example.rag.ScoredChunk;

import java.util.*;

/**
 * Tool for retrieving billing policy information.
 * Uses hybrid RAG pipeline with source filtering for billing_policy.md.
 */
public class BillingPolicyTool implements Tool {

    private final HybridRetriever retriever;
    private static final String SOURCE_FILTER = "billing_policy.md";

    public BillingPolicyTool(HybridRetriever retriever) {
        this.retriever = retriever;
    }

    @Override
    public String getName() {
        return "search_billing_policy";
    }

    @Override
    public String getDescription() {
        return "Search the billing policy document. Available sections: " +
                "1) Subscription Plans (Basic $9.99, Professional $29.99, Enterprise $99.99 with features), " +
                "2) Billing Cycle (monthly/annual options), " +
                "3) Refund Policy (full/partial eligibility), " +
                "4) Payment Methods (cards, PayPal, bank transfer), " +
                "5) Cancellation process, " +
                "6) Disputes, Payment Failures, Taxes. " +
                "Use semantic search with relevant keywords.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        Map<String, Object> queryProp = new HashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description",
                "The topic to search for in the billing policy (e.g., 'refund', 'enterprise plan', 'cancellation')");
        properties.put("query", queryProp);

        schema.put("properties", properties);
        schema.put("required", List.of("query"));

        return schema;
    }

    /**
     * Minimum confidence score for a result to be considered reliable.
     * Based on RRF scores which typically range from 0.01 to 0.03 for good matches.
     */
    private static final double CONFIDENCE_THRESHOLD = 0.015;

    @Override
    public String execute(Map<String, String> parameters) {
        String query = parameters.get("query");
        if (query == null || query.isEmpty()) {
            return "Error: No search query provided";
        }

        // Use hybrid retriever with source filter
        List<ScoredChunk> results = retriever.retrieve(query, 5, SOURCE_FILTER); // Retrieve more to allow filtering

        if (results.isEmpty()) {
            return getNoResultsMessage(query);
        }

        // Filter results by confidence threshold
        List<ScoredChunk> highConfidenceResults = results.stream()
                .filter(chunk -> chunk.score() >= CONFIDENCE_THRESHOLD)
                .limit(3)
                .toList();

        // If no high-confidence results, return "No relevant information found"
        if (highConfidenceResults.isEmpty()) {
            return getNoResultsMessage(query) + "\n(All results were below confidence threshold)";
        }

        // Format results
        StringBuilder output = new StringBuilder();
        output.append("📋 **Billing Policy Information**\n\n");

        for (ScoredChunk chunk : highConfidenceResults) {
            output.append(chunk.content());
            output.append("\n\n---\n\n");
        }

        return output.toString();
    }

    private String getNoResultsMessage(String query) {
        return "No relevant billing policy information found for: " + query +
                "\n\nThe billing policy covers: subscription plans, billing cycles, refunds, " +
                "cancellation, disputes, payment failures, and taxes.";
    }
}
