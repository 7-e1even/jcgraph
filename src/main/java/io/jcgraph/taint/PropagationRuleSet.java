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

public class PropagationRuleSet {

    private List<PropagationRule> rules;

    public static PropagationRuleSet loadJSON(InputStream in) {
        if (in == null) {
            return empty();
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            PropagationRuleSet set = new Gson().fromJson(r, PropagationRuleSet.class);
            return set == null ? empty() : set;
        } catch (Exception ex) {
            System.err.println("[taint] error loading propagation rules: " + ex);
            return empty();
        }
    }

    private static PropagationRuleSet empty() {
        PropagationRuleSet s = new PropagationRuleSet();
        s.rules = new ArrayList<>();
        return s;
    }

    public List<PropagationRule> getRules() {
        return rules == null ? Collections.<PropagationRule>emptyList() : rules;
    }
}
