package dev.traceback.indexing;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;
@Configuration
public class IndexObservability {
    public IndexObservability(IndexRepository repository,MeterRegistry metrics) {
        for(String status:new String[]{"PENDING_EXTRACTION","EXTRACTING","EXTRACTED","INDEXING","INDEXED","RETRY","FAILED","CANCELLED"})
            metrics.gauge("traceback.indexing.jobs",java.util.List.of(io.micrometer.core.instrument.Tag.of("state",status)),repository,r->r.count(status));
        metrics.gauge("traceback.indexing.expired.leases",repository,IndexRepository::expired);
        metrics.gauge("traceback.indexing.visible.revisions",repository,IndexRepository::visibleRevisions);
    }
}
