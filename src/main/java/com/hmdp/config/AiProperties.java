package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型接入配置。
 * <p>
 * 这里刻意做成「厂商无关」：所有主流国产大模型（智谱、阿里百炼、硅基流动、DeepSeek…）
 * 都提供 OpenAI 兼容协议，所以换厂商只需要改 application.yaml 里的三个值，
 * Java 代码一行都不用动。这也是为什么底层用的还是 OpenAiChatModel。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.llm")
public class AiProperties {

    /** API Key */
    private String apiKey;

    /** OpenAI 兼容接口地址（不含 /chat/completions，SDK 会自己拼） */
    private String baseUrl = "https://api.deepseek.com";

    /** 模型名，必须选支持 Function Calling 的型号，否则工具调用不会触发 */
    private String model = "deepseek-chat";
}
