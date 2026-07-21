package com.github.tink_api_with_charts.utils;

import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public class ConcurrentSlidingCache<T> {

    private final Deque<T> lifoCache = new LinkedList<>();
    private final Set<T> rawCache = new HashSet<>();
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
