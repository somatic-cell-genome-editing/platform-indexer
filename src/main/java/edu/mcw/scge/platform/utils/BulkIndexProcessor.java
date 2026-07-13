package edu.mcw.scge.platform.utils;

import co.elastic.clients.elasticsearch._helpers.bulk.BulkIngester;
import co.elastic.clients.elasticsearch._helpers.bulk.BulkListener;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import edu.mcw.scge.services.ESClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class BulkIndexProcessor {
    public static BulkIngester<Void> ingester = null;
    private static BulkIndexProcessor bulkIndexProcessor = null;

    private BulkIndexProcessor() {}

    public static BulkIndexProcessor getInstance() {
        if (bulkIndexProcessor == null) {
            bulkIndexProcessor = new BulkIndexProcessor();
            ingester = init();
        }
        return bulkIndexProcessor;
    }

    private static BulkIngester<Void> init() {
        System.out.println("CREATING NEW BULK INGESTER....");

        BulkListener<Void> listener = new BulkListener<>() {
            @Override
            public void beforeBulk(long executionId, BulkRequest request, List<Void> contexts) {
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, List<Void> contexts,
                                  BulkResponse response) {
                if (response.errors()) {
                    int failed = 0;
                    StringBuilder firstErrors = new StringBuilder();
                    for (BulkResponseItem item : response.items()) {
                        if (item.error() != null) {
                            failed++;
                            if (failed <= 3) {
                                firstErrors.append("[").append(item.error().type())
                                        .append(": ").append(item.error().reason()).append("] ");
                            }
                        }
                    }
                    System.err.println("BULK had item failures executionId=" + executionId
                            + " actions=" + request.operations().size()
                            + " failed=" + failed + " : " + firstErrors);
                }
            }

            @Override
            public void afterBulk(long executionId, BulkRequest request, List<Void> contexts,
                                  Throwable failure) {
                System.err.println("BULK FAILED executionId=" + executionId
                        + " actions=" + request.operations().size() + " : " + failure);
                failure.printStackTrace();
            }
        };

        return BulkIngester.of(b -> b
                .client(ESClient.getClient())
                .maxOperations(10000)
                .maxSize(5L * 1024 * 1024)
                .flushInterval(5, TimeUnit.SECONDS)
                .maxConcurrentRequests(10)
                .listener(listener)
        );
    }

    public void destroy() {
        if (ingester != null) {
            try {
                ingester.close();
            } finally {
                ingester = null;
                bulkIndexProcessor = null;
            }
        }
    }
}
