package org.example.agent;

import org.example.llm.LLMClient;
import org.example.model.ConversationContext;
import org.example.model.ConversationMessage;
import org.example.rag.HybridRetriever;

import java.util.List;

/**
 * Coordinator agent that routes messages to appropriate specialist agents.
 */
public class CoordinatorAgent implements Agent {
    private static final int ROUTING_CONTEXT_MESSAGE_COUNT = 4;
    private static final String AGENT_NAME_TECHNICAL = "TECHNICAL";
    private static final String AGENT_NAME_BILLING = "BILLING";
    private static final String AGENT_NAME_COORDINATOR = "COORDINATOR";

    private static final String ROUTING_PROMPT = """
            You are a support routing coordinator. Your job is to analyze each customer message
            and determine which specialist should handle it.

            Available specialists:
            1. TECHNICAL - Handles: product features, errors, bugs, integrations, API questions,
               troubleshooting, system requirements, installation, configuration
            2. BILLING - Handles: refunds, payments, subscriptions, plan changes, pricing,
               invoices, account charges, billing disputes, customer identification (plan type, account status)
            3. UNKNOWN - Use this ONLY when the question is completely unrelated to our support
               (e.g., weather, sports, general knowledge questions)

            IMPORTANT:
            - If the user identifies themselves or their plan (e.g., "I'm an Enterprise customer", "I have Basic plan"),
              route to BILLING since this is account/subscription related information.
            - When in doubt between BILLING and TECHNICAL, prefer routing to the specialist.
            - UNKNOWN should be very rare - only for completely off-topic requests.

            RESPOND WITH EXACTLY ONE WORD: TECHNICAL, BILLING, or UNKNOWN

            Consider the full conversation context when routing. If a follow-up question
            relates to the same topic, keep it with the same specialist.
            """;

    private static final String UNKNOWN_PROMPT = """
            The user's question falls outside the scope of our support services.
            Politely explain that you can only help with:
            - Technical questions (product features, troubleshooting, integrations, API usage)
            - Billing inquiries (refunds, payments, subscriptions, pricing, invoices)

            Ask if there's anything related to these areas you can help with.
            """;

    private final LLMClient llmClient;
    private final Agent technicalAgent;
    private final Agent billingAgent;
    private final HybridRetriever retriever;

    public CoordinatorAgent(LLMClient llmClient, HybridRetriever retriever) {
        this.llmClient = llmClient;
        this.retriever = retriever;
        this.technicalAgent = new TechnicalSpecialistAgent(llmClient, retriever);
        this.billingAgent = new BillingSpecialistAgent(llmClient, retriever);
    }

    @Override
    public String process(String userMessage, ConversationContext context) {
        // Direct routing without translation - allowing the agents to handle
        // multilingual input naturally
        AgentType targetAgent = routeMessage(userMessage, context);

        // Update context with the new interaction BEFORE delegating to agents
        // so any tool calls they push to context fall AFTER this user message
        context.addMessage(ConversationMessage.user(userMessage));

        String response;
        String respondingAgent;

        switch (targetAgent) {
            case TECHNICAL -> {
                response = technicalAgent.process(userMessage, context);
                respondingAgent = AGENT_NAME_TECHNICAL;
            }
            case BILLING -> {
                response = billingAgent.process(userMessage, context);
                respondingAgent = AGENT_NAME_BILLING;
            }
            default -> {
                // Use LLM to generate response in user's language
                response = llmClient.chat(UNKNOWN_PROMPT,
                        List.of(ConversationMessage.user(userMessage)));
                respondingAgent = AGENT_NAME_COORDINATOR;
            }
        }

        context.addMessage(ConversationMessage.assistant(response, respondingAgent));
        context.setCurrentAgentType(respondingAgent);

        return formatResponse(response, respondingAgent);
    }

    private AgentType routeMessage(String userMessage, ConversationContext context) {
        // Build context for routing decision
        StringBuilder routingContext = new StringBuilder();

        // Include recent conversation history for context
        List<ConversationMessage> recentMessages = context.getRecentMessages(ROUTING_CONTEXT_MESSAGE_COUNT);
        if (!recentMessages.isEmpty()) {
            routingContext.append("Recent conversation:\n");
            for (ConversationMessage msg : recentMessages) {
                routingContext.append(msg.role()).append(": ").append(msg.content()).append("\n");
            }
            routingContext.append("\nCurrent agent: ").append(context.getCurrentAgentType()).append("\n\n");
        }

        routingContext.append("New message to route: ").append(userMessage);

        String decision = llmClient.chat(ROUTING_PROMPT,
                List.of(ConversationMessage.user(routingContext.toString())));

        decision = decision.trim().toUpperCase();

        // Parse the decision
        if (decision.contains("TECHNICAL")) {
            return AgentType.TECHNICAL;
        } else if (decision.contains("BILLING")) {
            return AgentType.BILLING;
        } else {
            return AgentType.UNKNOWN;
        }
    }

    private String formatResponse(String response, String agentType) {
        String agentLabel = switch (agentType) {
            case AGENT_NAME_TECHNICAL -> "🔧 Technical Specialist";
            case AGENT_NAME_BILLING -> "💳 Billing Specialist";
            default -> "🤖 Support";
        };

        return "\n[" + agentLabel + "]\n" + response;
    }

    @Override
    public AgentType getType() {
        return null; // Coordinator doesn't have a specific type
    }

    @Override
    public String getName() {
        return "Coordinator";
    }
}
