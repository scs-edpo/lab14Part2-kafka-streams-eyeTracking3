package magicalpipelines.topology;

import magicalpipelines.model.aggregations.FixationStats;
import magicalpipelines.model.join.FixationClick;
import magicalpipelines.serialization.Click;
import magicalpipelines.serialization.Fixation;
import magicalpipelines.timestampExtractors.ClickTimestampExtractor;
import magicalpipelines.timestampExtractors.FixationTimestampExtractor;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;

import java.time.Duration;

import static magicalpipelines.fixationProcessing.Processor.findAOI;
import static magicalpipelines.serialization.json.JsonSerdes.jsonSerde;

public class EyeTrackingTopology {


    public static Topology build() {

        // The builder is used to construct the topology
        StreamsBuilder builder = new StreamsBuilder();

       //Define Serdes
        Serde<Fixation> fixationSerde = jsonSerde(Fixation.class);
        Serde<FixationStats> fixationStatsSerde = jsonSerde(FixationStats.class);
        Serde<Click> clickSerde = jsonSerde(Click.class);
        Serde<FixationClick> fixationClickSerde = jsonSerde(FixationClick.class);


        // Streaming Fixation events
        Consumed<byte[], Fixation> fixationConsumerOptions =
                Consumed.with(Serdes.ByteArray(), fixationSerde)
                        .withTimestampExtractor(new FixationTimestampExtractor());

        KStream<byte[], Fixation> fixationStream =
                // register the fixation-events stream
                builder.stream("fixations", fixationConsumerOptions);

        fixationStream.print(Printed.<byte[], Fixation>toSysOut().withLabel("fixations-stream"));

        // Stateless processing for fixations

        // Apply content filter to fixations // Keep only relevant attributes
        KStream<byte[], Fixation> contentFilteredFixations =
                fixationStream.mapValues(
                        (fixation) -> {
                            Fixation contentFilteredFixation = new Fixation();
                            contentFilteredFixation.setTimestamp(fixation.getTimestamp());
                            contentFilteredFixation.setXpos(fixation.getXpos());
                            contentFilteredFixation.setYpos(fixation.getYpos());
                            contentFilteredFixation.setFixationDuration(fixation.getFixationDuration());
                            contentFilteredFixation.setPupilSize(fixation.getPupilSize());
                            return contentFilteredFixation;
                        });


        // Apply event filter to fixations
        // Keep only fixations with duration above or equal to 60 ms
        KStream<byte[], Fixation> eventFilteredFixations =
                contentFilteredFixations.filter(
                        (key, fixation) -> {
                            return (fixation.getFixationDuration()>=60);
                        });

        // Apply event translator
        // find AOI based on xpos and ypos
        KStream<byte[], Fixation> eventTranslatedFixations =
                eventFilteredFixations.mapValues(
                        (fixation) -> {
                            String aoi = findAOI(fixation.getXpos(),fixation.getYpos()).toString();
                            fixation.setAoi(aoi);
                            return fixation;
                        });

        // Stateful processing for fixations

        // Window config for fixation events
        TimeWindows tumblingWindowFixations =
                TimeWindows.ofSizeAndGrace(Duration.ofSeconds(5), Duration.ofSeconds(1));


        // aggregation: fixation count, average fixation duration, total fixation duration per AOI
        Initializer<FixationStats> fixationStatsInitializer = () -> new FixationStats(0,0,0);

        Aggregator<String, Fixation, FixationStats> fixationStatsAggregator = (key, fixation, fixationStats) -> {
            long newFixationCount = fixationStats.getFixationCount() + 1;
            double newTotalFixationDuration = fixationStats.getTotalFixationDuration() + fixation.getFixationDuration();
            double newAverageFixationDuration = newTotalFixationDuration / newFixationCount;
            return new FixationStats(newFixationCount, newTotalFixationDuration, newAverageFixationDuration);
        };


        // Group fixations by AOI, Window by tumblingWindowFixations, Aggregate, Materialize, suppress
        KTable<Windowed<String>, FixationStats> fixationStats =
                eventTranslatedFixations
                        // group by AOI
                        .groupBy((key, value) -> value.getAoi(),
                                Grouped.<String, Fixation>with(Serdes.String(), fixationSerde))
                        // windowing by config
                        .windowedBy(tumblingWindowFixations)
                        // windowed aggregation
                        .aggregate(
                                fixationStatsInitializer,
                                fixationStatsAggregator,
                                Materialized.<String, FixationStats, WindowStore<Bytes, byte[]>>as("fixationStats")
                                        .withKeySerde(Serdes.String())
                                        .withValueSerde(fixationStatsSerde)
                        )
                        // suppress
                        .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded().shutDownWhenFull()));


        // Streaming Click events
        Consumed<String, Click> clickConsumerOptions =
                Consumed.with(Serdes.String(), clickSerde)
                        .withTimestampExtractor(new ClickTimestampExtractor());

        KStream<String, Click> clickStream =
                // register the click-events stream
                builder.stream("clicks", clickConsumerOptions);

        clickStream.print(Printed.<String, Click>toSysOut().withLabel("clicks-stream"));

        // Stateful processing for click events

        // Window config for click events
        TimeWindows tumblingWindowClicks =
                TimeWindows.ofSizeAndGrace(Duration.ofSeconds(5), Duration.ofSeconds(1));

        //Group clicks by AOI, Window by tumblingWindowClicks, Aggregage:count, Materialize, suppress
        KTable<Windowed<String>, Long> clickCounts =
                clickStream
                        // group by AOI
                        .groupBy((key, value) -> value.getAoi(),
                                Grouped.<String, Click>with(Serdes.String(), clickSerde))
                        // windowing by config
                        .windowedBy(tumblingWindowClicks)
                        // windowed aggregation
                        .count(Materialized.as("clickCount"))
                        // suppress
                        .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded().shutDownWhenFull()));


        // joining fixations and clicks

        //convert KTables to Streams
        KStream<String, Long> clickCountsStream = clickCounts
                .toStream()
                // windowedKey.key() allows us to access the AOI key
                .map(
                        (windowedKey, value) -> {
                            return KeyValue.pair(windowedKey.key(), value);
                        });

        KStream<String, FixationStats> fixationStatsStream = fixationStats
                .toStream()
                // windowedKey.key() allows us to access the AOI key
                .map(
                        (windowedKey, value) -> {
                            return KeyValue.pair(windowedKey.key(), value);
                        });

        //clickCountsStream.print(Printed.<String, Long>toSysOut().withLabel("clickCountsStream"));
        //fixationStatsStream.print(Printed.<String, FixationStats>toSysOut().withLabel("fixationStatsStream"));

        StreamJoined<String, FixationStats, Long> joinParams =
                StreamJoined.with(Serdes.String(), fixationStatsSerde, Serdes.Long())
                        .withKeySerde(Serdes.String())
                        .withValueSerde(fixationStatsSerde);

        JoinWindows joinWindows =
                JoinWindows.ofTimeDifferenceAndGrace(
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1));


        KStream<String, FixationClick> fixationClickJoined =
                fixationStatsStream.join(
                        clickCountsStream,
                        (fixationstats, clickCount) -> new FixationClick(fixationstats, clickCount),
                        joinWindows,
                        joinParams

                );


        KTable<String, FixationClick> fixationClickTable = fixationClickJoined.groupByKey()
                .reduce((aggValue, newValue) -> newValue,
                        Materialized.<String, FixationClick, KeyValueStore<Bytes, byte[]>>as("FixationClickStats")
                                .withKeySerde(Serdes.String())
                                .withValueSerde(fixationClickSerde));


        return builder.build();
    }
}

