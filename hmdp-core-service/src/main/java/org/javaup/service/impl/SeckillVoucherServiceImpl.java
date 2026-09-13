package org.javaup.service.impl;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.cache.SeckillVoucherLocalCache;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.Voucher;
import org.javaup.exception.HmdpFrameException;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.mapper.SeckillVoucherMapper;
import org.javaup.model.SeckillVoucherFullModel;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IVoucherService;
import org.javaup.servicelock.LockType;
import org.javaup.servicelock.annotion.ServiceLock;
import org.javaup.util.ServiceLockTool;
import org.redisson.api.RLock;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_VOUCHER;
import static org.javaup.constant.DistributedLockConstants.UPDATE_SECKILL_VOUCHER_LOCK;
import static org.javaup.constant.DistributedLockConstants.UPDATE_SECKILL_VOUCHER_STOCK_LOCK;
import static org.javaup.utils.RedisConstants.*;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 秒杀优惠券 接口实现
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {
    
    @Resource
    private ServiceLockTool serviceLockTool;//分布式锁的组件
    
    @Resource
    private RedisCache redisCache;//Redis缓存组件
    //引入布隆过滤器组件
    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;

    @Resource
    private SeckillVoucherLocalCache seckillVoucherLocalCache;//本地缓存组件
    
    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Resource
    private IVoucherService voucherService;
    //普通优惠券，秒杀优惠券，往redis里面放要拼一下，店铺id是放在普通优惠券里面的，秒杀的开始结束库存都在秒杀优惠券里面，所以要引入普通的service

    @Override
    @ServiceLock(lockType= LockType.Read,name = UPDATE_SECKILL_VOUCHER_LOCK,keys = {"#voucherId"})
    public SeckillVoucherFullModel queryByVoucherId(Long voucherId) {
        RedisKeyBuild seckillVoucherRedisKey =
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, voucherId);
        RedisKeyBuild seckillVoucherNullRedisKey =
                RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_NULL_TAG_KEY, voucherId);
//先查询本地缓存中的
        SeckillVoucherFullModel localCacheHit = seckillVoucherLocalCache.get(seckillVoucherRedisKey.getRelKey());
        if (Objects.nonNull(localCacheHit)) {//如果不为空，直接返回
            return localCacheHit;
        }

        //双重检测解决redis缓存 的一趴   查询redis缓存
        SeckillVoucherFullModel seckillVoucherFullModel =
                redisCache.get(seckillVoucherRedisKey, SeckillVoucherFullModel.class);
        if (Objects.nonNull(seckillVoucherFullModel)) {//第一次从外面判断redis有不，如果缓存中存在就直接返回
            seckillVoucherLocalCache.put(seckillVoucherRedisKey.getRelKey(), seckillVoucherFullModel);//如果redis不为空，先把数据放在本地缓存(写入本地缓存加快后续的访问)，再返回
            return seckillVoucherFullModel;
        }
        log.info("查询秒杀优惠券 从Redis缓存没有查询到 秒杀优惠券的优惠券id : {}",voucherId);


       //通过布隆过滤器判断是否存在（redis也没有再看布隆过滤器的）
        if (!bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_VOUCHER).contains(String.valueOf(voucherId))) {
            log.info("查询秒杀优惠券 布隆过滤器判断不存在 秒杀优惠券id : {}",voucherId);
            throw new HmdpFrameException("查询秒杀优惠券不存在");//如果不存在的话
        }
        //从本地缓存判断是否有空值
        SeckillVoucherFullModel  seckillVoucherFullModelNotExists = seckillVoucherLocalCache.get(seckillVoucherNullRedisKey.getRelKey());
         if (Objects.nonNull(seckillVoucherFullModelNotExists)) {
             throw new HmdpFrameException("查询秒杀优惠券不存在");
         }
        //判断有没有空值   判断空值是否存在，如果有空值，说明优惠券不存在了，抛出异常直接结束
        Boolean existResult = redisCache.hasKey(seckillVoucherNullRedisKey);
        if (existResult){//如果有空值，说明优惠券不存在了，如果不存在的话，直接往下执行，加锁 双重检测，
            throw new HmdpFrameException("查询秒杀优惠券不存在");
        }





       //redis没有加锁  上面有直接返回     空值也没有，才加锁
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, LOCK_SECKILL_VOUCHER_KEY, new String[]{String.valueOf(voucherId)});
        lock.lock();//lock这个方法表示，如果锁没有获取到的话，会阻塞等待，直到获取到锁




        try {
            //再次从本地缓存查询   如果有的话直接返回
            localCacheHit = seckillVoucherLocalCache.get(seckillVoucherRedisKey.getRelKey());
            if (Objects.nonNull(localCacheHit)) {
                return localCacheHit;
            }


          //加完锁，再从redis里面查有不，如果有就直接返回，没有就继续查询数据库   再次检测缓存是否存在
            seckillVoucherFullModel = redisCache.get(seckillVoucherRedisKey, SeckillVoucherFullModel.class);
            if (Objects.nonNull(seckillVoucherFullModel)) {
                seckillVoucherLocalCache.put(seckillVoucherRedisKey.getRelKey(), seckillVoucherFullModel);//如果缓存存在，写入本地缓存(写入本地缓存加快后续的访问)，再返回
                return seckillVoucherFullModel;
            }
            //从本地缓存判断是否有空值   双重检测
            seckillVoucherFullModelNotExists = seckillVoucherLocalCache.get(seckillVoucherNullRedisKey.getRelKey());
            if (Objects.nonNull(seckillVoucherFullModelNotExists)) {
                throw new HmdpFrameException("查询秒杀优惠券不存在");
            }


           //再次判断redis是否有空值，如果有空值，直接返回，如果没有的话，查询数据库  双重检测
            existResult = redisCache.hasKey(seckillVoucherNullRedisKey);
            if (existResult){
                throw new RuntimeException("查询优惠券不存在");
            }

           //如果缓存还不存在就在数据库里查询
            SeckillVoucher seckillVoucher = lambdaQuery().eq(SeckillVoucher::getVoucherId,voucherId).one();
            //如果数据库为空   要把空值（这是一个空值）写入redis中，过期时间为10分钟
            if (Objects.isNull(seckillVoucher)) {
                redisCache.set(seckillVoucherNullRedisKey,
                        "这是一个空值",
                        CACHE_NULL_TTL,
                        TimeUnit.MINUTES);
                //往本地缓存也得放空值   本地缓存过期时间没有办法像redis里面可以指定过期时间，过期时间不是根据对象来指定的，
                seckillVoucherFullModel=new SeckillVoucherFullModel();
                seckillVoucherFullModel.setEndTime(LocalDateTimeUtil.offset(LocalDateTime.now(), CACHE_NULL_TTL, ChronoUnit.MINUTES));//手动设置过期时间
                seckillVoucherLocalCache.put(seckillVoucherNullRedisKey.getRelKey(), seckillVoucherFullModel);


                throw new RuntimeException("查询秒杀优惠券不存在");
            }

            //如果数据库不为空则写入Redis中，过期时间为优惠券结束时间到当前时间的秒数
            long ttlSeconds = Math.max(
                    LocalDateTimeUtil.between(LocalDateTimeUtil.now(), seckillVoucher.getEndTime()).getSeconds(),
                    1L
            );
            //从普通优惠券中将店铺信息查出来
            Voucher voucher = voucherService.lambdaQuery().eq(Voucher::getId, voucherId).one();//查询普通优惠券

            //组装我们的信息
            seckillVoucherFullModel = new SeckillVoucherFullModel();
            BeanUtils.copyProperties(seckillVoucher, seckillVoucherFullModel);
            seckillVoucherFullModel.setShopId(voucher.getShopId());
            seckillVoucherFullModel.setStatus(voucher.getStatus());
            seckillVoucherFullModel.setStock(null);//要把库存清空，秒杀优惠券以后要用到多级缓存，存在本地缓存，本地缓存肯定不能存库存了，库存要在redis中进行秒杀扣减，所以这里要清空
            //构建好，往redis里面去放
            redisCache.set(
                    seckillVoucherRedisKey,
                    seckillVoucherFullModel,
                    ttlSeconds,
                    TimeUnit.SECONDS
            );
//写入我们的本地缓存
            seckillVoucherLocalCache.put(seckillVoucherRedisKey.getRelKey(), seckillVoucherFullModel);
            return seckillVoucherFullModel;
        }finally {
            //lock.unlock();
            //如果成功了，我们再解锁

                lock.unlock();

        }
    }
    
    @Override
    @ServiceLock(lockType= LockType.Read,name = UPDATE_SECKILL_VOUCHER_STOCK_LOCK,keys = {"#voucherId"})//锁的注解 分布式锁的组件
    public void loadVoucherStock(Long voucherId){//库存修改离不开库存是如何加载的，
        //用布隆过滤器判断是否存在，如果不存在，直接抛出异常，避免查询数据库
        if (!bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_VOUCHER).contains(String.valueOf(voucherId))) {
            log.info("加载库存 布隆过滤器判断不存在 秒杀优惠券id : {}",voucherId);
            throw new RuntimeException("查询秒杀优惠券不存在");
        }


        /**
         * 下面还是缓存击穿和缓存穿透的那一套，但是不涉及本地缓存，库存肯定要经常改，本地缓存只放那些不容易改变的数据
         *
         */




        String stock = //先从缓存里面拿来库存
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId), String.class);
        if (Objects.nonNull(stock)) {//如果缓存里面有库存，直接返回
            return;
        }
        //如果不存在接着查，加锁，是用来解决缓存击穿问题的不是用来解决并发一致性问题的     （用的重入锁）
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, LOCK_SECKILL_VOUCHER_STOCK_KEY, 
                new String[]{String.valueOf(voucherId)});
        lock.lock();
        try {
            //加锁之后再查一次，(防止有其他线程正在加载库存----》双重检测为了避免多线程下重复执行库存加载（重复初始化），同时保证高性能)
            stock = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId), String.class);
            if (Objects.nonNull(stock)) {
                return;//如果redis存在的话就不执行了，如果redis还不存在的话，才执行下面的代码
            }

//在库存里面去查数据库，如果数据库为空，我们把空值（这是一个空值）写入redis中，过期时间为10分钟（数据库里面去查，查完以后再放入我们的缓存）
            SeckillVoucher seckillVoucher = lambdaQuery().eq(SeckillVoucher::getVoucherId,voucherId).one();
            if (Objects.nonNull(seckillVoucher)) {
                long ttlSeconds = Math.max(
                        LocalDateTimeUtil.between(LocalDateTimeUtil.now(), seckillVoucher.getEndTime()).getSeconds(),
                        1L
                );
                //把库存写入redis中，过期时间为优惠券结束时间到当前时间的秒数
                redisCache.set(
                        RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY, voucherId),
                        String.valueOf(seckillVoucher.getStock()),
                        ttlSeconds,
                        TimeUnit.SECONDS
                );
            }
        }finally {
            lock.unlock();
        }
    }
    //回滚库存 要写sql先把mapper引进来  执行成功的话返回的是成功更新了多少行，sql，
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rollbackStock(final Long voucherId) {
        return seckillVoucherMapper.rollbackStock(voucherId) > 0;
    }
}
