package com.platform.action.write;

import com.platform.action.model.HealthStatus;
import com.platform.action.model.WriteResult;

import java.util.Map;

public interface WriteAdapter {

    String getId();

    void connect(Map<String, Object> config) throws Exception;

    void disconnect();

    HealthStatus healthCheck();

    WriteResult write(String destination, Object data, Map<String, Object> options);

    Object read(String destination, Object query);

    WriteResult update(String destination, Object query, Object data);

    WriteResult delete(String destination, Object query);
}
