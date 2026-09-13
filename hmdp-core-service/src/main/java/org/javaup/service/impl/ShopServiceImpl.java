package org.javaup.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.RedisKeyManage;
import org.javaup.dto.Result;
import org.javaup.entity.Shop;
import org.javaup.exception.HmdpFrameException;
import org.javaup.handler.BloomFilterHandlerFactory;
import org.javaup.mapper.ShopMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.IShopService;
import org.javaup.servicelock.LockType;
import org.javaup.toolkit.SnowflakeIdGenerator;
import org.javaup.util.ServiceLockTool;
import org.javaup.utils.CacheClient;
import org.javaup.utils.SystemConstants;
import org.redisson.api.RLock;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.javaup.constant.Constant.BLOOM_FILTER_HANDLER_SHOP;
import static org.javaup.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.javaup.utils.RedisConstants.CACHE_SHOP_TTL;
import static org.javaup.utils.RedisConstants.LOCK_SHOP_KEY;
import static org.javaup.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 商铺 接口实现
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    
    @Resource
    private ServiceLockTool serviceLockTool;
    
    @Resource
    private RedisCache redisCache;
    
    @Resource
    private BloomFilterHandlerFactory bloomFilterHandlerFactory;
    
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    
    
    @Override
    public Result<Long> saveShop(final Shop shop) {
        // 写入数据库
        shop.setId(snowflakeIdGenerator.nextId());
        save(shop);
        // 写入布隆过滤器（商铺业务）
        bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOP).add(String.valueOf(shop.getId()));
        // 返回店铺id
        return Result.ok(shop.getId());
    }
    
    @Override
    public Result queryById(Long id) {
        //Shop shop = queryByIdV1(id);

        // 互斥锁解决缓存击穿
        //shop = queryByIdV2(id);

        // 逻辑过期解决缓存击穿
        //shop = queryByIdV3(id);
        
        // 🚀完美的方案！使用双重检测锁和空值配置，解决缓存击穿和缓存穿透
        Shop shop = queryByIdV4(id);
        
        if (shop == null) {
            return Result.fail("店铺不存在！");
        }
        // 7.返回
        return Result.ok(shop);
    }
    
    public Shop queryByIdV1(Long id){
        return cacheClient
                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }
    
    public Shop queryByIdV2(Long id){
        return cacheClient
                .queryWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }
    
    public Shop queryByIdV3(Long id){
        return cacheClient
                .queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 20L, TimeUnit.SECONDS);
    }





    //queryByIdV4同时解决缓存击穿和缓存穿透问题
    public Shop queryByIdV4(Long id){
        //第一步：从redis缓存中查询，
        Shop shop =
                redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY, id), Shop.class);   //把 Redis 里查到的 JSON 字符串，自动转成 Shop 实体类对象
        if (Objects.nonNull(shop)) {
            return shop;
        }//如果缓存查到的话，直接返回

        log.info("查询商铺 从Redis缓存没有查询到 商铺id : {}",id);//如果不存在，继续判断布隆过滤器
       //第二步：通过布隆过滤器判断是否存在  https://www.doubao.com/thread/wcb6e2dd8c4237be2
        if (!bloomFilterHandlerFactory.get(BLOOM_FILTER_HANDLER_SHOP).contains(String.valueOf(id))) {
            log.info("查询商铺 布隆过滤器判断不存在 商铺id : {}",id);
            throw new HmdpFrameException("查询商铺不存在 商铺id："+id);
        }//如果布隆过滤器有的话，接着执行


        //第三步：检查空值缓存（解决缓存穿透）      https://www.doubao.com/thread/w0b484259511d5eb8
       //解决缓存穿透，从缓存中判断是否存在商铺空值信息，如果有，代表商铺不存在，直接返回
        Boolean existResult = redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY_NULL, id));
        if (existResult){//如果存在空值，直接返回
            throw new RuntimeException("查询商铺不存在");
        }

//空值也没有的话，要从数据库里查找，要加锁，双重检测锁，
        //加锁，解决缓存击穿
        //第四步：获取分布式锁（解决缓存击穿）
        RLock lock = serviceLockTool.getLock(LockType.Reentrant, LOCK_SHOP_KEY, new String[]{String.valueOf(id)});
        lock.lock();
        try {

            //第五步：双重检测-再次检查空值缓存
            //判断空值，双重检测    再次从缓存中判断是否存在商铺空值信息，如果有，代表店铺不存在，直接返回
            existResult = redisCache.hasKey(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY_NULL, id));
            if (existResult){
                throw new RuntimeException("查询商铺不存在");
            }

            //第五步：双重检测-再次从缓存中获取商铺信息，通过此步骤可以避免大量请求在获取锁后，直接击穿缓存访问数据库
            //再次从redis缓存中获取商铺信息，通过此步骤可以避免大流量请求在获取锁后，直接击穿缓存访问数据库
            shop = redisCache.get(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY, id), Shop.class);
            if (Objects.nonNull(shop)) {
                return shop;
            }//如果缓存中存在直接返回


            //第六步：查询数据库，如果缓存不存在，直接查询数据库
            shop = getById(id);
            //第七步：处理缓存结果，如果从数据库查询是空的，将空值放入redis缓存  https://www.doubao.com/thread/wf02fe20ac05ccdaf
            if (Objects.isNull(shop)) {
                //写入空值缓存，防止缓存穿透
                redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY_NULL, id),
                        "这是一个空值",
                        CACHE_SHOP_TTL,
                        TimeUnit.MINUTES);
                throw new RuntimeException("查询商铺不存在");
            }

            //如果从数据库查询不是空的，将商铺信息放入redis缓存
            redisCache.set(RedisKeyBuild.createRedisKey(RedisKeyManage.CACHE_SHOP_KEY, id),shop,
                    CACHE_SHOP_TTL,
                    TimeUnit.MINUTES);
            return shop;
        }finally {
            //第八步，释放锁
            lock.unlock();
        }
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        //TODO 先改成 x 和 y 都是空
        x = null;
        y = null;
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
