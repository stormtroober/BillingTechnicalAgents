package org.example.agent;

import org.example.llm.LLMClient;
import org.example.llm.LLMResponse;
import org.example.model.ConversationContext;
import org.example.model.ConversationMessage;
import org.example.tools.DocumentRetrievalTool;
import org.example.tools.Tool;
import org.example.rag.HybridRetriever;

import java.util.ArrayList;
import java.util.List;

/**
 * Technical Specialist agent that answers questions using documentation.
 */
public class TechnicalSpecialistAgent implements Agent {
    private static final String SYSTEM_PROMPT = """
            You are a Technical Support Specialist. Your role is to help customers with technical questions
            about our product using ONLY the official documentation.

            CORE RULES:
            1. ALWAYS search documentation before answering any question.
            2. Only provide information backed by documentation.
            3. If documentation doesn't cover the topic, say so and offer to escalate.
            4. NEVER guess or make up information.
            5. Be helpful, professional, and thorough.
            6. ALWAYS respond in the user's language.
            7. For billing/refund questions, transfer to Billing Specialist.

            BEHAVIOR:
            - Search first, even for general problems
            - Provide step-by-step instructions when applicable
            - Give helpful information BEFORE asking for more details
            - If the tool returns "No relevant documentation found" (indicating the Hallucination Detector blocked low-confidence results), you MUST state that the documentation does not cover the specific topic.
            - Do NOT use the "Available documentation covers" list to pretend to have a solution.
            - Do NOT provide general knowledge answers if the documentation search fails.
            - ALWAYS respond in the user's language (Detect language from user input).
            """;

    private final LLMClient llmClient;
    private final List<Tool> tools;
    private final HybridRetriever retriever;

    public TechnicalSpecialistAgent(LLMClient llmClient, HybridRetriever retriever) {
        this.llmClient = llmClient;
        this.retriever = retriever;
        this.tools = List.of(new DocumentRetrievalTool(retriever));
    }

    @Override
    public String process(String userMessage, ConversationContext context) {
        List<ConversationMessage> messages = new ArrayList<>(context.getMessages());

        // First call - may result in tool call
        LLMResponse response = llmClient.chatWithTools(SYSTEM_PROMPT, messages, tools);

        // Handle tool calls
        int maxIterations = 3;
        while (response.hasToolCalls() && maxIterations-- > 0) {
            for (LLMResponse.ToolCall toolCall : response.toolCalls()) {
                ConversationMessage callMsg = ConversationMessage.toolCall(toolCall.name(), toolCall.arguments(), "TECHNICAL");
                messages.add(callMsg);
                context.addMessage(callMsg);
                
                String result = "Error: Tool execution failed or tool not found.";
                for (Tool tool : tools) {
                    if (tool.getName().equals(toolCall.name())) {
                        result = tool.execute(toolCall.arguments());
                        break;
                    }
                }
                
                ConversationMessage resultMsg = ConversationMessage.toolResult(toolCall.name(), result);
                messages.add(resultMsg);
                context.addMessage(resultMsg);
            }

            response = llmClient.chatWithTools(SYSTEM_PROMPT, messages, tools);
        }

        return response.text();
    }

    @Override
    public AgentType getType() {
        return AgentType.TECHNICAL;
    }

    @Override
    public String getName() {
        return "Technical Specialist";
    }
}
