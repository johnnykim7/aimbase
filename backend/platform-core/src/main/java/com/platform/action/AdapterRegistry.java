package com.platform.action;

import com.platform.action.notify.NotifyAdapter;
import com.platform.action.write.WriteAdapter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(AdapterRegistry.class);

    private final Map<String, WriteAdapter> writeAdapters;
    private final Map<String, NotifyAdapter> notifyAdapters;

    public AdapterRegistry(List<WriteAdapter> writes, List<NotifyAdapter> notifies) {
        this.writeAdapters = writes.stream()
                .collect(Collectors.toMap(WriteAdapter::getId, Function.identity()));
        this.notifyAdapters = notifies.stream()
                .collect(Collectors.toMap(NotifyAdapter::getId, Function.identity()));
    }

    @PostConstruct
    public void init() {
        log.info("Registered Write adapters: {}", writeAdapters.keySet());
        log.info("Registered Notify adapters: {}", notifyAdapters.keySet());
    }

    public WriteAdapter getWriteAdapter(String adapterId) {
        WriteAdapter adapter = writeAdapters.get(adapterId);
        if (adapter == null) {
            throw new IllegalArgumentException("No Write adapter found: " + adapterId);
        }
        return adapter;
    }

    public NotifyAdapter getNotifyAdapter(String adapterId) {
        NotifyAdapter adapter = notifyAdapters.get(adapterId);
        if (adapter == null) {
            throw new IllegalArgumentException("No Notify adapter found: " + adapterId);
        }
        return adapter;
    }

    public boolean hasWriteAdapter(String adapterId) {
        return writeAdapters.containsKey(adapterId);
    }

    public boolean hasNotifyAdapter(String adapterId) {
        return notifyAdapters.containsKey(adapterId);
    }
}
