package com.copytrading.child.dto;

import java.util.UUID;

public class CopySettingsRequest {
    private UUID masterId;
    private String copySides;
    private Boolean allowShortSelling;

    public UUID getMasterId() { return masterId; }
    public void setMasterId(UUID masterId) { this.masterId = masterId; }
    public String getCopySides() { return copySides; }
    public void setCopySides(String copySides) { this.copySides = copySides; }
    public Boolean getAllowShortSelling() { return allowShortSelling; }
    public void setAllowShortSelling(Boolean allowShortSelling) { this.allowShortSelling = allowShortSelling; }
}
