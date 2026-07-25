package com.github.tink_api_with_charts.utils;

import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArraySet;

public class ConcurrentSlidingCache<T> {

    private final Deque<T> lifoCache = new ConcurrentLinkedDeque<>();
    private final Set<T> rawCache = new CopyOnWriteArraySet<>();
    private static final int CACHE_SIZE = 10;

    public synchronized void add(T o) {
        if (lifoCache.size() >= CACHE_SIZE) {
            rawCache.remove(lifoCache.pollLast());
        }
        lifoCache.addFirst(o);
        rawCache.add(o);
    }

    public synchronized boolean contains(T o) {
        return rawCache.contains(o);
    }

    public synchronized boolean checkContainsAndAdd(T o) {
        if (rawCache.contains(o)) {
            return true;
        }
        add(o);
        return false;
    }

}
