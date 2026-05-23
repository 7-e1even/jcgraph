/*
 * Adapted from jar-analyzer (https://github.com/jar-analyzer/jar-analyzer)
 * Original: GPLv3 License, Copyright (c) 2022-2026 4ra1n (Jar Analyzer Team)
 * Ported into jcgraph (gson instead of fastjson2); remains under GPLv3.
 */
package io.jcgraph.taint;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SanitizerRule {

    private List<Sanitizer> rules;

    public static SanitizerRule loadJSON(InputStream in) {
        if (in == null) {
            return empty();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            SanitizerRule rule = new Gson().fromJson(r, SanitizerRule.class);
            return rule == null ? empty() : rule;
        } catch (Exception ex) {
            System.err.println("[taint] error loading sanitizer rules: " + ex);
            return empty();
        }
    }

    private static SanitizerRule empty() {
        SanitizerRule s = new SanitizerRule();
        s.rules = new ArrayList<>();
        return s;
    }

    public List<Sanitizer> getRules() {
        return rules == null ? Collections.<Sanitizer>emptyList() : rules;
    }
}
