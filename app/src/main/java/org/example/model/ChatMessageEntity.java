package org.example.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.FetchType;
import java.util.HashMap;
import java.util.Map;

@Entity
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private ChatSessionEntity session;

    private String role;

    @Column(length = 4000)
    private String content;

    private String agentType;

    private String toolName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name="chat_message_args", joinColumns=@JoinColumn(name="message_id"))
    @MapKeyColumn(name="arg_key")
    @Column(name="arg_value", length = 2000)
    private Map<String, String> toolArguments = new HashMap<>();

    public ChatMessageEntity() {}

    public ChatMessageEntity(String role, String content, String agentType, String toolName, Map<String, String> toolArguments) {
        this.role = role;
        this.content = content;
        this.agentType = agentType;
        this.toolName = toolName;
        if (toolArguments != null) {
            this.toolArguments = new HashMap<>(toolArguments);
        }
    }

    public Long getId() { return id; }
    
    public ChatSessionEntity getSession() { return session; }
    public void setSession(ChatSessionEntity session) { this.session = session; }

    public String getRole() { return role; }
    public String getContent() { return content; }
    public String getAgentType() { return agentType; }
    public String getToolName() { return toolName; }
    public Map<String, String> getToolArguments() { return toolArguments; }
}
