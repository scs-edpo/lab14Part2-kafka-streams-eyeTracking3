package magicalpipelines.timestampExtractors;

import magicalpipelines.serialization.Click;
import magicalpipelines.serialization.Fixation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.streams.processor.TimestampExtractor;

/** This class allows us to use event-time semantics for purchase streams */
public class ClickTimestampExtractor implements TimestampExtractor {

    @Override
    public long extract(ConsumerRecord<Object, Object> record, long partitionTime) {
        Click click = (Click) record.value();
        return click.getTimestamp();
    }

}