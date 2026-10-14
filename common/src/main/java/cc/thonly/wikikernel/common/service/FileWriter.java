package cc.thonly.wikikernel.common.service;

/**
 * 文件写入桥（由 core 提供实现，插件可 {@code @Autowired} 注入）：把文件写进 / 移出**正式存储**。
 * <p>
 * 与只读的 {@link FileStore} 相对：core 的 {@code FileService} 仍是唯一的写入方，
 * 插件通过本桥把「通过审核」的待审上传落盘、或应用「通过审核」的文件删除
 * （待审内容先暂存在插件自己的数据目录，通过后才走这里写入正式存储）。
 */
public interface FileWriter {

    /**
     * 写入一个文件版本（同名文件 = 新版本，旧版本进文件历史），并记录上传日志。
     *
     * @param actorUserId 以谁的身份写入（必须是已存在的用户）
     * @param name        文件名（不含 {@code 文件:} 前缀；core 会按上传规则校验扩展名 / 大小）
     * @param description 文件说明（写进 {@code wiki_file.description}，可空）
     * @param content     文件内容
     * @return 写入结果（含文件 ID / 大小 / MIME），失败返回 {@link Result#failed()}
     */
    Result store(long actorUserId, String name, String description, byte[] content);

    /**
     * 删除文件（软删除可恢复；{@code hard=true} 彻底删除含全部历史版本）。
     *
     * @return 是否删除成功（文件不存在返回 false）
     */
    boolean delete(long actorUserId, long fileId, boolean hard);

    /**
     * 写入结果。
     *
     * @param ok          是否成功
     * @param id          文件 ID（失败为 0）
     * @param name        规范文件名（失败为空串）
     * @param size        字节数
     * @param contentType MIME 类型
     * @param error       失败原因（消息键 / 异常说明；成功为空串）
     */
    record Result(boolean ok, long id, String name, long size, String contentType, String error) {

        public Result {
            name = name == null ? "" : name;
            contentType = contentType == null ? "" : contentType;
            error = error == null ? "" : error;
        }

        /** 成功。 */
        public static Result ok(long id, String name, long size, String contentType) {
            return new Result(true, id, name, size, contentType, "");
        }

        /** 失败（error 是消息键或异常说明）。 */
        public static Result failed(String error) {
            return new Result(false, 0L, "", 0L, "", error);
        }
    }
}
