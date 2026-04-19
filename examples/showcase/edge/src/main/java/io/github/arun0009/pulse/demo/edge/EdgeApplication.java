package io.github.arun0009.pulse.demo.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestClient;

@SpringBootApplication
@EnableAsync
public class EdgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeApplication.class, args);
    }

    @Bean
    RestClient downstreamClient(
            RestClient.Builder builder, @Value("${downstream.url:http://localhost:8090}") String base) {
        return builder.baseUrl(base).build();
    }
}
