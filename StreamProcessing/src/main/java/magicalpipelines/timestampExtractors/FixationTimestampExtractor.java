package magicalpipelines.timestampExtractors;

import magicalpipelines.serialization.Fixation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Instant;

/** This class allows us to use event-time semantics for purchase streams */
public class FixationTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Fixation fixation = (Fixation) record.value();
        return fixation.getTimestamp();
    }

}