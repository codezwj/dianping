--订单id
local voucherId = ARGV[1]
--用户id
local userId = ARGV[2]

--库存key
local stockKey = 'seckill:stock:' .. voucherId

--订单key
local orderKey = 'seckill:order:' .. voucherId

--判断库存是否充足 get stockKey
local stock = redis.call('get',stockKey)
local number = tonumber(stock)
--if(tonumber(redis.call('get',stockKey)) <= 0) then
if(number <= 0) then
    return 1;
end

--判断用户是否下单 sismember orderKey userId
if(redis.call('sismember',orderKey,userId) == 1) then
    --存在，是重复下单
    return 2;
end


--扣减库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)

--下单（保存用户）sadd orderKey userId
redis.call('sadd',orderKey,userId)
return 0