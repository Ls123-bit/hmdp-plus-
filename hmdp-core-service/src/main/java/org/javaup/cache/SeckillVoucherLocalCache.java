package org.javaup.cache;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.javaup.model.SeckillVoucherFullModel;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 本地缓存：秒杀优惠券详情    （秒杀优惠券的本地缓存）
 * @author: 阿星不是程序员
 **/


/**
 * 设计要点：
 * 1.本地缓存，零网络开销：读写操作在内存中完成，适合热点数据与“读多写少”的秒杀场景
 * 2.动态 TTL：过期时间基于券的 endTime 动态计算，而不是统一固定值
 * 3.不刷新热点：读取与更新不改变剩余过期时间，防止热点数据长时间驻留(正常缓存 只要查一下，更新一下这条数据，他的过期时间就会重新计时，在这里热点优惠券访问量极大，如果每次查询都刷新过期时间，这条数据会永久驻留在本地内存，越堆越多，内存爆掉)
 * 4.容量控制：限制最大缓存条目为 10000，防止内存膨胀
 *
 *
 */



@Component
public class SeckillVoucherLocalCache {
    
    private final Cache<String, SeckillVoucherFullModel> cache = Caffeine.newBuilder()
            .maximumSize(10000)//容量
            .expireAfter(new Expiry<String, SeckillVoucherFullModel>() {//过期时间指定他的泛型
                @Override
                public long expireAfterCreate(String key, SeckillVoucherFullModel value, long currentTime) {
                    long ttlSeconds = 60L;//默认缓存有效期 过期时间为60秒
                    if (value != null && value.getEndTime() != null) {//如果对象不为空，并且结束时间也不为空，那么以他的结束时间为依据
                        ttlSeconds = Math.max(//重新赋值
                                LocalDateTimeUtil.between(LocalDateTimeUtil.now(), value.getEndTime()).getSeconds(),
                                1L
                        );
                    }
                    return TimeUnit.NANOSECONDS.convert(ttlSeconds, TimeUnit.SECONDS);//原始时间秒转换成纳秒
                }
                
                @Override
                public long expireAfterUpdate(String key, SeckillVoucherFullModel value, long currentTime, long currentDuration) {
                  //更新时，保持原有的过期时间不变
                    return currentDuration;
                }
                
                @Override
                public long expireAfterRead(String key, SeckillVoucherFullModel value, long currentTime, long currentDuration) {
                   //读取时，保持原有的过期时间不变
                    return currentDuration;
                }
            })
            .build();
    //用get取缓存中的数据
    public SeckillVoucherFullModel get(String voucherId) {
        return cache.getIfPresent(voucherId);
    }
    //用put放缓存
    public void put(String voucherId, SeckillVoucherFullModel voucher) {
        if (voucherId != null && voucher != null) {//如果优惠券id和优惠券对象都不为空再往里面放
            cache.put(voucherId, voucher);
        }
    }
    //用invalidate删除缓存中的数据  清除，失效
    public void invalidate(String voucherId) {
        cache.invalidate(voucherId);
    }
}