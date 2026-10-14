package cc.thonly.wikikernel.plugin.confirmedit.service;

import cc.thonly.wikikernel.common.extension.SessionAttributes;
import cc.thonly.wikikernel.common.util.Strings;

import java.security.SecureRandom;
import java.util.Random;

/**
 * 随机加法题（仿 ConfirmEdit 的问答式验证，无需图片 / CDN）：
 * 题面与答案存在**会话**里（键与迁移前 core 的 {@code ConfirmEditService} 相同），一次一题；
 * 校验时无论对错都会**消耗**题目（防重放 / 防爆破），下一次渲染就会换一道新题。
 */
public class CaptchaChallenge {

    /** 会话属性：题目文本（与迁移前 core 的键一致）。 */
    public static final String SESSION_QUESTION = "wikikernel.confirmEdit.question";

    /** 会话属性：期望答案（与迁移前 core 的键一致）。 */
    public static final String SESSION_ANSWER = "wikikernel.confirmEdit.answer";

    /** 加数范围：1..9（题面如 {@code "6 + 7 ="}）。 */
    static final int MIN_TERM = 1;
    static final int MAX_TERM = 9;

    private final Random random;

    public CaptchaChallenge() {
        this(new SecureRandom());
    }

    /** 可注入随机源（测试里用固定种子复现题目）。 */
    public CaptchaChallenge(Random random) {
        this.random = random == null ? new SecureRandom() : random;
    }

    /**
     * 取当前会话的题目：已有则沿用（同一会话里多次渲染题目一致），没有就生成一道加法题。
     *
     * @return 题面（如 {@code "6 + 7 ="}）
     */
    public String question(SessionAttributes session) {
        if (session == null) {
            return "";
        }
        String existing = session.getString(SESSION_QUESTION);
        if (!existing.isEmpty()) {
            return existing;
        }
        int left = MIN_TERM + random.nextInt(MAX_TERM - MIN_TERM + 1);
        int right = MIN_TERM + random.nextInt(MAX_TERM - MIN_TERM + 1);
        String question = left + " + " + right + " =";
        session.setAttribute(SESSION_QUESTION, question);
        session.setAttribute(SESSION_ANSWER, Integer.toString(left + right));
        return question;
    }

    /**
     * 校验答案并消耗题目。
     *
     * @return {@link Result#OK} 正确；{@link Result#WRONG} 错误；{@link Result#MISSING} 会话里没有题目（已过期 / 已被消耗）
     */
    public Result validate(SessionAttributes session, String answer) {
        if (session == null) {
            return Result.MISSING;
        }
        String expected = session.getString(SESSION_ANSWER);
        if (expected.isEmpty()) {
            return Result.MISSING;
        }
        // 消耗题目：无论对错都清掉，下一次渲染会出新题（防重放 / 防爆破）
        session.removeAttribute(SESSION_ANSWER);
        session.removeAttribute(SESSION_QUESTION);
        String value = Strings.trimToEmpty(answer).replaceAll("[^0-9-]", "");
        return expected.equals(value) ? Result.OK : Result.WRONG;
    }

    /** 校验结果。 */
    public enum Result {
        OK,
        WRONG,
        MISSING
    }
}
