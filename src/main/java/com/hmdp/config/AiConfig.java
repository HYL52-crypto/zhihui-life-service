package com.hmdp.config;

import com.hmdp.ai.Assistant;
import com.hmdp.ai.RedisChatMemoryStore;
import com.hmdp.ai.ShopAssistantTools;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import java.time.Duration;

/**
 * 智能客服装配。
 * <p>
 * 为什么写的是 OpenAiChatModel：
 * 几乎所有主流大模型厂商（DeepSeek、智谱、阿里百炼、硅基流动…）都提供
 * OpenAI 兼容协议（/chat/completions，请求响应字段一致），
 * 所以复用 langchain4j 的 OpenAI 客户端，只把 baseUrl / model 换掉即可，
 * 不需要为每家厂商单独引一个依赖。
 * <p>
 * 条件装配的目的：没配 api-key 时项目照样能启动，
 * 只是 /ai/chat 会返回"未配置"，不会因为缺 key 导致整个服务起不来。
 */
@Configuration
public class AiConfig {

    /** 系统提示词：约束人设 + 告诉它有哪些工具、什么时候该用 */
    private static final String SYSTEM_PROMPT =
            "你叫「小鉴」，是雅鉴生活志 App 的智能客服助手。\n"
            + "你的职责：帮用户找店、介绍店铺信息、帮用户预约到店、查询用户已有的预约。\n"
            + "规则：\n"
            + "1. 涉及店铺信息、预约这类实时业务数据时，必须调用工具获取，绝对不能自己编造店铺名、价格、评分。\n"
            + "2. 预约到店前，如果用户没说清时间和哪家店，先用一句话追问，不要凭空替用户决定。\n"
            + "3. 回答简洁口语化，控制在 3 句话以内，不要用 markdown 大标题。\n"
            + "4. 用户问的如果跟探店、店铺、预约无关，礼貌地说明你只负责本平台的业务。";

    /** 只保留最近这么多条消息，防止上下文无限膨胀把 token 烧光 */
    private static final int MAX_MESSAGES = 20;

    @Resource
    private ShopAssistantTools shopAssistantTools;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    /**
     * 注意这里用 @ConditionalOnExpression 而不是 @ConditionalOnProperty：
     * @ConditionalOnProperty 只要「键存在」就满足条件，而 api-key 配成空串也算存在，
     * 那样会拿着空 key 去创建模型，启动直接抛 openAiApiKey cannot be null。
     * 所以必须显式判断「非空」。
     */
    @Bean
    @ConditionalOnExpression("'${ai.llm.api-key:}' != ''")
    public ChatLanguageModel chatLanguageModel(AiProperties properties) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModel())
                .temperature(0.7)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(2)
                .logRequests(true)
                .logResponses(false)
                .build();
    }

    @Bean
    @ConditionalOnExpression("'${ai.llm.api-key:}' != ''")
    public Assistant assistant(ChatLanguageModel chatLanguageModel) {
        // ChatMemoryProvider：每个 sessionId 一份独立的滑动窗口记忆
        ChatMemoryProvider chatMemoryProvider = memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(MAX_MESSAGES)
                .chatMemoryStore(redisChatMemoryStore)
                .build();

        return AiServices.builder(Assistant.class)
                .chatLanguageModel(chatLanguageModel)
                .systemMessageProvider(memoryId -> SYSTEM_PROMPT)
                .chatMemoryProvider(chatMemoryProvider)
                // 把业务工具挂上去，大模型才能 Function Calling
                .tools(shopAssistantTools)
                .build();
    }
}
