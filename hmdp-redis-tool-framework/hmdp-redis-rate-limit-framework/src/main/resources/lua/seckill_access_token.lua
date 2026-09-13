local key = KEYS[1]       --我们的key
local expected = ARGV[1]   --我们的令牌
local val = redis.call('get', key)  --通过redis取值
if val == expected then    --如果取出来的值和我们传进来的这个令牌相等的话，就把他删掉，因为一次一用
  return redis.call('del', key)
else
  return 0       --如果取出来的值和我们传进来的这个令牌不相等的话，就返回0，表示令牌验证没有通过
end