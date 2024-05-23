package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    //封装redis工具类
    private final StringRedisTemplate stringRedisTemplate;

    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key,Object value,Long time,TimeUnit unit){
        //方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //不确定传进来的时间单位，将其转成秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        String json = JSONUtil.toJsonStr(redisData);
        //写入redis
        stringRedisTemplate.opsForValue().set(key,json);
    }

    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallBack,Long time,TimeUnit unit){
        //方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String stringJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(stringJson)){
            //存在，直接返回
            return JSONUtil.toBean(stringJson,type);
        }
        // 判断命中的是否是空值
        if(stringJson != null){
            //是空值，返回null
            return null;
        }
        // 4.不存在，根据id查询数据库
        R r = dbFallBack.apply(id);
        // 5.不存在，返回错误
        if(r == null){
            return null;
        }
        // 6.存在，写入redis
        set(key,r,time,unit);
        return r;
    }

    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallBack,Long time,TimeUnit unit){
        //1.从redis中查询信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){
            return null;
        }
        //命中，将json对象反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        LocalDateTime expire = redisData.getExpireTime();
        //实现缓存重构
        //判断缓存是否过期
        if(expire.isAfter(LocalDateTime.now())){
            return r;
        }
        //已过期，缓存重构，获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        log.info(String.valueOf(isLock));
        //判断获取锁是否成功
        if(isLock){
            log.info("1");
            System.out.println(stringRedisTemplate.opsForValue().get(lockKey));
            //新建一个线程进行重构
            CACHE_REBUILD_EXECUTOR.submit(()->{
               try{
                   //先查数据库
                   R r2 = dbFallBack.apply(id);
                   //再写入redis
                   setWithLogicalExpire(key,r2,time,unit);
               }catch (Exception e){
                   throw new RuntimeException(e);
               }finally {
                   unLock(lockKey);
               }
            });
        }
        return r;
    }

    public <R,ID> R queryWithMutex(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallBack,Long time,TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if(StrUtil.isNotBlank(json)){
            R r = JSONUtil.toBean(json, type);
            return r;
        }
        if(json != null){
            return null;
        }
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        R r = null;
        try{
            if(!isLock){
                Thread.sleep(50);
                return queryWithMutex(keyPrefix,id,type,dbFallBack,time,unit);
            }
            r = dbFallBack.apply(id);
            if(r == null){
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            set(key,JSONUtil.toJsonStr(r),time,unit);
        }catch (Exception e){
            throw new RuntimeException(e);
        }finally {
            unLock(lockKey);
        }
        return r;

    }

    public boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",300,TimeUnit.SECONDS);
        log.info("yes");
        return BooleanUtil.isTrue(flag);
    }

    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }
}
