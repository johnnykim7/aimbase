package com.platform.action.notify;

import com.platform.action.model.HealthStatus;
import com.platform.action.model.NotifyResult;

import java.util.Map;

public interface NotifyAdapter {

    String getId();

    void connect(Map<String, Object> config) throws Exception;

    void disconnect();

    HealthStatus healthCheck();

    NotifyResult publish(String channel, Object event, Map<String, Object> options);
}
