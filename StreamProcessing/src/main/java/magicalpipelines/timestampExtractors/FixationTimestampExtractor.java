package magicalpipelines.timestampExtractors;

import magicalpipelines.serialization.Fixation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

import java.time.Instant;


public class FixationTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Fixation fixation = (Fixation) record.value();
        return fixation.getTimestamp();
    }

}