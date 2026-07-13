package com.holo.framework.horm.examples;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 四框架用户 CRUD 接口并发性能对比测试。
 *
 * <p>在同一数据库表上，对 HORM、JdbcTemplate、MyBatis-Plus、JPA 的实现进行压测，
 * 输出每个操作的吞吐量和平均延迟。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("h2")
class FrameworkComparisonBenchmark {

    private static final int WARMUP_COUNT = 20;
    private static final int CONCURRENCY = 4;
    private static final int ITERATIONS = 200;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Test
    void runComparison() throws InterruptedException, ExecutionException {
        String[] endpoints = {
            "/api/users",
            "/api/jdbc/users",
            "/api/mybatis/users",
            "/api/jpa/users"
        };

        System.out.println("\n========== Framework CRUD Performance Comparison ==========");
        System.out.printf("%-20s %-10s %-12s %-12s %-12s %-12s%n",
            "Framework", "Operation", "Count", "Total(ms)", "Avg(ms)", "Throughput(op/s)");
        System.out.println("-----------------------------------------------------------");

        for (String endpoint : endpoints) {
            String name;
            if (endpoint.equals("/api/users")) {
                name = "horm";
            } else if (endpoint.equals("/api/jdbc/users")) {
                name = "jdbc";
            } else if (endpoint.equals("/api/mybatis/users")) {
                name = "mybatis";
            } else if (endpoint.equals("/api/jpa/users")) {
                name = "jpa";
            } else {
                name = "unknown";
            }

            warmup(endpoint, name);

            Result create = benchmarkCreate(endpoint, name);
            Result list = benchmarkList(endpoint);
            Result update = benchmarkUpdate(endpoint, name);
            Result delete = benchmarkDelete(endpoint, name);

            printResult(name, "CREATE", create);
            printResult(name, "LIST", list);
            printResult(name, "UPDATE", update);
            printResult(name, "DELETE", delete);
            System.out.println("-----------------------------------------------------------");
        }
    }

    private void printResult(String name, String operation, Result result) {
        System.out.printf("%-20s %-10s %-12d %-12.2f %-12.4f %-12.2f%n",
            name, operation, result.success, result.totalMs, result.avgMs(), result.throughput);
    }

    private void warmup(String endpoint, String name) {
        RestTemplate rest = testRestTemplate.getRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i < WARMUP_COUNT; i++) {
            HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("email", name + "-warmup-" + i + "@example.com", "nickname", "Warmup" + i), headers);
            rest.postForEntity(url(endpoint), request, String.class);
        }
    }

    private Result benchmarkCreate(String endpoint, String name) throws InterruptedException, ExecutionException {
        String url = url(endpoint);
        RestTemplate rest = testRestTemplate.getRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        AtomicInteger counter = new AtomicInteger();

        return runConcurrent(ITERATIONS, CONCURRENCY, () -> {
            int idx = counter.getAndIncrement();
            HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("email", name + "-bench-create-" + idx + "@example.com", "nickname", "Bench" + idx), headers);
            ResponseEntity<String> response = rest.postForEntity(url, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        });
    }

    private Result benchmarkList(String endpoint) throws InterruptedException, ExecutionException {
        String url = url(endpoint);
        RestTemplate rest = testRestTemplate.getRestTemplate();
        return runConcurrent(ITERATIONS, CONCURRENCY, () -> {
            ResponseEntity<String> response = rest.getForEntity(url, String.class);
            return response.getStatusCode().is2xxSuccessful();
        });
    }

    private Result benchmarkUpdate(String endpoint, String name) throws InterruptedException, ExecutionException {
        String url = url(endpoint);
        RestTemplate rest = testRestTemplate.getRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("email", name + "-bench-update-" + i + "@example.com", "nickname", "Before"), headers);
            ResponseEntity<String> response = rest.postForEntity(url, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                ids.add(-1L);
                continue;
            }
            ids.add(extractId(response.getBody()));
        }

        AtomicInteger counter = new AtomicInteger();
        return runConcurrent(ITERATIONS, CONCURRENCY, () -> {
            int idx = counter.getAndIncrement();
            long id = ids.get(idx);
            if (id < 0) {
                return false;
            }
            HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("nickname", "After" + idx), headers);
            ResponseEntity<String> response = rest.exchange(url + "/" + id, HttpMethod.PUT, request, String.class);
            return response.getStatusCode().is2xxSuccessful();
        });
    }

    private Result benchmarkDelete(String endpoint, String name) throws InterruptedException, ExecutionException {
        String url = url(endpoint);
        RestTemplate rest = testRestTemplate.getRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < ITERATIONS; i++) {
            HttpEntity<Map<String, String>> request = new HttpEntity<>(
                Map.of("email", name + "-bench-delete-" + i + "@example.com", "nickname", "Delete"), headers);
            ResponseEntity<String> response = rest.postForEntity(url, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                ids.add(-1L);
                continue;
            }
            ids.add(extractId(response.getBody()));
        }

        AtomicInteger counter = new AtomicInteger();
        return runConcurrent(ITERATIONS, CONCURRENCY, () -> {
            int idx = counter.getAndIncrement();
            long id = ids.get(idx);
            if (id < 0) {
                return false;
            }
            ResponseEntity<String> response = rest.exchange(url + "/" + id, HttpMethod.DELETE, null, String.class);
            return response.getStatusCode().is2xxSuccessful();
        });
    }

    private long extractId(String json) {
        int start = json.indexOf("\"id\":");
        if (start < 0) {
            return -1;
        }
        start += 5;
        int end = json.indexOf(",", start);
        if (end < 0) {
            end = json.indexOf("}", start);
        }
        return Long.parseLong(json.substring(start, end).trim());
    }

    private Result runConcurrent(int iterations, int concurrency, Callable<Boolean> task)
        throws InterruptedException, ExecutionException {

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        List<Future<Boolean>> futures = new ArrayList<>(iterations);
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            futures.add(executor.submit(task));
        }

        int success = 0;
        int failures = 0;
        for (Future<Boolean> future : futures) {
            try {
                if (Boolean.TRUE.equals(future.get())) {
                    success++;
                } else {
                    failures++;
                }
            } catch (Exception e) {
                failures++;
            }
        }
        long elapsedNanos = System.nanoTime() - start;
        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);

        double seconds = elapsedNanos / 1_000_000_000.0;
        double totalMs = elapsedNanos / 1_000_000.0;
        double avgMs = totalMs / Math.max(success, 1);
        double throughput = success / seconds;
        return new Result(success, failures, totalMs, avgMs, throughput);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private record Result(int success, int failures, double totalMs, double avgMs, double throughput) {
    }
}
