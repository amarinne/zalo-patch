package com.ez.zalopatch;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FingerprintResolverTest {
    @Test
    public void recordedGoldenEvidenceResolvesExpectedBindings() throws Exception {
        Map<String, Evidence> evidence = readEvidence();
        Map<String, String> expected = readExpected();
        assertEquals(expected.keySet(), evidence.keySet());
        for (Map.Entry<String, String> binding : expected.entrySet()) {
            Evidence fixture = evidence.get(binding.getKey());
            FingerprintResolver.Resolution first = FingerprintResolver.resolve(
                    fixture.hash, fixture.hash, fixture.anchor, fixture.highRisk,
                    fixture.candidates);
            ArrayList<FingerprintResolver.Candidate> reversed = new ArrayList<>(fixture.candidates);
            java.util.Collections.reverse(reversed);
            FingerprintResolver.Resolution second = FingerprintResolver.resolve(
                    fixture.hash, fixture.hash, fixture.anchor, fixture.highRisk, reversed);
            assertEquals(binding.getValue(), first.symbol);
            assertEquals(first.canonical(), second.canonical());
            assertTrue(first.margin >= FingerprintResolver.MINIMUM_MARGIN);
        }
    }

    @Test
    public void wrongHashAndAmbiguousCandidatesFailClosed() {
        FingerprintResolver.Candidate first = candidate("a", true, 0);
        FingerprintResolver.Candidate second = candidate("b", true, 5);
        assertEquals("apk_hash_mismatch", FingerprintResolver.resolve(
                "expected", "actual", "inbox.adapter", false,
                Arrays.asList(first)).status);
        assertEquals("ambiguous_margin", FingerprintResolver.resolve(
                "same", "same", "inbox.adapter", false,
                Arrays.asList(first, second)).status);
        assertEquals("confidence_below_threshold", FingerprintResolver.resolve(
                "same", "same", "inbox.adapter", false,
                Arrays.asList(candidate("shape-only", false, 0))).status);
    }

    private Map<String, Evidence> readEvidence() throws Exception {
        Map<String, Evidence> fixtures = new LinkedHashMap<>();
        try (BufferedReader reader = resource("/fingerprint-evidence.tsv")) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t");
                String key = fields[0] + "\t" + fields[2];
                Evidence fixture = fixtures.computeIfAbsent(key,
                        unused -> new Evidence(fields[1], fields[2],
                                Boolean.parseBoolean(fields[4])));
                fixture.candidates.add(new FingerprintResolver.Candidate(fields[3],
                        Boolean.parseBoolean(fields[5]), Boolean.parseBoolean(fields[6]),
                        Boolean.parseBoolean(fields[7]), Boolean.parseBoolean(fields[8]),
                        Boolean.parseBoolean(fields[9]), Boolean.parseBoolean(fields[10]),
                        Integer.parseInt(fields[11])));
            }
        }
        return fixtures;
    }

    private Map<String, String> readExpected() throws Exception {
        Map<String, String> expected = new LinkedHashMap<>();
        try (BufferedReader reader = resource("/fingerprint-expected.tsv")) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] fields = line.split("\\t");
                expected.put(fields[0] + "\t" + fields[1], fields[2]);
            }
        }
        return expected;
    }

    private BufferedReader resource(String name) {
        InputStream input = getClass().getResourceAsStream(name);
        if (input == null) throw new IllegalStateException("Missing fixture " + name);
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static FingerprintResolver.Candidate candidate(
            String symbol, boolean semantic, int penalty) {
        return new FingerprintResolver.Candidate(symbol, true, semantic,
                true, true, true, true, penalty);
    }

    private static final class Evidence {
        final String hash;
        final String anchor;
        final boolean highRisk;
        final ArrayList<FingerprintResolver.Candidate> candidates = new ArrayList<>();

        Evidence(String hash, String anchor, boolean highRisk) {
            this.hash = hash;
            this.anchor = anchor;
            this.highRisk = highRisk;
        }
    }
}
