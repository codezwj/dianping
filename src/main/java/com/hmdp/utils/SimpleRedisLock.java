package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import com.hmdp.service.ILock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    public static final String KEY_PREFIX = "lock";
    public static final String ID_PREFIX = UUID.fastUUID().toString(true);
    public static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //这里避免boolean自动拆箱导致空指针，通过equals来判断是不是null
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        //采用lua脚本方式
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }

//    @Override
//    public void unLock() {
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //通过获取锁中的id来看和自己线程的id是否一样，避免错误释放其他线程的锁
//        if(id.equals(threadId)){
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}
