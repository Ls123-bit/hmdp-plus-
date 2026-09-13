
--滑动窗口的脚本在这里写好，再去上面的lua包，
--滑动窗口上传参数，key是用来占位的，是我们的ip还是我们的用户，然后就是四个参数，分别是ip维度的窗口毫秒数，ip维度的最大请求数，用户维度的窗口毫秒数，用户维度的最大请求数
--
--滑动窗口限流，（支持ip和用户两个维度）
--参数
--ip的key，ip维度的zset
local ipKey = KEYS[1]
--用户维度key，用户维度的zset
local userKey = KEYS[2]
--数据
--ip维度的窗口毫秒数 没有的话默认为零
local ipWindowMillis = tonumber(ARGV[1] or '0')
--ip最大尝试次数
local ipMaxAttempts = tonumber(ARGV[2] or '0')
--用户窗口毫秒数
local userWindowMillis = tonumber(ARGV[3] or '0')
--用户最大尝试次数
local userMaxAttempts = tonumber(ARGV[4] or '0')


--我们的code码 baseCode

local CODE_SUCCESS = 0
local CODE_IP_EXCEEDED = 10007 --ip限制触发了
local CODE_USER_EXCEEDED = 10008 --用户限制触发了
--获取当前时间时间戳
local now = redis.call('TIME')
local nowMillis = now[1] * 1000 + math.floor(now[2] / 1000)










--真正的执行业务逻辑   （ip和用户，执行的限流的执行策略是一样的，要共用一个方法，共用一个方法要先计数）
--生成唯一成员值，避免同毫秒内重复
local function uniqueMember(baseKey, ts)
    local seqKey = baseKey .. ':seq'
    local seq = redis.call('INCR', seqKey)  --自增，避免同毫秒内重复
    --如果seq为1，说明是第一个请求，需要设置过期时间，过期时间为10分钟（给key设置一个较短的过期时间，避免长期占用内存）
    if seq == 1 then
        redis.call('PEXPIRE', seqKey, 600000) -- 10分钟
    end
    return tostring(ts) .. ':' .. tostring(seq) --拼上我们的时间戳
end
--对指定zset执行滑动窗口计数，超过则返回指定错误码
local function checkSlidingLimit(zsetKey, windowMillis, maxAttempts, exceededCode)
    if zsetKey ~= nil and zsetKey ~= '' and windowMillis > 0 and maxAttempts > 0 then
        --添加当前记录
        local member = uniqueMember(zsetKey, nowMillis)
       --添加当前（限流）记录到zset    member是生成的成员值
        redis.call('ZADD', zsetKey, nowMillis, member)
        --生成以后要删除窗口外的旧记录
        local minScore = 0
        local maxOld = nowMillis - windowMillis    --当前的时间戳减去窗口时间，就是窗口外的旧记录
        redis.call('ZREMRANGEBYSCORE', zsetKey, minScore, maxOld) --根据得分删除窗口外的旧记录


--删除完事之后，开始计数，统计当前窗口内的请求次数（记录数）
        local cnt = redis.call('ZCARD', zsetKey)
        if cnt == 1 then --如果数量是1的话，说明是最开始的调用，需要设置过期时间，过期时间为窗口时间的2倍 （在窗口内不过期就行） 给集合设置一个过期时间，避免长时间占用内存，不影响滑动窗口的逻辑
            redis.call('PEXPIRE', zsetKey, windowMillis * 2)
        end
        if cnt > maxAttempts then    --如果不是一，（说明在这之前已经有请求了）判断是否超过最大请求数 （超过最大请求数，直接返回错误码）  如果超过的话 触发我们的code码
            return exceededCode
        end
    end
    return CODE_SUCCESS   --如果没有超过限制，返回成功的code码
end









-------------------------------------------------------------------

--先检查ip维度，超过直接返回，
local ipRet = CODE_SUCCESS
--如果窗口大于零（等于零说明没有设置）并且重试次数大于零，才需要检查
if ipKey ~= nil and ipKey ~= '' and ipWindowMillis > 0 and ipMaxAttempts > 0 then
    ipRet = checkSlidingLimit(ipKey, ipWindowMillis, ipMaxAttempts, CODE_IP_EXCEEDED)   --ip相关code码传进去
    if ipRet ~= CODE_SUCCESS then    --如果ip维度超过限制，直接返回 不等于成功的code码
        return ipRet           --就把ip维度的code码返回（把不成功的返回回去）
    end
end
--再检查用户维度，超过则直接返回       用户一般都会存在，限流的时候是分为两种（ip和用户），ip要判断，因为我们的ip可以进行设置，但是我们的用户是肯定要设置限流的。ip的限制是可选的，用户就不用对应的条件了，用户要限流的话，用户是肯定要存在的
--直接调用，把用户相关的参数放进去
local userRet = checkSlidingLimit(userKey, userWindowMillis, userMaxAttempts, CODE_USER_EXCEEDED)
if userRet ~= CODE_SUCCESS then --如果不等于直接返回，
    return userRet
end
--能执行到这里说明，ip维度和用户维度都没有超过限制，所以返回成功的code码0（两个限流都没有触发）此时的请求是正常请求，是允许返回请求的，
----------------------------------------------------------------------------




return CODE_SUCCESS           --允许请求，返回成功的code码