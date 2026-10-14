package cc.thonly.wikikernel.common.extension;

/**
 * 会话属性读写桥（core 用 HTTP Session 实现），让插件的表单字段 / 保存前钩子能在**同一次会话**里
 * 存取值（验证码的题面与答案就存在这里）而不用依赖 Servlet API。
 * <p>
 * 约定与 {@code HttpSession} 一致：{@code null} 值不被保存；读不到返回 {@code null}。
 * core 传进来的实例总是可读写的（需要写时会话会被创建）；没有会话上下文时是 {@link #NONE}。
 */
public interface SessionAttributes {

    /** 空实现：读恒为 {@code null}，写被忽略（没有会话上下文的场景）。 */
    SessionAttributes NONE = new SessionAttributes() {
        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
            // 无会话：忽略
        }

        @Override
        public void removeAttribute(String name) {
            // 无会话：忽略
        }
    };

    /** 读取会话属性；不存在返回 {@code null}。 */
    Object getAttribute(String name);

    /** 写入会话属性（{@code value == null} 时与 {@link #removeAttribute(String)} 等价）。 */
    void setAttribute(String name, Object value);

    /** 删除会话属性。 */
    void removeAttribute(String name);

    /** 读取字符串属性（非字符串 / 不存在返回空串）。 */
    default String getString(String name) {
        Object value = getAttribute(name);
        return value instanceof String text ? text : "";
    }
}
