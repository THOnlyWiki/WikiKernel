package cc.thonly.wikikernel.oss;

/** 已存储原图的元数据。 */
public record OssFile(String storedName, String name, String contentType, long size,
                      long fileId, int version, boolean current, boolean deleted,
                      String createdAt, String updatedAt) {
}
