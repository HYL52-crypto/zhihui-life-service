package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 读取 application.yaml 里 ai.deepseek.* 的配置。
 * 单独拆一个类，是为了 AiConfig 里能用 @ConditionalOnProperty 优雅降级：
 * 没填 api-key 就不创建模型 Bean，项目不会启动失败。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.deepseek")
public class AiProperties {

    /** DeepSeek API Key，在 https://platform.deepseek.com 申请 */
    private String apiKey;

    /** OpenAI 兼容地址，DeepSeek 官方就是这个 */
    private String baseUrl = "https://api.deepseek.com";

    /** 模型名。deepseek-chat = DeepSeek-V3，支持 Function Calling */
    private String model = "deepseek-chat";
}
