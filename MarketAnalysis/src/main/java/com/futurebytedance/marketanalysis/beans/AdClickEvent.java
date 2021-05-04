package com.futurebytedance.marketanalysis.beans;

/**
 * @author yuhang.sun
 * @version 1.0
 * @date 2021/5/4 - 18:50
 * @Description JavaBean对象AdClickEvent
 */
public class AdClickEvent {
    private Long userId;
    private Long adId;
    private String province;
    private String key;
    private Long timestamp;

    public AdClickEvent() {
    }

    public AdClickEvent(Long userId, Long adId, String province, String key, Long timestamp) {
        this.userId = userId;
        this.adId = adId;
        this.province = province;
        this.key = key;
        this.timestamp = timestamp;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getAdId() {
        return adId;
    }

    public void setAdId(Long adId) {
        this.adId = adId;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "AdClickEvent{" +
                "userId=" + userId +
                ", adId=" + adId +
                ", province='" + province + '\'' +
                ", key='" + key + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
