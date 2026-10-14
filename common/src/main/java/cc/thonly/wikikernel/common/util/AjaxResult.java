package cc.thonly.wikikernel.common.util;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AJAX 统一返回结构：{@code { code, message, data }}。
 * <pre>
 * AjaxResult.success()                   // {"code":200,"message":"操作成功","data":null}
 * AjaxResult.success(data)               // 带数据
 * AjaxResult.success("已保存", data)      // 自定义 message + 数据
 * AjaxResult.error()                     // {"code":500,"message":"操作失败"}
 * AjaxResult.error("标题已存在")           // 自定义错误信息
 * AjaxResult.error(AjaxCode.NOT_FOUND)   // 指定错误码
 * AjaxResult.info("处理中")               // 提示
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AjaxResult<T> {
    private int code;
    private String message;
    private T data;

    public static <T> AjaxResult<T> of(AjaxCode code) {
        return new AjaxResult<>(code.getCode(), code.getMessage(), null);
    }

    public static <T> AjaxResult<T> of(AjaxCode code, T data) {
        return new AjaxResult<>(code.getCode(), code.getMessage(), data);
    }

    public static <T> AjaxResult<T> of(AjaxCode code, String message, T data) {
        return new AjaxResult<>(code.getCode(), message, data);
    }

    public static <T> AjaxResult<T> success() {
        return of(AjaxCode.SUCCESS);
    }

    public static <T> AjaxResult<T> success(T data) {
        return of(AjaxCode.SUCCESS, data);
    }

    public static <T> AjaxResult<T> success(String message, T data) {
        return of(AjaxCode.SUCCESS, message, data);
    }

    public static <T> AjaxResult<T> error() {
        return of(AjaxCode.ERROR);
    }

    public static <T> AjaxResult<T> error(String message) {
        return of(AjaxCode.ERROR, message, null);
    }

    public static <T> AjaxResult<T> error(AjaxCode code) {
        return of(code);
    }

    public static <T> AjaxResult<T> error(AjaxCode code, String message) {
        return of(code, message, null);
    }

    public static <T> AjaxResult<T> info(String message) {
        return of(AjaxCode.INFO, message, null);
    }

    public static <T> AjaxResult<T> info(String message, T data) {
        return of(AjaxCode.INFO, message, data);
    }

    public static <T> AjaxResult<T> warn(String message) {
        return of(AjaxCode.WARN, message, null);
    }

    /** 是否成功（按 {@link AjaxCode#SUCCESS} 判断）。 */
    public boolean isSuccess() {
        return code == AjaxCode.SUCCESS.getCode();
    }

    public AjaxResult<T> code(int code) {
        this.code = code;
        return this;
    }

    public AjaxResult<T> message(String message) {
        this.message = message;
        return this;
    }

    public AjaxResult<T> data(T data) {
        this.data = data;
        return this;
    }
}
