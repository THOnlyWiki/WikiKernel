package cc.thonly.wikikernel.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AjaxResultTest {

    @Test
    void successFactories() {
        AjaxResult<Void> ok = AjaxResult.success();
        assertEquals(AjaxCode.SUCCESS.getCode(), ok.getCode());
        assertEquals(AjaxCode.SUCCESS.getMessage(), ok.getMessage());
        assertNull(ok.getData());
        assertTrue(ok.isSuccess());

        AjaxResult<String> withData = AjaxResult.success("内容");
        assertEquals("内容", withData.getData());
        assertTrue(withData.isSuccess());

        AjaxResult<String> withMessage = AjaxResult.success("已保存", "内容");
        assertEquals("已保存", withMessage.getMessage());
        assertEquals("内容", withMessage.getData());
    }

    @Test
    void errorFactories() {
        AjaxResult<Void> error = AjaxResult.error();
        assertEquals(AjaxCode.ERROR.getCode(), error.getCode());
        assertFalse(error.isSuccess());

        assertEquals("标题已存在", AjaxResult.error("标题已存在").getMessage());
        assertEquals(AjaxCode.NOT_FOUND.getCode(), AjaxResult.error(AjaxCode.NOT_FOUND).getCode());
        assertEquals("资源不存在", AjaxResult.error(AjaxCode.NOT_FOUND).getMessage());
    }

    @Test
    void infoAndWarnFactories() {
        assertEquals(AjaxCode.INFO.getCode(), AjaxResult.info("处理中").getCode());
        assertEquals("处理中", AjaxResult.info("处理中").getMessage());
        assertEquals(AjaxCode.WARN.getCode(), AjaxResult.warn("注意").getCode());
    }

    @Test
    void chainedSetters() {
        AjaxResult<String> result = AjaxResult.<String>success().message("自定义").data("x");
        assertEquals("自定义", result.getMessage());
        assertEquals("x", result.getData());
    }

    @Test
    void codeLookup() {
        assertEquals(AjaxCode.SUCCESS, AjaxCode.fromCode(200).orElseThrow());
        assertTrue(AjaxCode.fromCode(9999).isEmpty());
        assertTrue(AjaxCode.SUCCESS.matches(200));
    }
}
