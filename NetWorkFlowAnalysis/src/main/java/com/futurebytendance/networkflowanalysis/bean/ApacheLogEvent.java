package com.futurebytendance.networkflowanalysis.bean;

/**
 * @author yuhang.sun
 * @version 1.0
 * @date 2021/5/3 - 16:13
 * @Description ApacheLogEvent
 */
public class ApacheLogEvent {
    private String ip;
    private String userId;
    private Long timestamp;
    private String method;
    private String url;

    public ApacheLogEvent() {
    }

    public ApacheLogEvent(String ip, String userId, Long timestamp, String method, String url) {
        this.ip = ip;
        this.userId = userId;
        this.timestamp = timestamp;
        this.method = method;
        this.url = url;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public String toString() {
        return "ApacheEventLog{" +
                "ip='" + ip + '\'' +
                ", userId='" + userId + '\'' +
                ", timestamp=" + timestamp +
                ", method='" + method + '\'' +
                ", url='" + url + '\'' +
                '}';
    }
}
