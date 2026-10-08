package com.hmdp.ai;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 把多轮对话记忆存到 Redis。
 * <p>
 * 为什么不用默认的内存版（InMemoryChatMemoryStore 就是一个 ConcurrentHashMap）：
 * 1. 应用重启对话就断了，用户刷新页面发现 AI "失忆"；
 * 2. 将来部署多个实例，请求打到不同机器上，记忆不一致。
 * 存 Redis 之后，只要 sessionId 不变，重启 / 换机器都还能接着聊。
 * <p>
 * 序列化直接用 langchain4j 自带的 ChatMessageSerializer（底层是 JSON + 类型标记），
 * 不要用 JDK 原生序列化，跨版本反序列化容易炸。
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "ai:chat:memory:";

    /** 会话记忆保留 24 小时，避免 Redis 里堆一堆没人认领的历史 */
    private static final Duration TTL = Duration.ofHours(24);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = stringRedisTemplate.opsForValue().get(KEY_PREFIX + memoryId);
        if (json == null || json.isEmpty()) {
            // 注意：这里必须返回空 List，返回 null 会让 langchain4j 后续 add 的时候 NPE
            return new ArrayList<>();
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String json = ChatMessageSerializer.messagesToJson(messages);
        // 每次更新都续一次 TTL：用户一直在聊就不会被清掉
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + memoryId, json, TTL);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(KEY_PREFIX + memoryId);
    }
}
