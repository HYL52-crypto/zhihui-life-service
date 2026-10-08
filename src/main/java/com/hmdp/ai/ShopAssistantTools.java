package com.hmdp.ai;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.Appointment;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.AppointmentMapper;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.utils.UserHolder;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 给 AI 用的「工具箱」——这就是 Function Calling（现在叫 Tool Calling）里的 Tool。
 * <p>
 * 执行链路：
 * 用户提问 → 大模型发现需要外部数据 → 返回「我要调用 searchShop，参数是 xxx」
 *        → langchain4j 反射调用下面被 @Tool 标注的方法
 *        → 把方法返回值塞回对话，让大模型再组织一次自然语言回答
 * <p>
 * 面试常问的两个坑：
 * 1. 方法必须返回 String（或能被 JSON 序列化的对象）。返回 String 最稳，大模型最容易读懂。
 * 2. @Tool 的描述要写得像「说明书」，大模型是靠这段描述决定「用不用、怎么用」的，
 *    写得含糊它就不会调用。
 */
@Slf4j
@Component
public class ShopAssistantTools {

    @Resource
    private ShopMapper shopMapper;

    @Resource
    private AppointmentMapper appointmentMapper;

    /** 没登录时用一个统一的游客 id，保证本地联调也能走通 */
    private static final Long GUEST_USER_ID = 0L;

    /**
     * 工具1：按关键词搜商家
     */
    @Tool("按关键词搜索商家，返回商家id、名称、评分、人均价格、商圈地址、营业时间。" +
            "当用户想找某类店（例如咖啡、火锅、奶茶、理发）或说出某个店名时调用。")
    public String searchShop(@P("搜索关键词，例如：咖啡、火锅、茶") String keyword) {
        if (StrUtil.isBlank(keyword)) {
            return "搜索关键词不能为空";
        }
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<Shop>()
                .like(Shop::getName, keyword)
                .last("LIMIT 5");
        List<Shop> shops = shopMapper.selectList(wrapper);
        if (shops == null || shops.isEmpty()) {
            return "没有找到包含「" + keyword + "」的商家";
        }
        StringBuilder sb = new StringBuilder("共找到 " + shops.size() + " 家：");
        for (Shop shop : shops) {
            sb.append("\n").append(formatShop(shop));
        }
        return sb.toString();
    }

    /**
     * 工具2：按 id 查商家详情
     */
    @Tool("根据商家id查询该商家的详细信息，包括评分、人均价格、地址、营业时间。")
    public String queryShopDetail(@P("商家id") Long shopId) {
        if (shopId == null) {
            return "商家id不能为空";
        }
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            return "id为 " + shopId + " 的商家不存在";
        }
        return formatShop(shop);
    }

    /**
     * 工具3：预约到店（真正写库）
     */
    @Tool("为用户预约到店，会生成一条预约记录。必须在用户明确说出「预约/订个位/帮我约」且提供了商家时才调用；" +
            "如果用户没说几点到，就先追问时间，不要自己编一个时间。")
    public String bookAppointment(
            @P("商家id，必须是搜索结果里出现过的id") Long shopId,
            @P("到店时间，用户口述即可，例如：今晚18点、明天中午12点") String timeSlot,
            @P("到店人数，用户没说就填1") Integer peopleCount,
            @P("当前会话标识，原样传入系统提示里给你的 sessionId") String sessionId) {
        if (shopId == null) {
            return "预约失败：缺少商家id";
        }
        if (StrUtil.isBlank(timeSlot)) {
            return "预约失败：缺少到店时间，请向用户确认几点到店";
        }
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            return "预约失败：商家 " + shopId + " 不存在";
        }
        int people = (peopleCount == null || peopleCount <= 0) ? 1 : peopleCount;

        Appointment appointment = new Appointment()
                .setUserId(resolveUserId(sessionId))
                .setShopId(shopId)
                .setShopName(shop.getName())
                .setTimeSlot(timeSlot)
                .setPeopleCount(people)
                .setStatus(1)
                .setCreateTime(LocalDateTime.now());
        appointmentMapper.insert(appointment);

        log.info("[AI工具] 预约成功：userId={}, shopId={}, timeSlot={}, id={}",
                appointment.getUserId(), shopId, timeSlot, appointment.getId());
        return "预约成功！预约编号 " + appointment.getId()
                + "，商家：" + shop.getName()
                + "，时间：" + timeSlot
                + "，人数：" + people;
    }

    /**
     * 工具4：查我的预约
     */
    @Tool("查询当前用户自己已经提交的到店预约记录。当用户问「我约了什么」「我的预约」时调用。")
    public String listMyAppointments(
            @P("当前会话标识，原样传入系统提示里给你的 sessionId") String sessionId) {
        Long userId = resolveUserId(sessionId);
        List<Appointment> list = appointmentMapper.selectList(
                new LambdaQueryWrapper<Appointment>()
                        .eq(Appointment::getUserId, userId)
                        .orderByDesc(Appointment::getCreateTime)
                        .last("LIMIT 10"));
        if (list == null || list.isEmpty()) {
            return "当前用户还没有任何预约记录";
        }
        StringBuilder sb = new StringBuilder("你有 " + list.size() + " 条预约：");
        for (Appointment a : list) {
            sb.append("\n编号 ").append(a.getId())
                    .append("｜").append(a.getShopName())
                    .append("｜").append(a.getTimeSlot())
                    .append("｜").append(a.getPeopleCount()).append("人")
                    .append("｜状态：").append(statusText(a.getStatus()));
        }
        return sb.toString();
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 1: return "已预约";
            case 2: return "已到店";
            case 3: return "已取消";
            default: return "未知";
        }
    }

    private String formatShop(Shop shop) {
        // 评分是乘10存的（例如 45 表示 4.5 分），展示时除回去
        String score = shop.getScore() == null ? "暂无" : String.valueOf(shop.getScore() / 10.0);
        String price = shop.getAvgPrice() == null ? "暂无" : shop.getAvgPrice() + "元";
        return "id=" + shop.getId()
                + "｜" + shop.getName()
                + "｜评分 " + score
                + "｜人均 " + price
                + "｜" + (shop.getArea() == null ? "" : shop.getArea())
                + (shop.getAddress() == null ? "" : shop.getAddress())
                + "｜营业 " + (shop.getOpenHours() == null ? "未知" : shop.getOpenHours());
    }

    private Long currentUserId() {
        try {
            if (UserHolder.getUser() != null) {
                return UserHolder.getUser().getId();
            }
        } catch (Exception e) {
            // 工具类被非 Web 线程调用时取不到，忽略走游客
        }
        return GUEST_USER_ID;
    }

    /**
     * 当前会话 id。
     * <p>
     * 为什么工具里需要这个：AI 客服「未登录也能用」，这时所有游客共用 userId=0，
     * 如果预约记录只按 userId 隔离，A 游客能查到 B 游客的预约（实测确实会串数据）。
     * 所以游客场景改用「会话 id」隔离。
     * <p>
     * 注意 langchain4j 0.35.0 还没有 ToolContext（那是更高版本的能力），
     * 所以这里用最简单可靠的做法：把 sessionId 显式声明成工具参数，
     * 由大模型从上下文里带上（系统提示词里已经交代了这个参数的含义）。
     * 登录用户优先用真实 userId，这样同一用户换会话也能看到自己的历史预约。
     */
    private Long resolveUserId(String sessionId) {
        try {
            if (UserHolder.getUser() != null) {
                return UserHolder.getUser().getId();
            }
        } catch (Exception e) {
            // 非 Web 线程取不到，忽略走游客
        }
        // 游客：把会话 id 做一个稳定的数值映射，与真实用户 id 区分开（避免撞号）
        if (StrUtil.isBlank(sessionId)) {
            return GUEST_USER_ID;
        }
        return GUEST_USER_ID - (long) Math.abs(sessionId.hashCode());
    }
}
