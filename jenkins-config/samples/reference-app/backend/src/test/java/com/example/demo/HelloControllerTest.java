package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Test de humo: arranca el contexto completo en un puerto aleatorio y
 * comprueba que /api/hello responde. Sirve para validar en el pipeline
 * que el jar generado realmente funciona, no solo que compila.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HelloControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void helloEndpointRespondeConMensaje() {
        String body = restTemplate.getForObject("http://localhost:" + port + "/api/hello", String.class);
        assertThat(body).contains("message");
        assertThat(body).contains("Hola desde el backend de referencia");
    }

    @Test
    void versionEndpointRespondeConVersion() {
        String body = restTemplate.getForObject("http://localhost:" + port + "/api/version", String.class);
        assertThat(body).contains("reference-backend");
    }
}
