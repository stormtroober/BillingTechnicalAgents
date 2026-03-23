package org.example.model;

import java.util.Map;

/**
 * Represents a single message in the conversation.
 */
public record ConversationMessage(
        String role, // "user", "assistant", "tool_call", "tool_result"
        String content,
        String agentType, // "COORDINATOR", "TECHNICAL", "BILLING", or null
        String toolName,
        Map<String, String> toolArguments
) {
    public static ConversationMessage user(String content) {
        return new ConversationMessage("user", content, null, null, null);
    }

    public static ConversationMessage assistant(String content, String agentType) {
        return new ConversationMessage("assistant", content, agentType, null, null);
    }
    
    public static ConversationMessage toolCall(String toolName, Map<String, String> args, String agentType) {
        return new ConversationMessage("tool_call", null, agentType, toolName, args);
    }

    public static ConversationMessage toolResult(String toolName, String result) {
        return new ConversationMessage("tool_result", result, null, toolName, null);
    }
}
