package dev.shipyard.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shipyard.common.Time;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-process event bus. Internal listeners subscribe with {@link #subscribe};
 * browsers subscribe through {@link #openStream} (Server-Sent Events), either
 * to one application's stream or to the platform-wide stream ({@code null}).
 */
@Component
public class EventBus {
    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    private static final String ALL = "*";

    private final ObjectMapper mapper;
    private final Map<String, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Set<Consumer<PlatformEvent>> listeners = new CopyOnWriteArraySet<>();

    public EventBus(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void publish(String type, String applicationId, String deploymentId, Map<String, Object> data) {
        publish(new PlatformEvent(type, applicationId, deploymentId, data == null ? Map.of() : data, Time.now()));
    }

    public void publish(PlatformEvent event) {
        for (Consumer<PlatformEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (RuntimeException e) {
                log.warn("Event listener failed for {}", event.type(), e);
            }
        }
        if (event.applicationId() != null) send(event.applicationId(), event);
        // High-volume log lines only go to application streams.
        if (!EventTypes.BUILD_LOG.equals(event.type()) && !EventTypes.DEPLOYMENT_LOG.equals(event.type())) {
            send(ALL, event);
        }
    }

    public void subscribe(Consumer<PlatformEvent> listener) {
        listeners.add(listener);
    }

    public SseEmitter openStream(String applicationId) {
        SseEmitter emitter = new SseEmitter(0L);
        String key = applicationId == null ? ALL : applicationId;
        Set<SseEmitter> set = emitters.computeIfAbsent(key, k -> new CopyOnWriteArraySet<>());
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    private void send(String key, PlatformEvent event) {
        Set<SseEmitter> set = emitters.get(key);
        if (set == null || set.isEmpty()) return;
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (IOException e) {
            log.warn("Could not serialise event {}", event.type(), e);
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(event.type()).data(json));
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
            }
        }
    }

    /** Keeps idle connections open through proxies. */
    @Scheduled(fixedDelay = 20_000)
    void heartbeat() {
        emitters.values().forEach(set -> set.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                set.remove(emitter);
            }
        }));
    }
}
