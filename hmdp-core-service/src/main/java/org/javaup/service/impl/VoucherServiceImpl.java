package org.javaup.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.cache.SeckillVoucherCacheInvalidationPublisher;
import org.javaup.context.DelayQueueContext;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.delay.message.DelayedVoucherReminderMessage;
import org.javaup.dto.DelayVoucherReminderDto;
import org.javaup.dto.Result;
import org.javaup.dto.SeckillVoucherDto;
import org.javaup.dto.UpdateSeckillVoucherDto;
import org.javaup.dto.UpdateSeckillVoucherStockDto;
import org.javaup.dto.VoucherDto;
import org.javaup.dto.VoucherSubscribeBatchDto;
import org.javaup.dto.VoucherSubscribeDto;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.Voucher;
import org.javaup.enums.BaseCode;
import org.javaup.enums.StockUpdateType;
import org.javaup.enums.SubscribeStatus;
import org.javaup.exception.HmdpFrameException;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.mapper.VoucherMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IVoucherOrderService;
import org.javaup.service.IVoucherService;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.utils.UserHolder;
import org.javaup.vo.GetSubscribeStatusVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_VOUCHER;
import static org.javaup.constant.Constant.DELAY_VOUCHER_REMINDER;
import static org.javaup.constant.DistributedLockConstants.UPDATE_SECKILL_VOUCHER_LOCK;
import static org.javaup.constant.DistributedLockConstants.UPDATE_SECKILL_VOUCHER_STOCK_LOCK;
import static org.javaup.service.impl.VoucherOrderServiceImpl.SECKILL_ORDER_EXECUTOR;
import static org.javaup.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 优惠券 接口实现
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    
    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;//布隆过滤器
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private SeckillVoucherCacheInvalidationPublisher seckillVoucherCacheInvalidationPublisher;
    
    @Resource
    private IVoucherOrderService voucherOrderService;
    
    @Resource
    private DelayQueueContext delayQueueContext;//延迟队列上下文

    //Spring 框架的配置注入，用于读取秒杀提醒的提前时间配置 用途：设置秒杀开始前多久向用户发送提醒通知（如短信、APP推送等）。
    //默认值：120 秒（即 2 分钟），表示秒杀开始前 2 分钟发送提醒。
    @Value("${seckill.reminder.ahead.seconds:120}")
    private long reminderAheadSeconds;
    //添加优惠券 普通优惠券
    @Override
    public Long addVoucher(VoucherDto voucherDto) {
        Voucher one = lambdaQuery().orderByDesc(Voucher::getId).one();
        long newId = 1L;
        if (one != null) {
            newId = one.getId() + 1;
        }
        Voucher voucher = new Voucher();//先创建对象
        BeanUtil.copyProperties(voucherDto, voucher);
        voucher.setId(newId);

        save(voucher);
        bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_VOUCHER).add(voucher.getId().toString());//把优惠券id放在布隆过滤器里面
        return voucher.getId();//返回优惠券id
    }
    //通过店铺查询
    @Override
    public Result<List<Voucher>> queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long addSeckillVoucher(SeckillVoucherDto seckillVoucherDto) {
        //return doAddSeckillVoucherV1(seckillVoucherDto);
        return doAddSeckillVoucherV2(seckillVoucherDto);
    }
    
    @Override
    //读写锁
    @ServiceLock(lockType= LockType.Write,name = UPDATE_SECKILL_VOUCHER_LOCK,keys = {"#updateSeckillVoucherDto.voucherId"})
    @Transactional(rollbackFor = Exception.class)
    public void updateSeckillVoucher(UpdateSeckillVoucherDto updateSeckillVoucherDto) {
        Long voucherId = updateSeckillVoucherDto.getVoucherId();
        //更新tb_voucher的非空字段
        boolean updatedVoucher = false;
        var voucherUpdate = this.lambdaUpdate().eq(Voucher::getId, voucherId);
        if (updateSeckillVoucherDto.getTitle() != null) {
            voucherUpdate.set(Voucher::getTitle, updateSeckillVoucherDto.getTitle());
            updatedVoucher = true;
        }
        if (updateSeckillVoucherDto.getSubTitle() != null) {
            voucherUpdate.set(Voucher::getSubTitle, updateSeckillVoucherDto.getSubTitle());
            updatedVoucher = true;
        }
        if (updateSeckillVoucherDto.getRules() != null) {
            voucherUpdate.set(Voucher::getRules, updateSeckillVoucherDto.getRules());
            updatedVoucher = true;
        }
        if (updateSeckillVoucherDto.getPayValue() != null) {
            voucherUpdate.set(Voucher::getPayValue, updateSeckillVoucherDto.getPayValue());
            updatedVoucher = true;
        }
        if (updateSeckillVoucherDto.getActualValue() != null) {
            voucherUpdate.set(Voucher::getActualValue, updateSeckillVoucherDto.getActualValue());
            updatedVoucher = true;
        }
        if (updateSeckillVoucherDto.getType() != null) {
            voucherUpdate.set(Voucher::getType, updateSeckillVoucherDto.getType());
            updatedVoucher = true;
        }
        if (updateSeckillVoucherDto.getStatus() != null) {
            voucherUpdate.set(Voucher::getStatus, updateSeckillVoucherDto.getStatus());
            updatedVoucher = true;
        }
       //判断确实有字段，进行更新，   上面更新的是普通优惠券  下面更新的是秒杀优惠券信息
        if (updatedVoucher) {
            voucherUpdate.set(Voucher::getUpdateTime, LocalDateTimeUtil.now()).update();
        }


        //更新tb_seckill_voucher表的非空字段（仅仅时间相关）
        
        boolean updatedSeckill = false;
        var seckillUpdate = seckillVoucherService.lambdaUpdate().eq(SeckillVoucher::getVoucherId, voucherId);
        if (updateSeckillVoucherDto.getBeginTime() != null) {
            seckillUpdate.set(SeckillVoucher::getBeginTime, updateSeckillVoucherDto.getBeginTime());
            updatedSeckill = true;
        }
        if (updateSeckillVoucherDto.getEndTime() != null) {
            seckillUpdate.set(SeckillVoucher::getEndTime, updateSeckillVoucherDto.getEndTime());
            updatedSeckill = true;
        }

        //受众规则字段更新
        if (updateSeckillVoucherDto.getAllowedLevels() != null) {
            seckillUpdate.set(SeckillVoucher::getAllowedLevels, updateSeckillVoucherDto.getAllowedLevels());
            updatedSeckill = true;
        }
        if (updateSeckillVoucherDto.getMinLevel() != null) {
            seckillUpdate.set(SeckillVoucher::getMinLevel, updateSeckillVoucherDto.getMinLevel());
            updatedSeckill = true;
        }

        if (updatedSeckill) {
            seckillUpdate.set(SeckillVoucher::getUpdateTime, LocalDateTimeUtil.now()).update();
        }
        //更新后清理缓存，等待读路径按新数据重建缓存
        if (updatedVoucher || updatedSeckill) {//两个有一个是true说明更新了
            voucherUpdate.update();       //多级缓存，每个实例有自己的本地缓存，在这将本地缓存清除的话，只可以清除自己的实例，其他实例是清不到的，，每个实例是自己的Jvm内存，通知其他实例接收到这个消息，消息接收到之后，清除自己的本地缓存，用消息队列，发送方式是广播消费，这个实例发送这个消息，其他所有实例都监听到这个消息，通知自己的本地缓存进行清除，
                                        //发送者先将自己的本地缓存清掉，再发送广播消息
            seckillUpdate.update();
            seckillVoucherCacheInvalidationPublisher.publishInvalidate(voucherId, "update");
        }
    }
    
    @Override
    @ServiceLock(lockType= LockType.Write,name = UPDATE_SECKILL_VOUCHER_STOCK_LOCK,keys = {"#updateSeckillVoucherDto.voucherId"})//分布式锁的组件  写锁   注解是用AOP实现的，所以执行方法必须是spring的代理对象
    @Transactional(rollbackFor = Exception.class)
    public void updateSeckillVoucherStock(UpdateSeckillVoucherStockDto updateSeckillVoucherDto) {//修改秒杀优惠券库存的方法
        SeckillVoucher seckillVoucher = seckillVoucherService.lambdaQuery()
                .eq(SeckillVoucher::getVoucherId, updateSeckillVoucherDto.getVoucherId()).one();//用于根据传入的voucherid查询对应的秒杀券信息
        if (Objects.isNull(seckillVoucher)) {//如果不存在抛出异常
            throw new HmdpFrameException(BaseCode.SECKILL_VOUCHER_NOT_EXIST);
        }
        Integer oldStock = seckillVoucher.getStock();//旧的库存
        Integer oldInitStock = seckillVoucher.getInitStock();//旧的初始化库存
        Integer newInitStock = updateSeckillVoucherDto.getInitStock();//获得我们参数的库存
        int changeStock = newInitStock - oldInitStock;//新的初始库存减去旧的初始库存就是我们要改变的库存的量（因为可能加可能减）
        if (changeStock == 0) {
            return;
        }
        int newStock = oldStock + changeStock;//新的库存等于旧的库存加上要改变的库存
        if (newStock < 0 ) {//如果新的库存小于0，说明库存不足 抛出异常
            throw new HmdpFrameException(BaseCode.AFTER_SECKILL_VOUCHER_REMAIN_STOCK_NOT_NEGATIVE_NUMBER);
        }
        StockUpdateType stockUpdateType = StockUpdateType.INCREASE;//在枚举类型里，库存修改的类型，是增加还是减少
        if (changeStock < 0) {
            stockUpdateType = StockUpdateType.DECREASE;
        }


        /**
         * 数据库库存更新
         */
        seckillVoucherService.lambdaUpdate()//数据库的钢芯
                .set(SeckillVoucher::getStock, newStock)//新的库存
                .set(SeckillVoucher::getInitStock, newInitStock)//新的初始库存
                .set(SeckillVoucher::getUpdateTime, LocalDateTimeUtil.now())//更新时间
                .eq(SeckillVoucher::getVoucherId, seckillVoucher.getVoucherId())//根据voucherid查询  上面的传给我的条件
                .update();


        /**
         * redis库存更新更新
         */
        String oldRedisStockStr = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY,
                updateSeckillVoucherDto.getVoucherId()), String.class);//先查有没有redis库存    旧的redis库存
        Integer newRedisStock = null;
        if (StrUtil.isBlank(oldRedisStockStr)) {//如果旧的redis库存是空的话，直接往redis里面放新的初始库存（redis没有的话，没有进行扣减，是开始的初始库存数量）
            redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY,
                    updateSeckillVoucherDto.getVoucherId()),String.valueOf(newInitStock));
        }else {//如果redis有库存，就根据要改变的库存的量，更新redis库存
            int oldRedisStock = Integer.parseInt(oldRedisStockStr);//旧库存数量拿到手
            newRedisStock = oldRedisStock + changeStock;
            if (newRedisStock < 0 ) {
                throw new HmdpFrameException(BaseCode.AFTER_SECKILL_VOUCHER_REMAIN_STOCK_NOT_NEGATIVE_NUMBER);
            }
            //往redsi里面放入
            redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY,
                    updateSeckillVoucherDto.getVoucherId()),String.valueOf(newRedisStock));
        }
        log.info("修改库存成功！修改库存类型：{},修改前：数据库初始库存：{},redis旧库存：{},修改后：数据库初始库存：{},redis新库存：{}",
                stockUpdateType.getMsg(),
                oldInitStock,
                StrUtil.isBlank(oldRedisStockStr) ? null : oldRedisStockStr,
                newInitStock,
                newRedisStock
                );
        //如果是增加库存,尝试将资格自动分配给订阅队列中最早的未购用户（如果是增加库存，将增加的优惠券库存分配给已经订阅到券提醒的用户，之前订单取消的时候有此功能，在这里增加库存理应也有）
        if (stockUpdateType == StockUpdateType.INCREASE) {//voucherOrderService.autoIssueVoucherToEarliestSubscriber() 方法，应该异步执行，因为分配这个动作不属于主流程，，是异步的，用voucherorderserviceimpl的异步线程池执行
           // SECKILL_ORDER_EXECUTOR.execute(() -> voucherOrderService
                   // .autoIssueVoucherToEarliestSubscriber(seckillVoucher.getVoucherId(),null));//传的参数是优惠券和排除的用户id

            SECKILL_ORDER_EXECUTOR.execute(() -> {//加了大括号的lambda表达式
                voucherOrderService.autoIssueVoucherToEarliestSubscriber(seckillVoucher.getVoucherId(), null);
            });
        }
    }

    //到券提醒
    @Override
    public void subscribe(final VoucherSubscribeDto voucherSubscribeDto) {
        Long voucherId = voucherSubscribeDto.getVoucherId();//首先是拿到优惠券id
        Long userId = UserHolder.getUser().getId();//然后拿到用户id
        String userIdStr = String.valueOf(userId);//用户id变为字符串类型，后续根据用户id的字符串形式来判断

        //优惠券被秒杀过，那么他肯定是放在缓存里面的，扣减是在redis里面扣减    如果没有秒杀过的话或者是进行修改库存了，有可能不在redis里面，因为当我们进行优惠券修改库存的话，会直接把我们的redis里面的信息给清除掉
        //接下来是订阅，适合用redis来实现，订阅属于附加功能，不是必须的核心功能，订阅具有公平性，先订阅的先被通知到 在redis里面数据结构是sorted set（带分数的集合--->用时间戳来当作分数，分数越小的，他的优先级越高）

       //在获取优惠券的过期时间要分为两个步骤来获取：1.先从redis来获取过期时间如果redis不存在的话，查数据库，从数据库里来获取优惠券的过期时间，

        //计算统一 TTL（过期秒数）
        //先在redis里面去查
        Long ttlSeconds = redisCache.getExpire(    //向redis里面去放，有过期时间，用秒杀优惠券的结束活动时间来当作我们的过期时间
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId),
                TimeUnit.SECONDS
        );
        //判断redis里面是否有过期时间，如果没有，就从数据库里面来获取
        if (Objects.isNull(ttlSeconds) || ttlSeconds <= 0) {
           //从数据库里面来获取优惠券的过期时间
            SeckillVoucher sv = seckillVoucherService.lambdaQuery()
                    .eq(SeckillVoucher::getVoucherId, voucherId)
                    .one();
            if (Objects.nonNull(sv) && Objects.nonNull(sv.getEndTime())) {
                ttlSeconds = Math.max(//如果优惠券的结束时间在当前时间之前，就用1秒，否则就用优惠券的结束时间减当前时间的秒数
                        LocalDateTimeUtil.between(LocalDateTimeUtil.now(), sv.getEndTime()).getSeconds(),
                        1L
                );
            } else {//数据库查出来如果是空的，设置一个默认值
                ttlSeconds = 3600L;
            }
        }
        //检查是否已购买，判断用户是否在 SECKILL_USER_TAG_KEY:{voucherId} 集合中（已购集合）
        boolean purchased = Boolean.TRUE.equals(redisCache.isMemberForSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId),
                userIdStr
        ));
        //根据用户是否已经购买，放入我们的订阅集合里面
        RedisKeyBuild statusKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_STATUS_TAG_KEY, voucherId);
        if (purchased) {//如果已经购买了，往里面放已经购买的状态。
            redisCache.putHash(statusKey, userIdStr, SubscribeStatus.SUCCESS.getCode(), ttlSeconds, TimeUnit.SECONDS);//对应已经买了的状态
            redisCache.expire(statusKey, ttlSeconds, TimeUnit.SECONDS);
            return;//已经购买了，终止不再执行
        }

        // 加入订阅集合（SET），幂等
        //去重，如果用户已经订阅了，就不重复订阅
        RedisKeyBuild setKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_USER_TAG_KEY, voucherId);
        Long added = redisCache.addForSet(setKey, userIdStr);//如果用户没有订阅，就加入订阅集合   幂等 set去重的，只有第一次往里加的时候added才会取到值
        redisCache.expire(setKey, ttlSeconds, TimeUnit.SECONDS);//设置过期时间



        //获取到     真正的订阅的队列      就是我们的zset（在这里面进行订阅的）  其他的集合或者数据库只是用来辅助我们的订阅队列的
        // 加入订阅队列（ZSET），仅首次加入时写入顺序分数（时间）
        //把键先构建出来
        RedisKeyBuild zsetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY, voucherId);
        if (Objects.nonNull(added) && added > 0) {//如果不为空且大于零，说明他是第一次添加，就加入订阅队列
            redisCache.addForSortedSet(zsetKey, userIdStr, (double) System.currentTimeMillis(), ttlSeconds, TimeUnit.SECONDS);
        } else {//如果不是首次，再次设置过期时间就可以了
            // 已存在则仅对齐TTL
            redisCache.expire(zsetKey, ttlSeconds, TimeUnit.SECONDS);
        }




        //还得再判断一下是不是其他状态，（既不是success状态，）
        // 更新订阅状态为 SUBSCRIBED（如已是 SUCCESS 则不降级）
        //从订阅队列里面把数据取出来
        Integer prev = redisCache.getForHash(statusKey, userIdStr, Integer.class);
        if (!SubscribeStatus.SUCCESS.getCode().equals(prev)) {//如果状态不是success的话状态变更为订阅状态
            redisCache.putHash(statusKey, userIdStr, SubscribeStatus.SUBSCRIBED.getCode(), ttlSeconds, TimeUnit.SECONDS);
        }
        redisCache.expire(statusKey, ttlSeconds, TimeUnit.SECONDS);//设置一下过期时间
    }





    //取消到券提醒的订阅  从订阅变成取消订阅
    //参数先搞到手，再获得订阅队列，set集合
    @Override
    public void unsubscribe(final VoucherSubscribeDto voucherSubscribeDto) {
        Long voucherId = voucherSubscribeDto.getVoucherId();
        Long userId = UserHolder.getUser().getId();
        String userIdStr = String.valueOf(userId);
//下面的三个key
        RedisKeyBuild setKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_USER_TAG_KEY, voucherId);//获取到set集合
        RedisKeyBuild zsetKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY, voucherId);//真正的zset订阅队列
        RedisKeyBuild statusKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_STATUS_TAG_KEY, voucherId);//订阅的状态
        
        // 从订阅集合与队列移除
        redisCache.removeForSet(setKey, userIdStr);
        redisCache.delForSortedSet(zsetKey, userIdStr);
        
        // 已购则维持 SUCCESS，否则置为 UNSUBSCRIBED
        //在我们已经购买的那个集合里面有没有，就是在seckillVoucher.lua中的下单redis.call（"sadd”，seckillUserKey，userId）
        boolean purchased = Boolean.TRUE.equals(redisCache.isMemberForSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId),
                userIdStr
        ));
        //算一下过期时间，在redis里面去拿优惠券的过期时间
        Long ttlSeconds = redisCache.getExpire(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId),
                TimeUnit.SECONDS
        );
        //到取消订阅这个方法，redis里面肯定是有过期时间的，但为了谨慎小心加上一个默认值，
        if (ttlSeconds == null || ttlSeconds <= 0) {
            ttlSeconds = 3600L;
        }
        //往我们的hash里面去放  看hash里面是不是已经有了如果已经有了，那么还是成功状态，如果没有的话，把它变成未定阅状态
        redisCache.putHash(
                statusKey, 
                userIdStr,
                purchased ? SubscribeStatus.SUCCESS.getCode() : SubscribeStatus.UNSUBSCRIBED.getCode(),//判断是不是购买了，是的话就是成功的状态，如果不是的话，就是未订阅的状态
                ttlSeconds, TimeUnit.SECONDS);
        redisCache.expire(statusKey, ttlSeconds, TimeUnit.SECONDS);
    }
    //单独查询到券提醒状态  从订阅变成取消订阅
    @Override
    public Integer getSubscribeStatus(final VoucherSubscribeDto voucherSubscribeDto) {
        Long voucherId = voucherSubscribeDto.getVoucherId();
        Long userId = UserHolder.getUser().getId();
        String userIdStr = String.valueOf(userId);

        RedisKeyBuild statusKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_STATUS_TAG_KEY, voucherId);
       //去redis中把状态取出来
        Integer st = redisCache.getForHash(statusKey, userIdStr, Integer.class);
        if (st != null) {//如果存在的话，直接返回就行
            return st;
        }
        //如果不存在。就真正的去判断是否有   在redis中去取购买记录 在秒杀的时候 redis里面有购买记录
        boolean purchased = Boolean.TRUE.equals(redisCache.isMemberForSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId),
                userIdStr
        ));
        if (purchased) {//如果已经购买了，要更新一下状态 把过期时间搞出来 往redis里面去放
            Long ttlSeconds = redisCache.getExpire(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId),
                    TimeUnit.SECONDS
            );
            //如果还是空或者小于零的话，给一个默认值
            if (ttlSeconds == null || ttlSeconds <= 0) {
                ttlSeconds = 3600L;
            }
            //往redis里面去放  看hash里面是不是已经有了如果已经有了，那么还是成功状态，如果没有的话，把它变成订阅状态
            //因为已经购买了，所以是成功的状态
            redisCache.putHash(statusKey, userIdStr, SubscribeStatus.SUCCESS.getCode(), ttlSeconds, TimeUnit.SECONDS);
            redisCache.expire(statusKey, ttlSeconds, TimeUnit.SECONDS);
            return SubscribeStatus.SUCCESS.getCode();
        }
        //判断如果是true的话表明其订阅了，否则表明未订阅
        boolean inQueue = Boolean.TRUE.equals(redisCache.isMemberForSet(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_USER_TAG_KEY, voucherId),
                userIdStr
        ));
        return inQueue ? SubscribeStatus.SUBSCRIBED.getCode() : SubscribeStatus.UNSUBSCRIBED.getCode();
    }
    
    @Override
    public List<GetSubscribeStatusVo> getSubscribeStatusBatch(final VoucherSubscribeBatchDto voucherSubscribeBatchDto) {
        Long userId = UserHolder.getUser().getId();
        String userIdStr = String.valueOf(userId);
        List<GetSubscribeStatusVo> res = new ArrayList<>();
        //增强for循环
        for (Long voucherId : voucherSubscribeBatchDto.getVoucherIdList()) {
            // 优先使用HASH缓存
            RedisKeyBuild statusKey = RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_STATUS_TAG_KEY, voucherId);
            Integer st = redisCache.getForHash(statusKey, userIdStr, Integer.class);
            if (st != null) {
                res.add(new GetSubscribeStatusVo(voucherId, st));
                continue;//该次循环不在往下执行了
            }
            //
            boolean purchased = Boolean.TRUE.equals(redisCache.isMemberForSet(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY, voucherId),
                    userIdStr
            ));
            if (purchased) {
                Long ttlSeconds = redisCache.getExpire(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId),
                        TimeUnit.SECONDS
                );
                if (ttlSeconds == null || ttlSeconds <= 0) {
                    ttlSeconds = 3600L;
                }
                redisCache.putHash(statusKey, userIdStr, SubscribeStatus.SUCCESS.getCode(), ttlSeconds, TimeUnit.SECONDS);
                redisCache.expire(statusKey, ttlSeconds, TimeUnit.SECONDS);
                res.add(new GetSubscribeStatusVo(voucherId, SubscribeStatus.SUCCESS.getCode()));
                continue;
            }
            //如果是true的话，说明已经订阅了，把订阅的订单添加进去 要是false说明没有订阅 把没有订阅的订单状态返回回去
            boolean inQueue = Boolean.TRUE.equals(redisCache.isMemberForSet(
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_USER_TAG_KEY, voucherId),
                    userIdStr
            ));
            res.add(new GetSubscribeStatusVo(voucherId, inQueue ? SubscribeStatus.SUBSCRIBED.getCode() : SubscribeStatus.UNSUBSCRIBED.getCode()));
        }
        return res;
    }
    //V1版本是添加，然后将（库存信息和优惠券信息？）放到redis里面
    public Long doAddSeckillVoucherV1(SeckillVoucherDto seckillVoucherDto) {
        VoucherDto voucherDto = new VoucherDto();
        BeanUtil.copyProperties(seckillVoucherDto, voucherDto);
        Long voucherId = addVoucher(voucherDto);
        //保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setId(snowflakeIdGenerator.nextId());
        seckillVoucher.setVoucherId(voucherId);
        seckillVoucher.setStock(seckillVoucherDto.getStock());
        seckillVoucher.setBeginTime(seckillVoucherDto.getBeginTime());
        seckillVoucher.setEndTime(seckillVoucherDto.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        //保存秒杀库存到Redis中
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, seckillVoucher.getStock().toString());
       //如果数据库查询不是空的，将秒杀优惠券信息写入缓存，TTL为距离结束时间的秒数
        long ttlSeconds = Math.max(
                LocalDateTimeUtil.between(LocalDateTimeUtil.now(), seckillVoucher.getEndTime()).getSeconds(),
                1L
        );
        seckillVoucher.setStock(null);
        redisCache.set(
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId),
                seckillVoucher,
                ttlSeconds,
                TimeUnit.SECONDS
        );
        return voucherId;
    }
    
    public Long doAddSeckillVoucherV2(SeckillVoucherDto seckillVoucherDto) {
       //保存优惠券  （添加秒杀优惠券，普通优惠券也得有，先调用普通优惠券的创造方法）
        VoucherDto voucherDto = new VoucherDto();
        BeanUtil.copyProperties(seckillVoucherDto, voucherDto);
        Long voucherId = addVoucher(voucherDto);
        //保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setId(snowflakeIdGenerator.nextId());
        seckillVoucher.setVoucherId(voucherId);
        seckillVoucher.setInitStock(seckillVoucherDto.getStock());//初始库存
        seckillVoucher.setStock(seckillVoucherDto.getStock());//要扣减的库存   一开始初始的库存和要扣减的库存肯定是一样的
        seckillVoucher.setBeginTime(seckillVoucherDto.getBeginTime());//活动开始时间
        seckillVoucher.setEndTime(seckillVoucherDto.getEndTime());//活动结束时间
        //受众规则字段
        seckillVoucher.setAllowedLevels(seckillVoucherDto.getAllowedLevels());
        seckillVoucher.setMinLevel(seckillVoucherDto.getMinLevel());//允许购买的最低会员等级
        seckillVoucherService.save(seckillVoucher);     //数据库里面保存 接着是redis里面保存 在这之前要把过期时间弄出来
        //如果数据库查询不是空的，将秒杀优惠券信息写入缓存，TTL为距离结束时间的秒数
        long ttlSeconds = Math.max(  //当前时间和优惠券结束时间的差值
                LocalDateTimeUtil.between(LocalDateTimeUtil.now(), seckillVoucher.getEndTime()).getSeconds(),
                1L
        );
        //不爱修改的信息放在一个键值对里面，库存放在另一个键值对里面，（做一个优惠券详情信息查看，修改优惠券信息也不会影响库存）
        redisCache.set( //往redis里面放库存 。但像开始结束时间，初始库存和要扣减的库存，这些信息一般不会轻易改变，所以对于这种不是轻易改变的信息，可以单独放在redis里面，（做一个优惠券详情信息查看）
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId),
                String.valueOf(seckillVoucher.getStock()),
                ttlSeconds,
                TimeUnit.SECONDS
        );
        seckillVoucher.setStock(null);//库存置空，另外一个键值对不放库存，只放优惠券信息，不往redis里面放
        redisCache.set(//将秒杀券信息放入redis
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId),
                seckillVoucher,
                ttlSeconds,
                TimeUnit.SECONDS
        );
        //延迟提醒（有的用户，在这家商铺买的东西比较多，会员等级比较高，当有新的优惠券后，会先提醒这些经常购买的用户）用到延迟队列
        sendDelayedVoucherReminder(seckillVoucher);//根据秒杀优惠券的活动开始时间来延迟提醒通知用户有秒杀优惠券
        return voucherId;
    }
    //
    public void sendDelayedVoucherReminder(SeckillVoucher seckillVoucher){
        LocalDateTime beginTime = seckillVoucher.getBeginTime();//活动开始时间
        if (beginTime == null) {
            log.warn("[DELAY_REMINDER] beginTime为空，跳过调度 voucherId={}", seckillVoucher.getVoucherId());
            return;
        }
        long secondsUntilBegin = Math.max(
                LocalDateTimeUtil.between(LocalDateTimeUtil.now(), beginTime).getSeconds(),
                0L
        );
        //计算出延迟发送消息的时间，
        long delaySeconds = secondsUntilBegin - Math.max(reminderAheadSeconds, 0L);
        if (delaySeconds <= 0) {
            log.info("[DELAY_REMINDER] beginTime过近或已开始，不进行延迟调度 voucherId={} beginTime={} delaySeconds={}",
                    seckillVoucher.getVoucherId(), beginTime, delaySeconds);
            return;
        }
        //消息有了之后，组建消息
        DelayedVoucherReminderMessage msg = new DelayedVoucherReminderMessage(
                seckillVoucher.getVoucherId(),
                beginTime
        );
        String content = JSON.toJSONString(msg);//将消息转换为 JSON 字符串，准备发送到延迟队列

        String topic = SpringUtil.getPrefixDistinctionName() + "-" + DELAY_VOUCHER_REMINDER;
        delayQueueContext.sendMessage(topic, content, delaySeconds, TimeUnit.SECONDS);//调用组件发送消息 延迟队列的发送
        log.info("[DELAY_REMINDER] 已调度提醒消息 voucherId={} delaySeconds={} topic={}", seckillVoucher.getVoucherId(), delaySeconds, topic);
    }
    
    @Override
    public void delayVoucherReminder(DelayVoucherReminderDto delayVoucherReminderDto) {
        SeckillVoucher seckillVoucher = seckillVoucherService.lambdaQuery().eq(SeckillVoucher::getVoucherId, 
                delayVoucherReminderDto.getVoucherId()).one();
        if (Objects.isNull(seckillVoucher)) {
            throw new HmdpFrameException(BaseCode.SECKILL_VOUCHER_NOT_EXIST);
        }
        DelayedVoucherReminderMessage msg = new DelayedVoucherReminderMessage(
                seckillVoucher.getVoucherId(),
                seckillVoucher.getBeginTime()
        );
        String content = JSON.toJSONString(msg);
        String topic = SpringUtil.getPrefixDistinctionName() + "-" + DELAY_VOUCHER_REMINDER;
        Integer delaySeconds = delayVoucherReminderDto.getDelaySeconds();
        delayQueueContext.sendMessage(topic, content, delayVoucherReminderDto.getDelaySeconds(), TimeUnit.SECONDS);
        log.info("[测试延迟发送] 已调度提醒消息 voucherId={} delaySeconds={} topic={}", seckillVoucher.getVoucherId(), delaySeconds, topic);
    }
}
