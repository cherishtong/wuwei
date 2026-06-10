package com.wuwei.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "skill_snapshot")
public class SnapshotEntity {

    @Id
    @Column(name = "skill_id")
    private String skillId;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "abi_version", nullable = false)
    private String abiVersion = "1.0";

    @Column(name = "snapshot_time", nullable = false)
    private Long snapshotTime;

    @Column(name = "reason")
    private String reason;

    @Column(name = "ui_tree_json", nullable = false, length = 65536)
    private String uiTreeJson;

    @Column(name = "state_summary", length = 4096)
    private String stateSummary;

    public SnapshotEntity() {}

    public SnapshotEntity(String skillId, String version, String abiVersion, Long snapshotTime,
                          String reason, String uiTreeJson, String stateSummary) {
        this.skillId = skillId;
        this.version = version;
        this.abiVersion = abiVersion;
        this.snapshotTime = snapshotTime;
        this.reason = reason;
        this.uiTreeJson = uiTreeJson;
        this.stateSummary = stateSummary;
    }

    public String getSkillId() { return skillId; }
    public void setSkillId(String s) { this.skillId = s; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public String getAbiVersion() { return abiVersion; }
    public void setAbiVersion(String v) { this.abiVersion = v; }
    public Long getSnapshotTime() { return snapshotTime; }
    public void setSnapshotTime(Long t) { this.snapshotTime = t; }
    public String getReason() { return reason; }
    public void setReason(String r) { this.reason = r; }
    public String getUiTreeJson() { return uiTreeJson; }
    public void setUiTreeJson(String j) { this.uiTreeJson = j; }
    public String getStateSummary() { return stateSummary; }
    public void setStateSummary(String s) { this.stateSummary = s; }
}
