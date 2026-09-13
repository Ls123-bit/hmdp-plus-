package org.javaup.init;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.cache.SeckillVoucherLocalCache;
import org.javaup.core.RedisKeyManage;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.Shop;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeckillVoucherService;
import org.javaup.service.IShopService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 删除缓存数据
 * @author: 阿星不是程序员
 **/
@Slf4j
@Order(3)
@Component
public class DelCacheDataInit {
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private IShopService shopService;
    
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    
    @Resource
    private SeckillVoucherLocalCache seckillVoucherLocalCache;
    
    @PostConstruct
    public void init(){
        log.info("==========删除缓存中的数据==========");
        List<Shop> shopList = shopService.list();
        for (final Shop shop : shopList) {
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY,shop.getId()));
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY_NULL,shop.getId()));
        }
        List<SeckillVoucher> seckillVoucherList = seckillVoucherService.list();
        for (final SeckillVoucher seckillVoucher : seckillVoucherList) {
            RedisKeyBuild seckillVoucherRedisKey =
                    RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY, seckillVoucher.getVoucherId());
            seckillVoucherLocalCache.invalidate(seckillVoucherRedisKey.getRelKey());
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_STOCK_TAG_KEY,seckillVoucher.getVoucherId()));
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_USER_TAG_KEY,seckillVoucher.getVoucherId()));
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_TAG_KEY,seckillVoucher.getVoucherId()));
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_VOUCHER_NULL_TAG_KEY,seckillVoucher.getVoucherId()));
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_TRACE_LOG_TAG_KEY,seckillVoucher.getVoucherId()));
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_USER_TAG_KEY,seckillVoucher.getVoucherId()));
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_ZSET_TAG_KEY,seckillVoucher.getVoucherId()));
            redisCache.del(RedisKeyBuild.createRedisKey(RedisKeyManage.SECKILL_SUBSCRIBE_STATUS_TAG_KEY,seckillVoucher.getVoucherId()));
        }
    }
}
