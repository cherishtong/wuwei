package com.wuwei.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "model_routing")
public class ModelRoutingEntity {

    @Id
    @Column(name = "task_type")
    private String taskType;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "api_url")
    private String apiUrl = "";

    @Column(name = "api_key")
    private String apiKey = "";

    @Column(name = "params")
    private String params = "{}";

    @Column(name = "updated_at")
    private Long updatedAt;

    public ModelRoutingEntity() {}

    public ModelRoutingEntity(String taskType, String provider, String model) {
        this.taskType = taskType;
        this.provider = provider;
        this.model = model;
        this.apiUrl = "";
        this.apiKey = "";
        this.params = "{}";
    }

    public String getTaskType() { return taskType; }
    public void setTaskType(String t) { this.taskType = t; }
    public String getProvider() { return provider; }
    public void setProvider(String p) { this.provider = p; }
    public String getModel() { return model; }
    public void setModel(String m) { this.model = m; }
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String u) { this.apiUrl = u; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String k) { this.apiKey = k; }
    public String getParams() { return params; }
    public void setParams(String p) { this.params = p; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long t) { this.updatedAt = t; }
}
