package org.javaup.delay.consumer;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.ConsumerTask;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.delay.message.DelayedVoucherReminderMessage;
import org.javaup.entity.UserInfo;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IUserInfoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.DELAY_VOUCHER_REMINDER;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 延迟抢购优惠券提醒-消费
 * @author: 阿星不是程序员
 **/

@Slf4j
@Component
public class ConsumerDelayedVoucherReminder implements ConsumerTask {//ConsumerTask是消费组件的实现接口
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private IUserInfoService userInfoService;


    @Value("${seckill.reminder.notify.sms.enabled:false}")
    private boolean smsEnabled;

    @Value("${seckill.reminder.notify.app.enabled:false}")
    private boolean appEnabled;

    @Value("${seckill.reminder.notify.sms.to:}")
    private String smsTo;

    @Value("${seckill.reminder.dedup.window.seconds:1800}")
    private long dedupWindowSeconds;

    /**
     * 当优惠券未设置allowedLevels/minLevel时的默认最小会员等级
     * */
    @Value("${seckill.reminder.notify.default.minLevel:1}")
    private int defaultMinLevel;
    /**
     * 每次提醒的最大用户数量，防止一次性通知过多
     * */
    @Value("${seckill.reminder.notify.max.users:1000}")
    private int maxNotifyUsers;
    /**
     * 是否附加通知“最近购买活跃用户”
     * */
    @Value("${seckill.reminder.notify.top.buyers.enabled:true}")
    private boolean topBuyersEnabled;
    /**
     * 统计最近多少天的购买行为
     * */
    @Value("${seckill.reminder.notify.top.buyers.days:30}")
    private int topBuyersDays;
    /**
     * Top购买用户数量
     * */
    @Value("${seckill.reminder.notify.top.buyers.count:200}")
    private int topBuyersCount;
    /**
     * 最大会员等级
     */
    @Value("${seckill.reminder.notify.user.level.max:10}")
    private int maxUserLevel;
    
    @Override
    public void execute(final String content) {
        try {
            DelayedVoucherReminderMessage msg = parseMessage(content);
            if (Objects.isNull(msg)) { 
                return; 
            }
            //将消息获取出来
            Long voucherId = msg.getVoucherId();
            SeckillVoucherFullModel voucherFull = seckillVoucherService.queryByVoucherId(voucherId);//优惠券的详细信息拿到手
            if (voucherFull == null) {
                log.warn("[DELAY_REMINDER_CONSUMER] 秒杀券不存在或缓存未命中 voucherId={}", voucherId);
                return;
            }
            //根据优惠券的会员等级规则和活跃买家设置，筛选出需要提醒的用户ID
            Set<String> userIds = buildAudienceUserIds(voucherFull);
            if (CollectionUtil.isEmpty(userIds)) {
                log.info("[DELAY_REMINDER_CONSUMER] 无符合规则的用户 voucherId={}", voucherId);
                return;
            }
            //遍历用户集合 进行去重判断后发送短信/app推送通知
            int notified = notifyUsers(voucherId, msg.getBeginTime(), userIds);
            log.info("[DELAY_REMINDER_CONSUMER] 完成提醒 voucherId={} totalUsers={} notified={}",
                    voucherId, userIds.size(), notified);
        } catch (Exception e) {
            log.warn("[DELAY_REMINDER_CONSUMER] 执行异常", e);
        }
    }

    private DelayedVoucherReminderMessage parseMessage(String content) {
        try {
            DelayedVoucherReminderMessage msg = JSON.parseObject(content, DelayedVoucherReminderMessage.class);
            if (msg == null || msg.getVoucherId() == null) {
                log.warn("[DELAY_REMINDER_CONSUMER] 消息解析失败 content={}", content);
                return null;
            }
            return msg;
        } catch (Exception ex) {
            log.warn("[DELAY_REMINDER_CONSUMER] 消息反序列化异常 content={}", content, ex);
            return null;
        }
    }

    //筛选出那些用户是要被通知的
    private Set<String> buildAudienceUserIds(SeckillVoucherFullModel voucherFull) {
        // 从优惠券配置里拿会员等级相关的字段
        // allowedLevels: 逗号分隔的允许等级列表，比如"1,2,3"表示只有1、2、3级会员能领
        String allowedLevelsStr = voucherFull.getAllowedLevels();
        Integer minLevel = voucherFull.getMinLevel();//默认最小会员等级
        Long shopId = voucherFull.getShopId();//店铺ID

       //=================================第一部分：根据会员等级规则查询用户=================================
        //筛选符合条件的用户
        List<UserInfo> userInfos = queryEligibleUserInfos(allowedLevelsStr, minLevel);
        //将userinfo列表转换为用户的id集合
        Set<String> userIds = toUserIdSet(userInfos);

        //=================================第二部分：追加top活跃买家用户=================================
        //通知判断 确实开启了通知开关 并且我们的店铺id不为空 然后进行通知(检查是否开启了top买家功能，且店铺id存在)
        if (topBuyersEnabled && Objects.nonNull(shopId)) {
            //从redis中读取该店铺最近N天的Top买家用户   topBuyersCount:获取的top用户数量（配置项：默认200）    topBuyersDays:统计最近多少天的购买行为（配置项：默认30天）
            for (Long uid : readTopBuyersFromRedis(shopId, topBuyersCount, topBuyersDays)) {
                if (uid != null) { 
                    userIds.add(String.valueOf(uid)); 
                }
            }
        } else if (topBuyersEnabled) {//如果激活但是商铺id为空
            log.warn("[DELAY_REMINDER_CONSUMER] 店铺ID为空，跳过Top买家统计");
        }
        return userIds;
    }

    //筛选等级
    private List<UserInfo> queryEligibleUserInfos(String allowedLevelsStr, Integer minLevel) {
        if (StrUtil.isNotBlank(allowedLevelsStr)) {
            Set<Integer> allowed = parseAllowedLevels(allowedLevelsStr);

            //允许通知会员等级有了以后，下面是在redis里面进行查找

            //如果解析出有效的等级集合
            if (CollectionUtil.isNotEmpty(allowed)) {//如果允许的会员等级集合不为空
                //尝试从redis的用户等级set中获取用户id
                List<Long> fromRedis = readUserIdsFromLevelSets(new ArrayList<>(allowed), maxNotifyUsers);
                //redis有数据时，构建UserInfo列表返回
                if (CollectionUtil.isNotEmpty(fromRedis)) {
                    List<UserInfo> list = new ArrayList<>(fromRedis.size());
                    for (Long uid : fromRedis) { 
                        if (uid != null) { //如果用户id不是空的话 往里面添加UserInfo对象
                            UserInfo u = new UserInfo(); 
                            u.setUserId(uid); 
                            list.add(u);
                        } 
                    }
                    return list;
                }
                //redis没有数据时，从数据库中查询（回退到数据库来查询 使用in条件来匹配）
                return userInfoService.lambdaQuery()
                        .select(UserInfo::getUserId, UserInfo::getLevel)
                        .in(UserInfo::getLevel, allowed)
                        .last("limit " + maxNotifyUsers)
                        .list();
            }
            //  allowedlevels解析结果为空，回退使用minlevel或默认值
            int useMin = Objects.nonNull(minLevel) ? minLevel : defaultMinLevel;
            //构建从useMin到maxUserLevel的等级列表
            List<Long> fromRedis = readUserIdsFromLevelSets(buildLevelRange(useMin, maxUserLevel), maxNotifyUsers);
            if (CollectionUtil.isNotEmpty(fromRedis)) {
                List<UserInfo> list = new ArrayList<>(fromRedis.size());
                for (Long uid : fromRedis) { 
                    if (uid != null) {
                        UserInfo u = new UserInfo(); 
                        u.setUserId(uid); 
                        list.add(u);
                    } 
                }
                return list;
            }
            //redis没有回退到数据库查询 使用>=等级条件来匹配指定等级
            return userInfoService.lambdaQuery()
                    .select(UserInfo::getUserId, UserInfo::getLevel)
                    .ge(UserInfo::getLevel, useMin)
                    .last("limit " + maxNotifyUsers)
                    .list();
        }
        //如果最小的集合不为空，调用redis构建
        if (Objects.nonNull(minLevel)) {
            List<Long> fromRedis = readUserIdsFromLevelSets(buildLevelRange(minLevel, maxUserLevel), maxNotifyUsers);
            if (CollectionUtil.isNotEmpty(fromRedis)) {
                List<UserInfo> list = new ArrayList<>(fromRedis.size());
                for (Long uid : fromRedis) {
                    if (uid != null) {
                        UserInfo u = new UserInfo();
                        u.setUserId(uid); list.add(u);
                    }
                }
                return list;
            }
            //redis没有回退到数据库查询 使用>=等级条件来匹配指定等级
            return userInfoService.lambdaQuery()
                    .select(UserInfo::getUserId, UserInfo::getLevel)
                    .ge(UserInfo::getLevel, minLevel)
                    .last("limit " + maxNotifyUsers)
                    .list();
        }
        //minlevel为空，那么只能使用默认的配置 将默认的最小值，最大值传进来，通知这个用户
        List<Long> fromRedis = readUserIdsFromLevelSets(buildLevelRange(defaultMinLevel, maxUserLevel), maxNotifyUsers);
        if (CollectionUtil.isNotEmpty(fromRedis)) {
            List<UserInfo> list = new ArrayList<>(fromRedis.size());
            for (Long uid : fromRedis) { 
                if (uid != null) { 
                    UserInfo u = new UserInfo(); 
                    u.setUserId(uid); 
                    list.add(u);
                } 
            }
            return list;
        }
        return userInfoService.lambdaQuery()
                .select(UserInfo::getUserId, UserInfo::getLevel)
                .ge(UserInfo::getLevel, defaultMinLevel)
                .last("limit " + maxNotifyUsers)
                .list();
    }

    //构建从min到max的等级列表 有几个会员等级
    //举例：如果min=3 max=6 则返回 3456
    private List<Integer> buildLevelRange(int min, int max) {
        int from = Math.max(min, 1);
        int to = Math.max(max, from);
        List<Integer> levels = new ArrayList<>(to - from + 1);
        for (int lv = from; lv <= to; lv++) { 
            levels.add(lv); 
        }
        return levels;
    }

    //从redis的用户等级set中获取用户id 参数是会员等级集合和最大返回用户数量（个数）
    private List<Long> readUserIdsFromLevelSets(List<Integer> levels, int count) {
        if (CollectionUtil.isEmpty(levels)) { //如果会员等级集合为空
            return Collections.emptyList(); //返回空列表
        }
        //构建各个等级对应的redis key列表
        List<RedisKeyBuild> keys = new ArrayList<>(levels.size());
        for (Integer lv : levels) {//循环等级
            if (lv == null) { 
                continue; 
            }
            //真正的redis的键值  为每个等级构建对应的redis key
            keys.add(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_LEVEL_MEMBERS_TAG_KEY, lv));
        }
        if (keys.isEmpty()) { //如果构建的redis key列表为空 直接返回空列表
            return Collections.emptyList();
        }
        //单个等级的简化处理 直接从redis SET中随机获取指定数量的不重复用户
        if (keys.size() == 1) {//即只有一个会员等级需要处理 从 Redis Set 中随机获取 count 个不重复的用户ID 将结果转换为ArrayList<Long> 返回
            Set<Long> r = redisCache.distinctRandomMembersForSet(keys.get(0), Math.max(count, 1), Long.class);
            return new ArrayList<>(r);
        }
        String label;
        //多个等级时，构建并集key的label
        //举例：如果等级列表为 3456 则并集key的label为 3-6 拼成头和尾
        if (levels.size() >= 2) { 
            label = levels.get(0) + "-" + levels.get(levels.size()-1); 
        } else { 
            label = String.valueOf(levels.get(0)); 
        }
        //构建临时并集key
        RedisKeyBuild dest = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_LEVEL_MEMBERS_UNION_TAG_KEY, label);
        RedisKeyBuild base = keys.get(0);//第一个key作为基准集合
        Collection<RedisKeyBuild> others = keys.subList(1, keys.size());//其他key作为要合并的集合 排除第一个key
        try {
            //执行redis并集命令 将多个set的并集存储到des key
            redisCache.unionAndStoreForSet(base, others, dest);
            //临时key设置过期时间 避免长时间占用内存
            redisCache.expire(dest, 60, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[DELAY_REMINDER_CONSUMER] SET并集失败 levels={} label={}", levels, label, e);
        }
        //从并集key中随机获取指定数量的不重复用户id
        Set<Long> r = redisCache.distinctRandomMembersForSet(dest, Math.max(count, 1), Long.class);
        return new ArrayList<>(r);
    }

    //解析允许的会员等级
    private Set<Integer> parseAllowedLevels(String allowedLevelsStr) { //分割，转换成我们的set集合
        Set<Integer> allowed = new HashSet<>();
        if (StrUtil.isBlank(allowedLevelsStr)) { return allowed; }
        String[] parts = allowedLevelsStr.split(",");//进行分割 按逗号分割
        for (String s : parts) {
            if (StrUtil.isNotBlank(s)) {//跳过空字符串
                try { 
                    allowed.add(Integer.valueOf(s.trim())); //这段代码的功能是将逗号分隔的字符串转换为整数集合
                } catch (Exception ignore) {
                    //忽略转换异常
                }
            }
        }
        return allowed;
    }

    //将用户信息列表转换为用户ID集合
    private Set<String> toUserIdSet(List<UserInfo> userInfos) {
        Set<String> userIds = new LinkedHashSet<>();//用link的hash set保证插入顺序和我们的传入顺序是一致的
        if (CollectionUtil.isEmpty(userInfos)) { return userIds; }
        for (UserInfo ui : userInfos) {
            if (Objects.nonNull(ui) && Objects.nonNull(ui.getUserId())) {//如果不是空 id也不为空 将其加入id
                userIds.add(String.valueOf(ui.getUserId()));
            }
        }
        return userIds;
    }

    //
    private List<Long> readTopBuyersFromRedis(Long shopId, int count, int days) {
        try {
            LocalDate today = LocalDate.now();//获取今天日期作为起始点   日期往前推 （如果是 2 那么是今天和昨天 如果是三 是今天 昨天 前天 ）
            DateTimeFormatter fmt = DateTimeFormatter.BASIC_ISO_DATE;//日期格式化器 输出格式为20251225
            List<RedisKeyBuild> dailyKeys = new ArrayList<>(); //构建最近N天每天的redis key列表
            for (int i = 0; i < Math.max(days, 1); i++) {
                //计算历史日期  今天 昨天 前天 ...
                String day = today.minusDays(i).format(fmt);
                dailyKeys.add(RedisKeyBuild.createRedisKey(//每构建一个日期 就构建一个redis key 加入列表
                        RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_DAILY_TAG_KEY,
                        shopId,
                        day
                ));
            }
            if (dailyKeys.isEmpty()) { //如果为空的话，直接返回
                return Collections.emptyList(); 
            }
            if (dailyKeys.size() == 1) { //如果长度为一的话，直接返回 （说明只差一天） 单日key的简化处理  getReverseRangeForSortedSet返回按score倒序排列的结果，（购买次数最多的排在前面）
                Set<Long> topSet = redisCache.getReverseRangeForSortedSet(
                        dailyKeys.get(0), 0, Math.max(count - 1, 0), Long.class);
                return new ArrayList<>(topSet);
            }
            //如果不是1 要按照之前讲的并集的规则去取  并集key的label为 3-6 拼成头和尾
            //多天时，构建日期范围标签，用于临时key命令 比如查询最多哦三十天 则rangeLabel为 20251126-20251225
            String rangeLabel = today.minusDays(dailyKeys.size() - 1).format(fmt) + "-" + today.format(fmt);
            //构建临时存储合并结果的key
            RedisKeyBuild destKey = RedisKeyBuild.createRedisKey(
                    RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_UNION_TAG_KEY,
                    shopId,
                    rangeLabel
            );
            //第一个key作为基准zset
            RedisKeyBuild base = dailyKeys.get(0);
            //其余key作为要合并的zset      （合并时 分数是会累加的）
            Collection<RedisKeyBuild> others = dailyKeys.subList(1, dailyKeys.size());
            try {
                //执行redis zset并集命令 将多个zset的并集存储到dest key
                //合并时，score 取各个zset的score之和（购买次数累加）  即各天购买次数汇总
                redisCache.unionAndStoreForSortedSet(base, others, destKey);
                //设置过期时间 60秒
                redisCache.expire(destKey, 60, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[DELAY_REMINDER_CONSUMER] ZSET并集失败 shopId={} range={}", shopId, rangeLabel, e);
            }

            //从合并结果中按score（也就是购买次数）倒序获取指定数量的top买家用户
            Set<Long> topSet = redisCache.getReverseRangeForSortedSet(destKey, 0, Math.max(count - 1, 0), Long.class);
            return new ArrayList<>(topSet);
        } catch (Exception ex) {
            log.warn("[DELAY_REMINDER_CONSUMER] 读取Redis Top买家失败 shopId={} days={} count={} ex={}",
                    shopId, days, count, ex.getMessage());
            return Collections.emptyList();
        }
    }

    //通知用户
    private int notifyUsers(Long voucherId, LocalDateTime beginTime, Set<String> userIds) {
        int notifyCount = 0;
        for (String userIdStr : userIds) {//遍历我们的所有目标用户进行通知
            if (StrUtil.isBlank(userIdStr)) { continue; }
            boolean shouldNotify;
            try {
                shouldNotify = redisCache.setIfAbsent(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_REMINDER_NOTIFY_DEDUP_KEY, voucherId, userIdStr),
                        "1",
                        dedupWindowSeconds,
                        java.util.concurrent.TimeUnit.SECONDS
                );
            } catch (Exception e) {
                shouldNotify = true;
            }
            if (!shouldNotify) { 
                continue; 
            }
            String notifyContent = String.format("[REMINDER] voucherId=%s userId=%s beginTime=%s",
                    voucherId, userIdStr, beginTime);
            if (smsEnabled && StrUtil.isNotBlank(smsTo)) {
                log.info("[REMINDER_SMS] to={} content={}", smsTo, notifyContent);
            }
            if (appEnabled) {
                log.info("[REMINDER_APP] userId={} content={}", userIdStr, notifyContent);
            }
            notifyCount++;
        }
        return notifyCount;
    }

    //消费组件的实现接口，用于处理延迟队列的消息
    @Override
    public String topic() {
        return SpringUtil.getPrefixDistinctionName() + "-" + DELAY_VOUCHER_REMINDER;
    }
}
