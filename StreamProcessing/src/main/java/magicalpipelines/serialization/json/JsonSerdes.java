package magicalpipelines.serialization.json;


import magicalpipelines.serialization.Fixation;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;

public class JsonSerdes {

  public static <T> Serde<T> jsonSerde(Class<T> valueType) {
    JsonSerializer<T> serializer = new JsonSerializer<>();
    JsonDeserializer<T> deserializer = new JsonDeserializer<>(valueType);
    return Serdes.serdeFrom(serializer, deserializer);
  }


}
