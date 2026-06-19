package com.copytrading.admin.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Table("admin_audit_logs")
public class AdminAuditLog {

    @Id
    private UUID id;

    @Column("user_id")
    private UUID userId;

    @Column("action")
    private String action;

    @Column("parameters")
    private String parameters;

    @Column("created_at")
    private Instant createdAt;

    public AdminAuditLog() {}

    public AdminAuditLog(UUID userId, String action, String parameters, Instant createdAt) {
        this.userId = userId;
        this.action = action;
        this.parameters = parameters;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
