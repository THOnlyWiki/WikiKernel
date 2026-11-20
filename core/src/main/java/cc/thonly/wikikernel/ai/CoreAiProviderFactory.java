package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.ai.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CoreAiProviderFactory implements AiProviderFactory {
    private final AiConfig config;
    private final ObjectMapper mapper;
    private final cc.thonly.wikikernel.common.service.SiteConfig settings;
    private final AiProperties properties;
    private final cc.thonly.wikikernel.common.service.PluginDatabase database;
    private final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
    private final ConcurrentHashMap<String, AiHttp> clients = new ConcurrentHashMap<>();

    public CoreAiProviderFactory(AiConfig config, ObjectMapper mapper,
                                cc.thonly.wikikernel.common.service.SiteConfig settings, AiProperties properties,
                                cc.thonly.wikikernel.common.service.PluginDatabase database) {
        this.config = config;
        this.mapper = mapper;
        this.settings = settings;
        this.properties = properties;
        this.database = database;
    }

    @Override
    public AiProvider create(String id, AiProviderConfig options) {
        if ("mock".equals(options.type())) return new MockProvider(id, options);
        String proxy = options.proxy().isBlank() ? config.settings().proxy() : options.proxy();
        String clientKey = config.settings().timeoutSeconds() + "|" + proxy;
        if (clients.size() > 32) clients.clear();
        AiHttp http = clients.computeIfAbsent(clientKey,
                key -> new AiHttp(mapper, config.settings().timeoutSeconds(), proxy));
        AiProvider provider = switch (options.type()) {
            case "anthropic" -> new AnthropicProvider(id, options, http);
            case "gemini" -> new GeminiProvider(id, options, http);
            case "openai" -> new OpenAiProvider(id, options, http);
            default -> throw new IllegalArgumentException("Unsupported AI protocol");
        };
        return new GuardedProvider(provider, options.baseUrl());
    }

    @Override
    public boolean isPublicUrl(String url) {
        return AiEndpointPolicy.isPublicHttpUrl(url);
    }

    private final class GuardedProvider implements AiProvider {
        private final AiProvider delegate;
        private final String endpoint;
        private GuardedProvider(AiProvider delegate, String endpoint) { this.delegate = delegate; this.endpoint = endpoint; }
        public String id() { return delegate.id(); }
        public String label() { return delegate.label(); }
        public String model() { return delegate.model(); }
        public String type() { return delegate.type(); }
        public boolean available() { return delegate.available(); }
        public String complete(java.util.List<AiMessage> messages) {
            int max = Math.max(1, Math.min(4, settings.getInt("ai.max-concurrency", 1)));
            if (running.incrementAndGet() > max) {
                running.decrementAndGet(); throw new AiBusyException();
            }
            long callId = 0, started = System.nanoTime();
            String outcome = "FAILED", answer = "";
            try {
                if (!endpoint.isBlank()) {
                    AiEndpointPolicy.validate(endpoint, settings.getBoolean("ai.provider." + id() + ".private", false)
                            || properties.getProviders().containsKey(id()));
                }
                synchronized (CoreAiProviderFactory.this) {
                    String day = java.time.Instant.now().toString().substring(0, 10);
                    long used = database.queryOne("SELECT COUNT(*) AS total FROM wiki_ai_call WHERE created_at >= ?", day)
                            .orElseThrow().longValue("total", 0);
                    if (used >= settings.getInt("ai.daily-call-limit", 100)) {
                        throw new AiException("今天的站点 AI 请求额度已用完。");
                    }
                    long chars = messages.stream().mapToLong(m -> m.content().length()).sum();
                    callId = database.insert("INSERT INTO wiki_ai_call (provider, model, outcome, duration_ms, input_chars, output_chars, created_at)"
                            + " VALUES (?, ?, 'RUNNING', 0, ?, 0, ?)", id(), model(), chars, java.time.Instant.now().toString());
                }
                answer = java.util.Objects.requireNonNullElse(delegate.complete(messages), "");
                outcome = "SUCCESS";
                return answer;
            } finally {
                running.decrementAndGet();
                if (callId > 0) database.update("UPDATE wiki_ai_call SET outcome = ?, duration_ms = ?, output_chars = ? WHERE id = ?",
                        outcome, (System.nanoTime() - started) / 1000000, answer.length(), callId);
            }
        }
    }
}
