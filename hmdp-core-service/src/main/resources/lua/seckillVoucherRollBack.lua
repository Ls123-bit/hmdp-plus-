
--参数key
--库存
local stockKey = KEYS[1]

--用户集合key
local seckillUserKey = KEYS[2]
--扣减日志key
local traceLogKey = KEYS[3]
--数据
--优惠券id
local voucherId = ARGV[1]
--用户id
local userId = (ARGV[2])
--订单id
local orderId = ARGV[3]
--是否删除秒杀优惠券订单记录（是否删除订单记录）
local seckillVoucherOrderOperate = tonumber(ARGV[4])
--traceId
local traceId = ARGV[5]
--操作类型
local logType = ARGV[6]
--前库存
local beforeQty = tonumber(ARGV[7])
--改变库存
local changeQty = tonumber(ARGV[8])
--后库存
local afterQty = tonumber(ARGV[9])
--查询库存
local stock = redis.call('get', stockKey);

if not stock then
    return 10004
end

--秒杀券回滚操作中处理用户抢购记录的逻辑
redis.call('del', stockKey)--回滚加一不好办，所以先将其删掉，下次再下单又重新加载   发送到kafka失败了，消费端根本得不到消息，订单生成不了，数据库中的库存也就不进行扣减 所以直接删掉之后，等下次再进行抢购优惠券的话，又从数据库中的库存加载回来了
if seckillVoucherOrderOperate == 1 then   --判断是否需要删除订单记录：如果 seckillVoucherOrderOperate == 1，则执行删除操作。
    if (redis.call('sismember', seckillUserKey, userId) == 1) then  --检查用户是否在抢购集合中：使用 sismember 检查用户 ID 是否存在于 seckillUserKey 集合中。
        redis.call('srem', seckillUserKey, userId)--移除用户抢购记录：如果用户在集合中，使用 srem 将其移除，允许用户再次参与秒杀抢购。
    end
end
--构建回滚的扣减日志记录
local timeArr = redis.call('TIME')
local nowMillis = tonumber(timeArr[1]) * 1000 + math.floor(tonumber(timeArr[2]) / 1000)
local logEntry = cjson.encode({
    logType = logType,
    ts = nowMillis,
    orderId = orderId,
    traceId = traceId,
    userId = userId,
    voucherId = voucherId,
    beforeQty = beforeQty,
    changeQty = changeQty,
    afterQty = afterQty
})
redis.call('hset', traceLogKey, traceId, logEntry)
return 0
