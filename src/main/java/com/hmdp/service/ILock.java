package com.hmdp.service;

public interface ILock {
    boolean tryLock(Long timeoutSec);

    void unLock();
}
