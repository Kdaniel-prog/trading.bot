package kd.trading.bot.config;

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

@Configuration
public class BinanceConfigBeans {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newHttpClient();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // ha kell, idő/dátum modul
        mapper.registerModule(new JavaTimeModule());
        // itt állíthatsz globális opciókat (pl. null kezelés, dátum formátum, stb.)
        return mapper;
    }
}
