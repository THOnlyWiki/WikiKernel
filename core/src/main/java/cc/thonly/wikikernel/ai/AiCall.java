package cc.thonly.wikikernel.ai;

import cc.thonly.wikikernel.common.data.DbColumn;
import com.baomidou.mybatisplus.annotation.*;

@TableName("wiki_ai_call")
public class AiCall {
    @TableId(type = IdType.AUTO) private Long id;
    @DbColumn private String provider;
    @DbColumn private String model;
    @DbColumn private String outcome;
    @DbColumn private Long durationMs;
    @DbColumn private Long inputChars;
    @DbColumn private Long outputChars;
    @DbColumn private String createdAt;
}
