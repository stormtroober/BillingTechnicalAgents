package org.example.llm;

import io.github.cdimascio.dotenv.Dotenv;
import com.google.genai.Client;
import com.google.genai.types.*;
import org.example.model.ConversationMessage;
import org.example.tools.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Gemini API client implementation using the official Google Gen AI SDK.
 */
public class GeminiClient implements LLMClient {
    private final String modelName;
    private final Client client;

    public GeminiClient(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("API Key cannot be null or empty");
        }
        this.client = Client.builder().apiKey(apiKey).build();

        // Load model name from .env
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        String envModel = dotenv.get("GEMINI_MODEL");
        if (envModel == null) {
            // Try parent directory
            Dotenv parentDotenv = Dotenv.configure().directory("..").ignoreIfMissing().load();
            envModel = parentDotenv.get("GEMINI_MODEL");
        }

        this.modelName = (envModel != null && !envModel.isEmpty()) ? envModel : "gemini-2.0-flash";
    }

    @Override
    public String chat(String systemPrompt, List<ConversationMessage> messages) {
        LLMResponse response = chatWithTools(systemPrompt, messages, List.of());
        return response.text();
    }

    @Override
    @SuppressWarnings("unchecked")
    public LLMResponse chatWithTools(String systemPrompt, List<ConversationMessage> messages, List<Tool> tools) {
        try {
            // Build conversation history
            List<Content> history = messages.stream()
                    .map(msg -> {
                        switch (msg.role()) {
                            case "tool_call":
                                Map<String, Object> mappedArgs = new HashMap<>();
                                if (msg.toolArguments() != null) {
                                    mappedArgs.putAll(msg.toolArguments());
                                }
                                return Content.builder()
                                        .role("model")
                                        .parts(List.of(Part.builder()
                                                .functionCall(FunctionCall.builder()
                                                        .name(msg.toolName())
                                                        .args(mappedArgs)
                                                        .build())
                                                .build()))
                                        .build();
                            case "tool_result":
                                return Content.builder()
                                        .role("user")
                                        .parts(List.of(Part.builder()
                                                .functionResponse(FunctionResponse.builder()
                                                        .name(msg.toolName())
                                                        .response(Map.of("output", msg.content()))
                                                        .build())
                                                .build()))
                                        .build();
                            case "assistant":
                                return Content.builder()
                                        .role("model")
                                        .parts(List.of(Part.builder().text(msg.content() == null ? "" : msg.content()).build()))
                                        .build();
                            case "user":
                            default:
                                return Content.builder()
                                        .role("user")
                                        .parts(List.of(Part.builder().text(msg.content() == null ? "" : msg.content()).build()))
                                        .build();
                        }
                    })
                    .collect(Collectors.toList());

            // Configure tools
            List<com.google.genai.types.Tool> sdkTools = new ArrayList<>();
            if (!tools.isEmpty()) {
                List<FunctionDeclaration> functionDeclarations = new ArrayList<>();
                for (Tool tool : tools) {
                    functionDeclarations.add(FunctionDeclaration.builder()
                            .name(tool.getName())
                            .description(tool.getDescription())
                            .parameters(Schema.builder()
                                    .type("OBJECT")
                                    .properties(mapProperties(tool.getParametersSchema()))
                                    .required((List<String>) tool.getParametersSchema().get("required"))
                                    .build())
                            .build());
                }

                sdkTools.add(com.google.genai.types.Tool.builder()
                        .functionDeclarations(functionDeclarations)
                        .build());
            }

            // Configure generation config
            GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder()
                    .temperature(0.3f)
                    .systemInstruction(Content.builder()
                            .parts(List.of(Part.builder().text(systemPrompt).build()))
                            .build());

            if (!sdkTools.isEmpty()) {
                configBuilder.tools(sdkTools);
            }

            // Execute request
            GenerateContentResponse response = client.models.generateContent(
                    modelName,
                    history,
                    configBuilder.build());

            return parseResponse(response);

        } catch (Exception e) {
            e.printStackTrace();
            // If the default model fails, maybe try another fallback or just report error
            throw new RuntimeException("Failed to call Gemini API via SDK (" + modelName + ")", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Schema> mapProperties(Map<String, Object> schemaMap) {
        Map<String, Schema> properties = new HashMap<>();
        Map<String, Object> props = (Map<String, Object>) schemaMap.get("properties");

        if (props != null) {
            for (Map.Entry<String, Object> entry : props.entrySet()) {
                Map<String, Object> propDetails = (Map<String, Object>) entry.getValue();
                properties.put(entry.getKey(), Schema.builder()
                        .type("STRING")
                        .description((String) propDetails.get("description"))
                        .build());
            }
        }
        return properties;
    }

    private LLMResponse parseResponse(GenerateContentResponse response) {
        List<Candidate> candidates = response.candidates().orElse(Collections.emptyList());
        if (candidates.isEmpty()) {
            return LLMResponse.textOnly("No response generated.");
        }

        Candidate candidate = candidates.get(0);
        Content content = candidate.content().orElse(null);

        List<LLMResponse.ToolCall> toolCalls = new ArrayList<>();
        StringBuilder textBuilder = new StringBuilder();

        if (content != null && content.parts().isPresent()) {
            for (Part part : content.parts().get()) {
                if (part.text().isPresent()) {
                    textBuilder.append(part.text().get());
                }

                if (part.functionCall().isPresent()) {
                    FunctionCall call = part.functionCall().get();
                    Map<String, Object> args = call.args().orElse(Collections.emptyMap());
                    Map<String, String> stringArgs = new HashMap<>();
                    if (args != null) {
                        args.forEach((k, v) -> stringArgs.put(k, String.valueOf(v)));
                    }
                    toolCalls.add(new LLMResponse.ToolCall(call.name().orElse("unknown_tool"), stringArgs));
                }
            }
        }

        if (!toolCalls.isEmpty()) {
            return LLMResponse.withToolCalls(toolCalls);
        }

        return LLMResponse.textOnly(textBuilder.toString());
    }
}
