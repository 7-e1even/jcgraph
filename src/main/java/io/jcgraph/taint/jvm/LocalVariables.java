/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph (package + minor cleanup only); remains under GPLv3.
 */
package io.jcgraph.taint.jvm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Abstract local-variable table: each slot carries a taint marker set. */
public class LocalVariables<T> {
    private final ArrayList<Set<T>> array;

    public LocalVariables() {
        this.array = new ArrayList<>();
    }

    public void clear() {
        this.array.clear();
    }

    public void add(Set<T> t) {
        this.array.add(t);
    }

    public void set(int index, Set<T> t) {
        array.set(index, t);
    }

    public void set(int index, T t) {
        Set<T> set = new HashSet<>();
        set.add(t);
        array.set(index, set);
    }

    public Set<T> get(int index) {
        return array.get(index);
    }

    public int size() {
        return this.array.size();
    }

    public void remove(int index) {
        this.array.remove(index);
    }

    public List<Set<T>> getList() {
        return this.array;
    }
}
