package cc.thonly.wikikernel.service.impl;

import cc.thonly.wikikernel.common.service.RateLimit;
import cc.thonly.wikikernel.service.RateLimiterService;
import org.springframework.stereotype.Service;

/**
 * 限流桥：把 core 的 {@code RateLimiterService}（滑动窗口 + 锁定，规则来自
 * {@code wikikernel.rate-limit.<分类>}）暴露给插件。
 */
@Service
public class RateLimitImpl implements RateLimit {

    private final RateLimiterService rateLimiterService;

    public RateLimitImpl(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    public Decision check(String category, String key) {
        RateLimiterService.Decision decision = rateLimiterService.check(category, key);
        return new Decision(decision.limited(), decision.retryAfterSeconds());
    }

    @Override
    public void hit(String category, String key) {
        rateLimiterService.hit(category, key);
    }

    @Override
    public void success(String category, String key) {
        rateLimiterService.success(category, key);
    }
}
