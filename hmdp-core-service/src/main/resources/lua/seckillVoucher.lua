--




















--参数列表
--库存key
local stockKey = KEYS[1]
--用户集合key
local seckillUserKey = KEYS[2]
--扣减日志key
local traceLogKey = KEYS[3]
--数据
--优惠券id
local voucherId = ARGV[1]
--用户id
local userId = ARGV[2]
--秒杀开始时间（毫秒）
local beginTime = tonumber(ARGV[3])  --传下来是字符串要转成数字
--秒杀结束时间
local endTime = tonumber(ARGV[4])
--优惠券的状态（0：正常 1：已过期 2：已用完 3：已取消）
local status = tonumber(ARGV[5])
--订单id
local orderId = ARGV[6]
--关联id
local traceId = ARGV[7]
--操作类型
local logType = ARGV[8]
--过期时间（秒）
local ttlSeconds = tonumber(ARGV[9])
--脚本业务
--当前毫秒   （从redis里面获取）
local timeArr = redis.call('TIME')
local nowMillis = tonumber(timeArr[1]) * 1000 + math.floor(tonumber(timeArr[2]) / 1000)
--判断秒杀活动是否开始   当前时间小于开始时间说明活动没开始
if nowMillis < beginTime then
    return string.format('{"%s": %d}', 'code', 10002)
end
if nowMillis > endTime then
    return string.format('{"%s": %d}', 'code', 10003)
end
--判断优惠券状态
if (status == 2) then
    return string.format('{"%s": %d}', 'code', 10011)
end
if (status == 3) then
    return string.format('{"%s": %d}', 'code', 10012)
end
--获取库存
local stock = redis.call('get', stockKey);
--判断库存是否为空   如果库存不存在，说明库存为空
if not stock then   --为空的话返回   10004--》库存不存在
    return string.format('{"%s": %d}', 'code', 10004)
end
--判断库存是否足够  存在的话
if (tonumber(stock) <= 0) then
    return string.format('{"%s": %d}', 'code', 10005)
end
--判断用户是否已下单   如果没有下单，才往下执行，如果已经下单了，直接终止
if (redis.call('sismember', seckillUserKey, userId) == 1) then
    return string.format('{"%s": %d}', 'code', 10006)
end
--扣减库存
--记录扣减前 扣减 扣减后库存数量
local beforeQty = tonumber(stock)  --查出来的是扣减前的，
local changeQty = 1        --扣减是多少
local afterQty = beforeQty - changeQty
--扣减库存    +（-1）就是扣减
redis.call('incrby', stockKey, -changeQty)
--下单
redis.call('sadd', seckillUserKey, userId)
--获取我们的时间，单位是毫秒
local timeArr2 = redis.call('TIME')
local logNowMillis = tonumber(timeArr2[1]) * 1000 + math.floor(tonumber(timeArr2[2]) / 1000)
--记录扣减日志  操作类型 时间 订单id 关联id 用户id 优惠券id 扣减前 扣减 扣减后 json 格式拼装
local logEntry = cjson.encode({
  logType = logType,
  ts = logNowMillis,
  orderId = orderId,
  traceId = traceId,   --关联id把我们的日志记录和我们的数据库里面的日志进行关联，一一对应起来，
  userId = userId,
  voucherId = voucherId,
  beforeQty = beforeQty,
  changeQty = changeQty,
  afterQty = afterQty
})
--设置扣减信息，使用hash类型，哈希里面的key：链路关联id 方便和数据库中的记录关联，value：信息（扣减日志信息）   （把日志放在redis里面，）
redis.call('hset', traceLogKey, traceId, logEntry)
if ttlSeconds and ttlSeconds > 0 then
  redis.call('expire', traceLogKey, ttlSeconds)
end
--拼接返回结果
return string.format('{"%s": %d, "%s": %s, "%s": %s, "%s": %s}', 'code', 0, 'beforeQty', beforeQty, 'deductQty', changeQty, 'afterQty', afterQty)