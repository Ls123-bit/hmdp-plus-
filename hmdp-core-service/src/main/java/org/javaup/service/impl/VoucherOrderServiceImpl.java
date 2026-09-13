package org.javaup.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.dto.CancelVoucherOrderDto;
import org.javaup.dto.GetVoucherOrderByVoucherIdDto;
import org.javaup.dto.GetVoucherOrderDto;
import org.javaup.dto.Result;
import org.javaup.dto.VoucherReconcileLogDto;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.UserInfo;
import org.javaup.entity.Voucher;
import org.javaup.entity.VoucherOrder;
import org.javaup.entity.VoucherOrderRouter;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessType;
import org.javaup.enums.LogType;
import org.javaup.enums.OrderStatus;
import org.javaup.enums.SeckillVoucherOrderOperate;
import org.javaup.exception.HmdpFrameException;
import org.javaup.kafka.message.SeckillVoucherMessage;
import org.javaup.kafka.producer.SeckillVoucherProducer;
import org.javaup.kafka.redis.RedisVoucherData;
import org.javaup.lua.SeckillVoucherDomain;
import org.javaup.lua.SeckillVoucherOperate;
import org.javaup.mapper.VoucherOrderMapper;
import org.javaup.mapper.VoucherOrderRouterMapper;
import org.javaup.message.MessageExtend;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCacheImpl;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.repeatexecutelimit.annotion.RepeatExecuteLimit;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IUserInfoService;
import org.javaup.service.IVoucherOrderRouterService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherReconcileLogService;
import org.javaup.service.IVoucherService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.RedisIdWorker;
import org.javaup.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.javaup.constant.Constant.SECKILL_VOUCHER_TOPIC;
import static org.javaup.constant.RepeatExecuteLimitConstants.SECKILL_VOUCHER_ORDER;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 优惠券订单 接口实现
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private IVoucherService voucherService;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;
    
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    
    @Resource
    private SeckillVoucherOperate seckillVoucherOperate;
    
    @Resource
    private SeckillVoucherProducer seckillVoucherProducer;
    
    @Resource
    private RedisCacheImpl redisCache;
    
    @Resource
    private IVoucherOrderRouterService voucherOrderRouterService;//订单路由的service引入进来
    
    @Resource
    private IUserInfoService userInfoService;
    
    @Resource
    private VoucherOrderMapper voucherOrderMapper;
    
    @Resource
    private VoucherOrderRouterMapper voucherOrderRouterMapper;
    
    @Resource
    private RedisVoucherData redisVoucherData;
    
    @Resource
    private IVoucherReconcileLogService voucherReconcileLogService;//对账的service引入进来，用于对账日志的添加
    

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
//异步执行的线程池，用于处理优惠券订单的异步操作，如自动分配优惠券给订阅用户等
    public static final ThreadPoolExecutor SECKILL_ORDER_EXECUTOR =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(1024),
                    new NamedThreadFactory("seckill-order-", false),
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );

    private static class NamedThreadFactory implements ThreadFactory {
        private final String namePrefix;
        private final boolean daemon;
        private final AtomicInteger index = new AtomicInteger(1);

        public NamedThreadFactory(String namePrefix, boolean daemon) {
            this.namePrefix = namePrefix;
            this.daemon = daemon;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, namePrefix + index.getAndIncrement());
            t.setDaemon(daemon);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("未捕获异常，线程={}, err={}", thread.getName(), ex.getMessage(), ex)
            );
            return t;
        }
    }
    
    
    @PostConstruct
    private void init(){
        // 这是黑马点评的普通版本，升级版本中不再使用此方式
        //SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    @PreDestroy
    private void destroy(){
        try {
            SECKILL_ORDER_EXECUTOR.shutdown();
            if (!SECKILL_ORDER_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                SECKILL_ORDER_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SECKILL_ORDER_EXECUTOR.shutdownNow();
        }
    }

    private class VoucherOrderHandler implements Runnable{
        private final String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 0.初始化stream
                    initStream();
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }

        public void initStream(){
            Boolean exists = stringRedisTemplate.hasKey(queueName);
            if (BooleanUtil.isFalse(exists)) {
                log.info("stream不存在，开始创建stream");
                // 不存在，需要创建
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("stream和group创建完毕");
                return;
            }
            // stream存在，判断group是否存在
            StreamInfo.XInfoGroups groups = stringRedisTemplate.opsForStream().groups(queueName);
            if(groups.isEmpty()){
                log.info("group不存在，开始创建group");
                // group不存在，创建group
                stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.latest(), "g1");
                log.info("group创建完毕");
            }
        }

        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            // 获取代理对象（事务）
            createVoucherOrderV1(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }

    IVoucherOrderService proxy;
    /**
     * 抢优惠券下单
     * */
    @Override
    public Result<Long> seckillVoucher(Long voucherId) {
        //return doSeckillVoucherV1(voucherId);
        return doSeckillVoucherV2(voucherId);
    }
    
    public Result<Long> doSeckillVoucherV1(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = snowflakeIdGenerator.nextId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 4.返回订单id
        return Result.ok(orderId);
    }
    
    public Result<Long> doSeckillVoucherV2(Long voucherId) {
       //查询秒杀优惠券
        SeckillVoucherFullModel seckillVoucherFullModel = seckillVoucherService.queryByVoucherId(voucherId);
       //加载优惠券库存
        seckillVoucherService.loadVoucherStock(voucherId);
        Long userId = UserHolder.getUser().getId();//获取当前用户id
        verifyUserLevel(seckillVoucherFullModel,userId);//验证用户会员等级

        long orderId = snowflakeIdGenerator.nextId();//生成订单id
        long traceId = snowflakeIdGenerator.nextId();//生成跟踪id（trace id）为了对账用的，也是先生成
        //执行lua脚本需要的key
        List<String> keys = ListUtil.of(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId).getRelKey(),     //库存key
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId).getRelKey(),        //用户key
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId).getRelKey()
        );
        //执行lua需要的数据
        String[] args = new String[9];
        args[0] = voucherId.toString();  //优惠券
        args[1] = userId.toString();       //用户id
        args[2] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getBeginTime()));//时间，优惠券的时间
        args[3] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getEndTime()));//时间，优惠券的结束时间
        args[4] = String.valueOf(seckillVoucherFullModel.getStatus());//优惠券状态 传脚本是字符串形式
        args[5] = String.valueOf(orderId);//订单id
        args[6] = String.valueOf(traceId);//跟踪id（trace id）为了对账用的 对账关联id
        args[7] = String.valueOf(LogType.DEDUCT.getCode());//类型是扣减
        long secondsUntilEnd = Duration.between(LocalDateTimeUtil.now(), seckillVoucherFullModel.getEndTime()).getSeconds();
        long ttlSeconds = Math.max(1L, secondsUntilEnd + Duration.ofDays(1).getSeconds());
        args[8] = String.valueOf(ttlSeconds);
        //执行lua脚本
        SeckillVoucherDomain seckillVoucherDomain = seckillVoucherOperate.execute(
                keys,
                args
        );
        //判断是否成功   判断code码和我们这个是不是成功的code码，如果不成功，抛出异常
        if (!seckillVoucherDomain.getCode().equals(BaseCode.SUCCESS.getCode())) {
            throw new HmdpFrameException(Objects.requireNonNull(BaseCode.getRc(seckillVoucherDomain.getCode())));
        }

        //发送到kafka，要构建一个消息对象  kafka消息，
        SeckillVoucherMessage seckillVoucherMessage = new SeckillVoucherMessage(
                userId,
                voucherId,
                orderId,
                traceId,
                seckillVoucherDomain.getBeforeQty(),//从脚本返回的扣减前的库存
                seckillVoucherDomain.getDeductQty(),
                seckillVoucherDomain.getAfterQty(),
                Boolean.FALSE
        );
        //发送到kafka  （用kafka的模板，要先继承那个模板）
        seckillVoucherProducer.sendPayload(
                SpringUtil.getPrefixDistinctionName() + "-" + SECKILL_VOUCHER_TOPIC,
                seckillVoucherMessage);//seckillVoucherMessage为消息
        //返回订单id
        return Result.ok(orderId);
    }


    //验证会员等级的方法
    public void verifyUserLevel(SeckillVoucherFullModel seckillVoucherFullModel,Long userId){
        String allowedLevelsStr = seckillVoucherFullModel.getAllowedLevels();//允许的会员等级字符串
        Integer minLevel = seckillVoucherFullModel.getMinLevel();//最低的会员等级要求
        boolean hasLevelRule = StrUtil.isNotBlank(allowedLevelsStr) || Objects.nonNull(minLevel);
        if (!hasLevelRule) {//如果两者都为空，直接返回
            return;
        }


        UserInfo userInfo = userInfoService.getByUserId(userId);//查取用户信息
        if (Objects.isNull(userInfo)) {//如果用户信息为空，抛出异常
            throw new HmdpFrameException(BaseCode.USER_NOT_EXIST);
        }
        //allowed标识当前用户是否通过规则校验，默认通过
        boolean allowed = true;
        Integer level = userInfo.getLevel();//获取用户会员等级 可能为空
        if (StrUtil.isNotBlank(allowedLevelsStr)) {
            try {//捕获一下 不要影响主线程
                //将逗号分隔的字符串转换为整型集合
                Set<Integer> allowedLevels = Arrays.stream(allowedLevelsStr.split(","))//用逗号拆出来
                        .map(String::trim)//去掉空格
                        .filter(StrUtil::isNotBlank)//过滤空字符串，确保只有非空的字符串才会被后续处理
                        .map(Integer::valueOf)
                        .collect(Collectors.toSet());//转成set
                if (CollectionUtil.isNotEmpty(allowedLevels)) {//如果集合不为空
                    allowed = allowedLevels.contains(level);//allowedLevels.contains(level) 判断当前用户的会员等级（level）是否在允许的等级列表（allowedLevels）中。 allowed 变量：用于标记用户是否通过等级校验，默认值为 true
                }
            } catch (Exception parseEx) {
                //如果解析失败，记录日志
                log.warn("allowedLevels 解析失败, voucherId={}, raw={}",
                        seckillVoucherFullModel.getVoucherId(), 
                        allowedLevelsStr, parseEx);
            }
        }
        //处理最低级minLevel规则：仅当之前的allowed检验通过时，才进行minLevel检验
        if (allowed && Objects.nonNull(minLevel)) {
            allowed = Objects.nonNull(level) && level >= minLevel;
        }
        if (!allowed) {
            throw new HmdpFrameException("当前会员级别不满足参与条件");
        }
    }

   
    private static class AudienceRule {
        public Set<Integer> allowedLevels;
        public Integer minLevel;
        public Set<String> allowedCities;
        
        boolean hasLevelRule(){
            return (allowedLevels != null && !allowedLevels.isEmpty()) || minLevel != null;
        }
        boolean hasCityRule(){
            return allowedCities != null && !allowedCities.isEmpty();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createVoucherOrderV1(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();

        // 5.1.查询订单
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 5.2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                // set stock = stock - 1
                .setSql("stock = stock - 1")
                // where id = ? and stock > 0
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0) 
                .update();
        if (!success) {
            // 扣减失败
            log.error("库存不足！");
            return;
        }
        // 7.创建订单
        save(voucherOrder);
    }
    
    
    @Override
    @RepeatExecuteLimit(name = SECKILL_VOUCHER_ORDER,keys = {"#message.uuid"})//消费有可能重复投递，所以要加幂等

    @Transactional(rollbackFor = Exception.class)//开启事务
    public boolean createVoucherOrderV2(MessageExtend<SeckillVoucherMessage> message) {
       //获取消息体
        SeckillVoucherMessage messageBody = message.getMessageBody();
        Long userId = messageBody.getUserId();
       //根据优惠券id和用户id查询是否已存在正常状态订单   有些业务下只查找订单id，比如后续写对账的时候，根据订单编号去查找数据库，但是订单id不是分片键，所以需要全路由后查询   所以要创建一个订单路由表
        VoucherOrder normalVoucherOrder = lambdaQuery()
                .eq(VoucherOrder::getVoucherId, messageBody.getVoucherId())
                .eq(VoucherOrder::getUserId, userId)
                .eq(VoucherOrder::getStatus,OrderStatus.NORMAL.getCode())
                .one();
        //如果不为空，说明已存在此订单，抛出异常
        if (Objects.nonNull(normalVoucherOrder)) {
            log.warn("已存在此订单，voucherId：{},userId：{}", normalVoucherOrder.getVoucherId(), userId);
            throw new HmdpFrameException(BaseCode.VOUCHER_ORDER_EXIST);
        }

      //扣减库存 在数据库中扣减库存   这是在消费 Kafka 消息时执行的，属于异步操作。如果数据库扣减失败，可能需要回滚 Redis 中的库存（通过 seckillVoucherRollBack.lua 脚本）。
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", messageBody.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {//如果扣减失败     抛出异常
            throw new HmdpFrameException("优惠券库存不足！优惠券id:" + messageBody.getVoucherId());
        }
        //接下来创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(messageBody.getOrderId());
        voucherOrder.setUserId(messageBody.getUserId());
        voucherOrder.setVoucherId(messageBody.getVoucherId());
        voucherOrder.setCreateTime(LocalDateTimeUtil.now());
        save(voucherOrder);
       //创建订单路由  订单用用户id和优惠券id做分片键  但是有的时候需要根据订单id去查询订单，因为订单id不是分片键，所以去订单路由表查询 订单路由表的订单id是分片键,这样就可以查询我们要的信息了
        //复合添加，一次添加多个表，所以要有事物的存在


        VoucherOrderRouter voucherOrderRouter = new VoucherOrderRouter();
        voucherOrderRouter.setId(snowflakeIdGenerator.nextId());
        voucherOrderRouter.setOrderId(voucherOrder.getId());
        voucherOrderRouter.setUserId(userId);
        voucherOrderRouter.setVoucherId(voucherOrder.getVoucherId());
        voucherOrderRouter.setCreateTime(LocalDateTimeUtil.now());
        voucherOrderRouter.setUpdateTime(LocalDateTimeUtil.now());
        voucherOrderRouterService.save(voucherOrderRouter);
        //订单存放redis  下单的时候在redis扣减，然后发送到kafka kafka进行消费  kafka消费和发送到kafka的过程中，都可能失败，这样（调用下单接口）需要前端一直轮询，确定订单是否真正的创建成功，
        //轮询的话，不可交给数据库否则增加数据库的压力，放进redis中，轮询过程中，不让用户再操作，
        //放在redis里面，前端不断轮询，轮询到了以后，这个订单才算真正的创建成功
        redisCache.set(RedisKeyBuild.createRedisKey(
                RedisKeyManage.DB_SECKILL_ORDER_KEY,messageBody.getOrderId()),
                voucherOrder,
                60, 
                TimeUnit.SECONDS
        );
        //对账日志，
        voucherReconcileLogService.saveReconcileLog(
                LogType.DEDUCT.getCode(),
                BusinessType.SUCCESS.getCode(),
                "order created",
                message
        );
        return true;
    }
    //根据订单id查询秒杀订单
    @Override
    public Long getSeckillVoucherOrder(GetVoucherOrderDto getVoucherOrderDto) {
       //先从redis里面查
        VoucherOrder voucherOrder =
                redisCache.get(RedisKeyBuild.createRedisKey(
                        RedisKeyManage.DB_SECKILL_ORDER_KEY, 
                        getVoucherOrderDto.getOrderId()), 
                        VoucherOrder.class);
        if (Objects.nonNull(voucherOrder)) {
            return voucherOrder.getId();//如果在redis中存在，直接返回订单id
        }
        //如果redis里面没有 （数据库刚生成还没有往redis里面放）查询数据库中   要查订单编号（而不是订单表，因为订单表用的是优惠券id和用户id进行的分片）所以先借助订单路由表，订单路由表用的是订单id进行的分片 先用订单id把路由表数据查出来
        VoucherOrderRouter voucherOrderRouter =  //不是查询订单表，查订单表的话分片键不是一个，所以要查订单表就只能全路由查询 效率慢了
                voucherOrderRouterService.lambdaQuery()
                        .eq(VoucherOrderRouter::getOrderId, getVoucherOrderDto.getOrderId())
                        .one();
        if (Objects.nonNull(voucherOrderRouter)) {//如果在订单路由表中存在，直接返回订单id
            return voucherOrderRouter.getOrderId();
        }
        return null;//如果数据库也空了，返回null
    }
    //根据优惠券id查询秒杀订单
    @Override
    public Long getSeckillVoucherOrderIdByVoucherId(GetVoucherOrderByVoucherIdDto getVoucherOrderByVoucherIdDto) {
        VoucherOrder voucherOrder = lambdaQuery()//查询条件：通过用户id和订单id，这时查表就可以是订单表
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .eq(VoucherOrder::getVoucherId, getVoucherOrderByVoucherIdDto.getVoucherId())
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode())//查询正常订单而不是取消订单
                .one();
        if (Objects.nonNull(voucherOrder)) {
            return voucherOrder.getId();
        }
        return null;
    }
    
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean cancel(CancelVoucherOrderDto cancelVoucherOrderDto) {
        //首先要查询订单
        VoucherOrder voucherOrder = lambdaQuery()
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())//第一个条件是我们的用户id
                .eq(VoucherOrder::getVoucherId, cancelVoucherOrderDto.getVoucherId())//第二个条件是优惠券id
                .eq(VoucherOrder::getStatus, OrderStatus.NORMAL.getCode())//第三个条件是订单状态是正常不是已取消
                .one();
        if (Objects.isNull(voucherOrder)) {//如果订单不存在，抛出异常
            throw new HmdpFrameException(BaseCode.SECKILL_VOUCHER_ORDER_NOT_EXIST);
        }
        //往下是查询订单秒杀优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.lambdaQuery()
                .eq(SeckillVoucher::getVoucherId, cancelVoucherOrderDto.getVoucherId())
                .one();
        if (Objects.isNull(seckillVoucher)) {//秒杀优惠券不存在，抛出异常
            throw new HmdpFrameException(BaseCode.SECKILL_VOUCHER_NOT_EXIST);
        }
        //更新订单状态
        //将订单状态改为取消状态   set我们的订单状态改为取消状态 set更新时间改为当前时间 条件一个是我们的用户id，一个是我们的优惠券id
        boolean updateResult = lambdaUpdate().set(VoucherOrder::getStatus, OrderStatus.CANCEL.getCode())
                .set(VoucherOrder::getUpdateTime, LocalDateTimeUtil.now())
                .eq(VoucherOrder::getUserId, UserHolder.getUser().getId())
                .eq(VoucherOrder::getVoucherId, cancelVoucherOrderDto.getVoucherId())
                .update();
        //结果拿到手之后，需要对账日志 在生成订单的时候有一个对账，在取消的时候也要加对账，
        //要加事务因为既有订单状态的更新，也有对账日志的更新，所以要加事务
        //对账日志
        long traceId = snowflakeIdGenerator.nextId();//生成对账日志id
        //用DTO而不是用实体是因为存放对账日志的方法也要给别的订单调用 用dto这种*********入参方式********，方便调用
        VoucherReconcileLogDto voucherReconcileLogDto = new VoucherReconcileLogDto();
        voucherReconcileLogDto.setOrderId(voucherOrder.getId());
        voucherReconcileLogDto.setUserId(voucherOrder.getUserId());
        voucherReconcileLogDto.setVoucherId(voucherOrder.getVoucherId());
        voucherReconcileLogDto.setDetail("cancel voucher order ");
        voucherReconcileLogDto.setBeforeQty(seckillVoucher.getStock());//取消订单之前库存
        voucherReconcileLogDto.setChangeQty(1);
        voucherReconcileLogDto.setAfterQty(seckillVoucher.getStock() + 1);//取消之后当前库存加一
        voucherReconcileLogDto.setTraceId(traceId);
        voucherReconcileLogDto.setLogType(LogType.RESTORE.getCode());//对账日志操作类型是恢复
        voucherReconcileLogDto.setBusinessType( BusinessType.CANCEL.getCode());//业务类型是取消订单
        boolean saveReconcileLogResult = voucherReconcileLogService.saveReconcileLog(voucherReconcileLogDto);//调用service保存对账日志
        //恢复优惠券库存 回滚库存
        boolean rollbackStockResult = seckillVoucherService.rollbackStock(cancelVoucherOrderDto.getVoucherId());
        //如果更新成功了，添加对账成功了，并且回滚库存也成功了，才返回true   -----》都满足了，把redis里面的优惠券库存也得回滚回去，
        Boolean result = updateResult && saveReconcileLogResult && rollbackStockResult;
        if (result) {//如果都满足了，把redis里面的优惠券库存也得回滚回去，
            redisVoucherData.rollbackRedisVoucherData(
                    SeckillVoucherOrderOperate.YES,//回滚要删除订单记录
                    traceId,
                    voucherOrder.getVoucherId(),//优惠券id
                    voucherOrder.getUserId(),
                    voucherOrder.getId(),
                    seckillVoucher.getStock(),
                    1,
                    seckillVoucher.getStock() + 1
            );
            //将自己移除订阅队列，因为取消订单，所以要从订阅队列中移除自己
            redisCache.delForHash(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_STATUS_TAG_KEY, 
                    cancelVoucherOrderDto.getVoucherId()),
                    String.valueOf(voucherOrder.getUserId()));
            //将自己在每日top买家统计的zset中减一
            Voucher voucher = voucherService.getById(voucherOrder.getVoucherId());
            if (Objects.nonNull(voucher)) {
                String day = voucherOrder.getCreateTime().format(DateTimeFormatter.BASIC_ISO_DATE);
               //构建redis的键
                RedisKeyBuild dailyKey = RedisKeyBuild.createRedisKey(
                        RedisKeyManage.SECKILL_SHOP_TOP_BUYERS_DAILY_TAG_KEY,
                        voucher.getShopId(),
                        day
                );
                //减一
                redisCache.incrementScoreForSortedSet(dailyKey, String.valueOf(voucherOrder.getUserId()), -1.0);
            }
            //回滚成功后，尝试将资格自动分配给订阅队列中最早的未购用户
            //取消的话，把这个资格给已经订阅的用户使用 越早订阅的用户应该越早被提醒领取到
            try {//为了不影响主流程，应该捕获住，执行失败了，问题也不是特别大  要是不捕获的话，报异常，上面取消的话，就又回滚回去了
                autoIssueVoucherToEarliestSubscriber(
                        voucherOrder.getVoucherId(),//优惠券id
                        voucherOrder.getUserId()//排除用户id  当前取消的用户id要剔除掉
                );
            } catch (Exception e) {
                log.warn("自动发券失败，voucherId={}, err=\n{}", voucherOrder.getVoucherId(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 回滚后自动发券，挑选订阅zset中按加入时间最早的未购用户，执行lua扣减并下发kafka消息
     * 说明：不修改订阅集合与状态，成功下单后用户将出现在已购集合，状态查询返回SUCCESS
     *       为避免重复，筛选时，排除掉已购用户与当前取消用户
     *       采用范围批量读取前N条并按score最小选取候选，避免由于Set去序导致的顺序丢失
     * @param voucherId
     * @param excludeUserId
     * @return
     */
    @Override
    public boolean autoIssueVoucherToEarliestSubscriber(final Long voucherId, final Long excludeUserId) {
        //先查取优惠券信息 查询秒杀优惠券
        SeckillVoucherFullModel seckillVoucherFullModel = seckillVoucherService.queryByVoucherId(voucherId);
       //判断参数， 用于确保秒杀券的完整信息（seckillVoucherFullModel）及其关键属性（开始时间和结束时间）不为空
        if (Objects.isNull(seckillVoucherFullModel)
                || 
                Objects.isNull(seckillVoucherFullModel.getBeginTime()) 
                ||
                Objects.isNull(seckillVoucherFullModel.getEndTime())) {
            return false;//秒杀券信息不完整，不能自动发券 直接返回
        }
        //需要再加载一次缓存，防止修改数据或者对账执行将redis中的库存删掉
        // （对账执行也会把缓存删掉，要对账执行进行补偿的话，补偿策略是把缓存里面的redis里面库存缓存给删掉，这样的话后续用户在下单的时候，一看缓存里面没有这库存，又去数据库加载）
        seckillVoucherService.loadVoucherStock(voucherId);
       //在订阅ZSET中查找最早且未购的候选用户（排除当前取消的用户）
        String candidateUserIdStr = findEarliestCandidate(voucherId, excludeUserId);
        if (StrUtil.isBlank(candidateUserIdStr)) {//如果查询为空，说明没有符合条件的候选用户 直接返回
            return false;
        }
        //对候选用户执行Lua扣减与消息下发，并在成功后，移除候选的ZSET位置
        return issueToCandidate(voucherId, candidateUserIdStr, seckillVoucherFullModel);
    }

    /**
     * 按分数升序使用 LIMIT 递增分页 找出第一个符合条件的候选用户
     * 条件：排除当前取消的用户，且未在已购集合中
     * @param voucherId
     * @param excludeUserId
     * @return
     */
    //在订阅ZSET中查找最早且未购的候选用户（排除当前取消的用户）  参数：优惠券id，排除用户id
    private String findEarliestCandidate(final Long voucherId, final Long excludeUserId) {
        //订阅Zset key（按加入时间的score存储）
        RedisKeyBuild subscribeZSetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY, voucherId);
       //已购用户集合key
        RedisKeyBuild purchasedSetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId);
        //每页仅仅取一个成员
        final long pageCount = 1L;
        //从最早的成员开始递增偏移
        long offset = 0L;
       //循环分页，直到找到符合者或者没有更多的成员
        while (true) {
            Set<ZSetOperations.TypedTuple<String>> page = redisCache.rangeByScoreWithScoreForSortedSet(
                    subscribeZSetKey,
                    Double.NEGATIVE_INFINITY,  //分数的最小值
                    Double.POSITIVE_INFINITY,  //分数的最大值
                    offset,
                    pageCount,
                    String.class
            );
            //判断，如果没有更多的成员，直接返回null
            if (CollectionUtil.isEmpty(page)) {
                return null;
            }
            //取出当前页的唯一成员
            ZSetOperations.TypedTuple<String> tuple = page.iterator().next();//使用迭代器获取唯一成员
            //判断，如果没有成员，或者成员的值为空，直接跳过
            if (Objects.isNull(tuple) || Objects.isNull(tuple.getValue())) {
                offset++;    //当前值为空的话再循环再查下一个，但是循环再查的话偏移量要变大要++
                continue;//不用再继续下边的执行
            }
            //如果值有的话，就开始取用户id
            String uidStr = tuple.getValue();
            //判断，如果用户id为空，直接跳过
            if (StrUtil.isBlank(uidStr)) {
                offset++;
                continue;
            }
            //判断，如果用户id等于排除用户id，直接跳过  排除id不为空，并且用户id等于排除用户id   排除已经取消的用户
            if (Objects.nonNull(excludeUserId) && Objects.equals(uidStr, String.valueOf(excludeUserId))) {
                offset++;
                continue;
            }
            //判断是否已经购买 如果已经购买，直接跳过
            Boolean purchased = redisCache.isMemberForSet(purchasedSetKey, uidStr);
            if (BooleanUtil.isTrue(purchased)) {
                offset++;
                continue;
            }
            //找到符合条件的候选id，直接返回
            return uidStr;
        }
    }

    /**
     * 对候选用户执行lua扣减于消息下发 ，并从订阅ZSET中移除该用户（成功后）
     * @param voucherId 优惠券id
     * @param candidateUserIdStr 候选用户id（筛选出来要通知的用户id）
     * @param seckillVoucherFullModel 优惠券完整实体参数
     * @return
     */
    private boolean issueToCandidate(final Long voucherId, 
                                     final String candidateUserIdStr, 
                                     final SeckillVoucherFullModel seckillVoucherFullModel) {
       //将候选用户id转换为long类型
        Long candidateUserId = Long.valueOf(candidateUserIdStr);
        try {//首先要验证会员等级，会员等级允许购买，允许领取
            verifyUserLevel(seckillVoucherFullModel, candidateUserId);
        } catch (Exception e) {
            log.info("候选用户不满足人群规则，自动发券跳过。voucherId={}, userId={}", voucherId, candidateUserId);
            return false;
        }
        //构建lua脚本keys（库存，已购集合，trace日志集合）
        List<String> keys = buildSeckillKeys(voucherId);
        long orderId = snowflakeIdGenerator.nextId();
        long traceId = snowflakeIdGenerator.nextId();
        //构建lua脚本的入参数据
        String[] args = buildSeckillArgs(voucherId, candidateUserIdStr, seckillVoucherFullModel, orderId, traceId);
        //执行秒杀扣减lua脚本
        SeckillVoucherDomain domain = seckillVoucherOperate.execute(keys, args);//拿到上下文
        if (!Objects.equals(domain.getCode(), BaseCode.SUCCESS.getCode())) {//判断是否执行成功了 Code码是否是我们的成功码
            log.info("自动发券Lua扣减失败，code={}, voucherId={}, userId={}", domain.getCode(), voucherId, candidateUserId);
            return false; //如果不成功，直接提前结束
        }
        //如果扣减成功，要发送kafka，构建kafka消息
        SeckillVoucherMessage message = new SeckillVoucherMessage(
                candidateUserId,
                voucherId,
                orderId,
                traceId,
                domain.getBeforeQty(),
                domain.getDeductQty(),
                domain.getAfterQty(),
                Boolean.TRUE   //选true，要通知用户  当用户在kafka消费端成功把优惠券领取了，有订单了以后，有这个参数决定是否主动的通知用户
                                //如果正常购买，用户自己主动点击的秒杀优惠券，就不会通知用户 到时候前端轮询就会告诉你是否领取成功
        );
        //发送kafka消息
        seckillVoucherProducer.sendPayload(
                SpringUtil.getPrefixDistinctionName() + "-" + SECKILL_VOUCHER_TOPIC,
                message
        );
        //不在此处移除订阅zset成员，也不记录成功日志
        //订阅ZSET的移除应在kafka消费端成功并创建订单了以后，再移除订阅zset成员  避免发送或消费失败导致丢失资格
        return true;
    }
    //构建秒杀优惠券的redis key
    private List<String> buildSeckillKeys(final Long voucherId) {
        String stockKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId).getRelKey();//库存key
        String userKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId).getRelKey();//已购用户id key
        String traceKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY, voucherId).getRelKey();//轨迹key redis扣减日志
        return ListUtil.of(stockKey, userKey, traceKey);//将三个key构建成一个集合
    }
    //构建秒杀优惠券的lua脚本参数 数据
    private String[] buildSeckillArgs(final Long voucherId,
                                      final String userIdStr,
                                      final SeckillVoucherFullModel seckillVoucherFullModel,//秒杀优惠券的数据
                                      final long orderId,//订单id
                                      final long traceId) {//关联id
        String[] args = new String[9];
        args[0] = voucherId.toString();
        args[1] = userIdStr;
        args[2] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getBeginTime()));
        args[3] = String.valueOf(LocalDateTimeUtil.toEpochMilli(seckillVoucherFullModel.getEndTime()));
        args[4] = String.valueOf(seckillVoucherFullModel.getStatus());
        args[5] = String.valueOf(orderId);
        args[6] = String.valueOf(traceId);
        args[7] = String.valueOf(LogType.DEDUCT.getCode());
        args[8] = String.valueOf(computeTtlSeconds(seckillVoucherFullModel));//过期时间
        return args;
    }
    //计算秒杀优惠券的过期时间
    private long computeTtlSeconds(final SeckillVoucherFullModel seckillVoucherFullModel) {
        long secondsUntilEnd = Duration.between(LocalDateTimeUtil.now(), seckillVoucherFullModel.getEndTime()).getSeconds();
        return Math.max(1L, secondsUntilEnd + Duration.ofDays(1).getSeconds());//为了避免正好失效，在执行逻辑中，优惠券正好到活动的结束时间再加一天
    }

    /*
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );
        int r = result.intValue();
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2.为0 ，有购买资格，把下单信息保存到阻塞队列
        VoucherOrder voucherOrder = new VoucherOrder();
        // 2.3.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
        // 2.6.放入阻塞队列
        orderTasks.add(voucherOrder);
        // 3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy()
        // 4.返回订单id
        return Result.ok(orderId);
    }*/
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try {
            // 获取代理对象（事务）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/


    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();

        synchronized (userId.toString().intern()) {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0  乐观锁
                    .update();
            if (!success) {
                // 扣减失败
                return Result.fail("库存不足！");
            }


            // 7.创建订单（如果成功的话）
            VoucherOrder voucherOrder = new VoucherOrder();
            // 7.1.订单id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            // 7.2.用户id
            voucherOrder.setUserId(userId);
            // 7.3.代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);

            // 7.返回订单id
            return Result.ok(orderId);
        }
    }*/

}
