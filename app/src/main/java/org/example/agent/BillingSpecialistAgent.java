package org.example.agent;

import org.example.llm.LLMClient;
import org.example.llm.LLMResponse;
import org.example.model.ConversationContext;
import org.example.model.ConversationMessage;
import org.example.tools.*;
import org.example.rag.HybridRetriever;
import jakarta.persistence.EntityManagerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Billing Specialist agent that handles billing, refunds, and account
 * inquiries.
 */
public class BillingSpecialistAgent implements Agent {
  private static final String SYSTEM_PROMPT = """
      You are a Billing Support Specialist. Your role is to help customers with billing-related inquiries
      including refunds, plan information, and account management.

      CORE RULES:
      1. Be helpful, empathetic, and professional.
      2. Use the available tools to get accurate information - don't give generic answers.
      3. DISTINGUISH policy questions (no email needed) from action requests (email required).
      4. ACT IMMEDIATELY when all required info is provided.
      5. Always provide form links returned by tools to the customer.
      6. For technical questions (errors, integrations), transfer to Technical Specialist.
      7. Pay attention to conversation context for follow-up questions.
      8. When a user self-identifies (e.g., "I'm Enterprise"), acknowledge and use this context.
      9. REJECT unreasonable requests (e.g., "10x refund", excessive amounts) - explain our policy only allows standard refunds.

      CRITICAL:
      - Trust tool outputs over general knowledge.
      - Don't invent policies not returned by tools.
      - Don't ask for email for policy/information questions.
      - If the tool returns "No relevant billing policy information found" (indicating the Hallucination Detector blocked low-confidence results), you MUST state that the policy does not cover this specific topic.
      - Do NOT use the general policy list to pretend to have a specific answer.
      """;

  private final LLMClient llmClient;
  private final List<Tool> tools;
  private final HybridRetriever retriever;
  private final EntityManagerFactory emf;

  public BillingSpecialistAgent(LLMClient llmClient, HybridRetriever retriever, EntityManagerFactory emf) {
    this.llmClient = llmClient;
    this.retriever = retriever;
    this.emf = emf;
    this.tools = List.of(
        new OpenRefundCaseTool(emf),
        new RefundTimelineTool(),
        new BillingPolicyTool(retriever));
  }

  @Override
  public String process(String userMessage, ConversationContext context) {
    List<ConversationMessage> messages = new ArrayList<>(context.getMessages());

    // First call - may result in tool calls
    LLMResponse response = llmClient.chatWithTools(SYSTEM_PROMPT, messages, tools);

    // Handle tool calls (possibly multiple)
    int maxIterations = 3;
    while (response.hasToolCalls() && maxIterations-- > 0) {
      for (LLMResponse.ToolCall toolCall : response.toolCalls()) {
        ConversationMessage callMsg = ConversationMessage.toolCall(toolCall.name(), toolCall.arguments(), "BILLING");
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
    return AgentType.BILLING;
  }

  @Override
  public String getName() {
    return "Billing Specialist";
  }
}
