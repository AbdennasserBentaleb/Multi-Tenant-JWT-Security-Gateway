package dev.gateway.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextConcurrencyTest {

    @Test
    @DisplayName("ScopedValue strictly isolates tenant configurations across 10,000 concurrent virtual threads without race conditions")
    void verifyStrictConcurrencyIsolationUnderLoad() throws InterruptedException, ExecutionException {
        int threadCount = 10_000;
        
        // Simulating Spring Boot's Virtual Thread per Task model
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch readyLatch = new CountDownLatch(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            List<Callable<Boolean>> tasks = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                final UUID tenantIdForThisThread = UUID.randomUUID();

                tasks.add(() -> {
                    readyLatch.countDown();
                    // Block until all 10,000 threads are perfectly aligned at the starting line
                    startLatch.await();

                    // Bind the tenant context for this specific virtual thread
                    return ScopedValue.where(TenantContext.CURRENT_TENANT, tenantIdForThisThread).call(() -> {
                        UUID retrievedTenantId = TenantContext.getCurrentTenant();
                        doneLatch.countDown();
                        return tenantIdForThisThread.equals(retrievedTenantId);
                    });
                });
            }

            // Submit all tasks
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(executor.submit(task));
            }

            // Wait for all virtual threads to initialize
            readyLatch.await();
            
            // Release the hounds! Execute all 10,000 mappings simultaneously
            startLatch.countDown();
            
            // Wait for execution completion
            doneLatch.await();

            // Assert that not a single thread experienced context bleeding/race conditions
            for (Future<Boolean> future : futures) {
                assertThat(future.get()).isTrue();
            }
        }
    }
}
