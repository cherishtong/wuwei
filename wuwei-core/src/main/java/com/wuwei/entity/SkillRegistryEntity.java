package com.wuwei.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "skill_registry")
public class SkillRegistryEntity {

    @Id
    @Column(name = "id")
    private String id;

    @Column(name = "version", nullable = false)
    private String version;

    @Column(name = "runtime", nullable = false)
    private String runtime;

    @Column(name = "abi", nullable = false)
    private String abi = "1.0";

    @Column(name = "capabilities_json", nullable = false, length = 4096)
    private String capabilitiesJson;

    @Column(name = "source")
    private String source = "local";

    @Column(name = "install_time")
    private Long installTime;

    @Column(name = "last_run")
    private Long lastRun;

    public SkillRegistryEntity() {}

    public SkillRegistryEntity(String id, String version, String runtime, String abi,
                               String capabilitiesJson, String source) {
        this.id = id;
        this.version = version;
        this.runtime = runtime;
        this.abi = abi;
        this.capabilitiesJson = capabilitiesJson;
        this.source = source;
        this.installTime = System.currentTimeMillis() / 1000;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getRuntime() { return runtime; }
    public void setRuntime(String runtime) { this.runtime = runtime; }
    public String getAbi() { return abi; }
    public void setAbi(String abi) { this.abi = abi; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String v) { this.capabilitiesJson = v; }
    public String getSource() { return source; }
    public void setSource(String s) { this.source = s; }
    public Long getInstallTime() { return installTime; }
    public void setInstallTime(Long t) { this.installTime = t; }
    public Long getLastRun() { return lastRun; }
    public void setLastRun(Long t) { this.lastRun = t; }
}
