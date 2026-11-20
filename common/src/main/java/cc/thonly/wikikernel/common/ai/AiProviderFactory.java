package cc.thonly.wikikernel.common.ai;

/** Core owns network clients and protocol adapters. */
public interface AiProviderFactory {
    AiProvider create(String id, AiProviderConfig config);
    boolean isPublicUrl(String url);
}
