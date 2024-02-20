import interfaces.ApplicationStatusResponse;
import interfaces.ApplicationStatusResponse.Failure;
import interfaces.ApplicationStatusResponse.Success;
import interfaces.Client;
import interfaces.Handler;
import interfaces.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MyHandler implements Handler {
    private static final int N_THREADS = 4;
    private static final int TIMEOUT = 15;

    private final Client client;
    private final ExecutorService executorService;
    AtomicInteger retriesCount = new AtomicInteger(0);
    AtomicReference<Duration> lastRequestTime = new AtomicReference<>();

    public MyHandler(Client client) {
        this(client, N_THREADS);
    }

    public MyHandler(Client client, int nThreads) {
        this.client = client;
        this.executorService = Executors.newFixedThreadPool(nThreads);
    }

    @Override
    public ApplicationStatusResponse performOperation(String id) {
        final Instant start = Instant.now();

        CompletableFuture<Response> responseFuture1 = CompletableFuture
                .supplyAsync(() -> client.getApplicationStatus1(id), executorService);
        CompletableFuture<Response> responseFuture2 = CompletableFuture
                .supplyAsync(() -> client.getApplicationStatus2(id), executorService);

        CompletableFuture<ApplicationStatusResponse> anyStatus = CompletableFuture
                .anyOf(responseFuture1, responseFuture2)
                .thenApplyAsync(obj -> {
                    if (obj instanceof Response.Success success) {
                        return new Success(success.applicationId(), success.applicationStatus());
                    } else if (obj instanceof Response.Failure failure) {
                        System.err.println(failure.ex().getMessage());
                        return new Failure(Duration.between(start, Instant.now()), retriesCount.get());
                    } else if (obj instanceof Response.RetryAfter retryAfter) {
                        retriesCount.incrementAndGet();
                        lastRequestTime.set(Duration.between(start, Instant.now()));
                        try {
                            Thread.sleep(retryAfter.delay().toMillis());
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return performOperation(id); // Повтор операции
                    } else {
                        throw new IllegalStateException();
                    }
                }, executorService);

        try {
            return anyStatus.get(TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            return new Failure(Duration.between(start, Instant.now()), retriesCount.get());
        }
    }

    public void stop() {
        executorService.shutdownNow();
    }
}
