
--令牌桶限流(支持ip和用户两个维度)
--设计说明;
--容量 capacity=maxAttempts（最大尝试次数）
--生成速率 ratePerms=maxAttempts/windowMillis（每毫秒生成的令牌数）最大尝试次数除以我们的窗口
--使用redis time（时间戳）作为统一的时钟，避免应用于redis时钟漂移问题
--ttl（过期时间）=windowMillis*2+随机数（随机抖动）（0到windowMillis/10之间）缓解集中过期而产生的抖动
--判断顺序：先按ip维度，再按用户维度
--参数

--ip维度的桶 （HASH结构）
local ipKey = KEYS[1]
--用户维度的桶 （HASH结构）
local userKey = KEYS[2]
--ip维度的窗口时间（毫秒）
local ipWindowMillis = tonumber(ARGV[1] or '0')
--ip维度的最大尝试次数
local ipMaxAttempts = tonumber(ARGV[2] or '0')
--用户维度的窗口时间（毫秒）
local userWindowMillis = tonumber(ARGV[3] or '0')
--用户维度的最大尝试次数
local userMaxAttempts = tonumber(ARGV[4] or '0')
--返回的code码
local CODE_SUCCESS = 0        --成功
local CODE_IP_EXCEEDED = 10007   --ip维度的限额
local CODE_USER_EXCEEDED = 10008   --用户维度的限额
--获取当前时间戳
local now = redis.call('TIME')
local nowMillis = now[1] * 1000 + math.floor(now[2] / 1000)













local function clampDelta(delta, window)
  if delta < 0 then return 0 end   --如果时间间隔小于0 说明上一次生成令牌的时间戳大于当前时间戳  直接返回0即可  不用补充了
  local maxDelta = window > 0 and (window * 2) or 0    --如果我们的窗口时间大于0  补充我们的窗口的二倍   如果不大于零那么还是零
  if maxDelta > 0 and delta > maxDelta then return maxDelta end --如果大于零并且时间间隔大于最大时间间隔  则返回二倍时间窗口
  return delta  --否则返回时间间隔 兜底时间间隔 不能让时间无限的涨
end
--计算并消费令牌
local function tryConsume(bucketKey, windowMillis, maxAttempts)
  if bucketKey == nil or bucketKey == '' or windowMillis <= 0 or maxAttempts <= 0 then
    return true      --参数不正常不执行限流直接返回true
  end
--桶的容量
  local capacity = maxAttempts
  --平均令牌生成速率（每毫秒）
  local ratePerMs = maxAttempts / windowMillis
  --上一次生成令牌的时间戳
  local lastMs = tonumber(redis.call('HGET', bucketKey, 'last_ms'))
  --当前桶里的令牌数
  local tokens = tonumber(redis.call('HGET', bucketKey, 'tokens'))
 --如果不为空的话 要更新一下
  if not lastMs then
    lastMs = nowMillis --把当前的时间戳变为记录，
    tokens = capacity   --把桶里的令牌数变为最大尝试次数  我们的token变为容量
  end



--时间间隔影响我们的补充令牌数
 --计算时间间隔 （距离上次更新的时间间隔）   参数：当前时间戳-上一次生成令牌的时间戳 （毫秒）  窗口时间（毫秒）
  local delta = clampDelta(nowMillis - lastMs, windowMillis)
  local refill = delta * ratePerMs         --动态生成令牌要获取的令牌数  本次可补充的令牌数，时间间隔乘上我们的生成速率
 --不能一直生成，有一个最大限量 桶中令牌不能超过的容量  令牌不能超过容量，超过容量就只能取容量这个数了
  tokens = math.min(capacity, tokens + refill)

--消耗令牌
  if tokens >= 1.0 then  --大于一说明有令牌可以消耗
    tokens = tokens - 1.0 --消耗一个令牌
    redis.call('HSET', bucketKey, 'tokens', tokens) --更新桶的状态
    redis.call('HSET', bucketKey, 'last_ms', nowMillis)--更新上一次生成令牌的时间戳
    --设置过期时间 窗口时间的2倍+随机抖动 (窗口/10) 降级集中过期导致负载尖峰  避免集中过期
    local ttl = (windowMillis * 2) + math.random(0, math.max(1, math.floor(windowMillis / 10)))
    redis.call('PEXPIRE', bucketKey, ttl)
    return true
  else --令牌不足仍更新last_ms和tokens
    redis.call('HSET', bucketKey, 'tokens', tokens)
    redis.call('HSET', bucketKey, 'last_ms', nowMillis)
    local ttl = (windowMillis * 2) + math.random(0, math.max(1, math.floor(windowMillis / 10)))
    redis.call('PEXPIRE', bucketKey, ttl)
    return false
  end
end
--执行业务   （令牌桶在桶里获得令牌 说明请求被允许通过，如果没有获得，说明限流被执行了）
--先按ip维度
local ipAllowed = tryConsume(ipKey, ipWindowMillis, ipMaxAttempts)
if not ipAllowed then
  return CODE_IP_EXCEEDED  --没有获得令牌 说明ip维度的限额被超了  直接返回
end
--再按用户维度
local userAllowed = tryConsume(userKey, userWindowMillis, userMaxAttempts)
if not userAllowed then
  return CODE_USER_EXCEEDED  --没有获得令牌 说明用户维度的限额被超了  直接返回
end
return CODE_SUCCESS