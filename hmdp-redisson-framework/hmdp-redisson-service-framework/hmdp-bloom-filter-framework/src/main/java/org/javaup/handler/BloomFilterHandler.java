package org.javaup.handler;


import org.javaup.core.SpringUtil;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料 
 * @description: 单个布隆过滤器封装
 * @author: 阿星不是程序员
 **/


public class BloomFilterHandler {

    private final RBloomFilter<String> bloomFilter;

    public BloomFilterHandler(RedissonClient redissonClient, 
                              String name, 
                              Long expectedInsertions, 
                              Double falseProbability){
        RBloomFilter<String> bf = redissonClient.getBloomFilter(
                SpringUtil.getPrefixDistinctionName() 
                        + "-" 
                        + name);
        bf.tryInit(expectedInsertions == null ? 
                        20000L : expectedInsertions,
                falseProbability == null ? 
                        0.01D : falseProbability);
        this.bloomFilter = bf;
    }

    public boolean add(String data) {
        return bloomFilter.add(data);
    }

    public boolean contains(String data) {
        return bloomFilter.contains(data);
    }
}