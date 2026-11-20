package cc.thonly.wikikernel.plugin.news;

public record NewsContent(String title, String summary, String body, String category, String region,
                          String sourceName, String sourceUrl, String cover, int activityYear, long activityId,
                          String activityRevision, String publishAt, String expiresAt, String pinUntil,
                          boolean aiAssisted, String aiModel) { }
