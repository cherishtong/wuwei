package com.wuwei.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "resume_data")
public class ResumeDataEntity {

    @Id
    @Column(name = "name")
    private String name;

    @Column(name = "data_json", nullable = false, length = 16384)
    private String dataJson;

    @Column(name = "mapping_json", length = 8192)
    private String mappingJson;

    @Column(name = "created_at")
    private Long createdAt;

    @Column(name = "updated_at")
    private Long updatedAt;

    public ResumeDataEntity() {}

    public ResumeDataEntity(String name, String dataJson) {
        this.name = name;
        this.dataJson = dataJson;
        long now = System.currentTimeMillis() / 1000;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDataJson() { return dataJson; }
    public void setDataJson(String j) { this.dataJson = j; }
    public String getMappingJson() { return mappingJson; }
    public void setMappingJson(String j) { this.mappingJson = j; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long t) { this.createdAt = t; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long t) { this.updatedAt = t; }
}
