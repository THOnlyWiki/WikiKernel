package cc.thonly.wikikernel.plugin.aiagent.data;

import cc.thonly.wikikernel.common.data.DbColumn;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户自己的 AI 厂商密钥（「参数设置 → AI 代理」里填的），只用于该用户自己的 AI 对话。
 * <p>
 * 站点级密钥存在站点设置（{@code wiki_setting} 的 {@code ai.provider.<id>.key} 等），
 * 这里只放个人密钥；优先级：个人密钥 &gt; 站点设置密钥 &gt; LocalSettings.yaml。
 * 密钥不会回传到前端（界面只显示尾号掩码）。
 */
@Data
@TableName("wiki_user_ai_key")
@AllArgsConstructor
@NoArgsConstructor
public class WikiUserAiKey {

    @TableId(type = IdType.AUTO)
    @DbColumn
    private Long id;

    /** 用户 ID。 */
    @DbColumn(nullable = false)
    private Long userId;

    /** 厂商 id（内置目录的 id，如 deepseek / gemini / groq / custom）。 */
    @DbColumn(length = 40, nullable = false)
    private String providerId;

    /** API 密钥。 */
    @DbColumn(length = 512)
    private String apiKey = "";

    /** 覆盖厂商默认的 base-url（可空；如自建 OpenAI 兼容端点）。 */
    @DbColumn(length = 300)
    private String baseUrl = "";

    /** 覆盖厂商默认的模型名（可空）。 */
    @DbColumn(length = 120)
    private String model = "";

    /** 该厂商的代理（可空；如 socks5://127.0.0.1:10808）。 */
    @DbColumn(length = 200)
    private String proxy = "";

    @DbColumn(nullable = false)
    private LocalDateTime updateAt;
}
