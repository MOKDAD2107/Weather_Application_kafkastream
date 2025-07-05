package ma.enset;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;

import java.util.Properties;

public class MeteoAppStream {
    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "meteoAppStream-v2");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams");

        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, String> source = builder.stream("weather-data");
        source.foreach((key, value) -> System.out.println("----RECEIVED----: " + value));

        // Filtrage des températures > 30
        KStream<String, String> filtred = source.filter((key, value) -> {
            String[] parts = value.split(",");
            if (parts.length < 3) return false;
            try {
                boolean keep = Double.parseDouble(parts[1]) > 30;
                if (keep)
                    System.out.println("FILTERED: " + value);
                return keep;
            } catch (NumberFormatException e) {
                System.err.println("nombre invalid: " + value);
                return false;
            }
        });

        // Conversion en Fahrenheit
        KStream<String, String> converted = filtred.mapValues(value -> {
            try {
                String[] parts = value.split(",");
                double tempC = Double.parseDouble(parts[1]);
                double tempF = (tempC * 9 / 5) + 32;
                String result = parts[0] + "," + tempF + "," + parts[2];
                System.out.println(" CONVERTED: " + result);
                return result;
            } catch (Exception e) {
                System.err.println("Error pendant la convertion: " + value);
                return value;
            }
        });

        // Groupement par station
        KGroupedStream<String, String> grouped = converted.groupBy(
                (key, value) -> value.split(",")[0],
                Grouped.with(Serdes.String(), Serdes.String())

        );

        // Agrégation (température moyenne, humidité moyenne)
        KTable<String, String> total = grouped.aggregate(
                () -> "0.0,0.0,0",
                (key, value, aggregate) -> {
                    try {
                        String[] valParts = value.split(",");
                        String[] aggParts = aggregate.split(",");
                        double sumTemp = Double.parseDouble(aggParts[0]) + Double.parseDouble(valParts[1]);
                        double sumHum = Double.parseDouble(aggParts[1]) + Double.parseDouble(valParts[2]);
                        int count = Integer.parseInt(aggParts[2]) + 1;

                        System.out.println("SUM Temp: " + sumTemp + ", SUM Hum: " + sumHum + ", Count: " + count);
                        return sumTemp + "," + sumHum + "," + count;
                    } catch (Exception e) {
                        System.err.println("Error in aggregation: " + value + " | " + aggregate);
                        return aggregate;
                    }
                },
                Materialized.with(Serdes.String(), Serdes.String())
        );

        // Écriture dans le topic station-averages:
        total.toStream().mapValues(value-> {
            String[] parts = value.split(",");
            double avgTemp = Double.parseDouble(parts[0]) / Integer.parseInt(parts[2]);
            double avgHum = Double.parseDouble(parts[1]) / Integer.parseInt(parts[2]);

            return String.format("Température Moyenne = %.2f°F, Humidité Moyenne = %.2f%%", avgTemp, avgHum);
        }).to("station-averages", Produced.with(Serdes.String(), Serdes.String()));

        // Lancement
        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        streams.start();
        System.out.println(" Kafka Streams started!");

        // Arrêt propre
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

        // Garder l'application active
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
