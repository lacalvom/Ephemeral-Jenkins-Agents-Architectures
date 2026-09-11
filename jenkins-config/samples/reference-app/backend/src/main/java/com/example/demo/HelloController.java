package com.example.demo;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador REST minimo. Expone:
 *
 *   GET /api/hello   -> mensaje de saludo + timestamp
 *   GET /api/version -> version del backend (leida del pom.xml via
 *                        BuildProperties, si esta disponible)
 *
 * CrossOrigin abierto a * porque este backend solo se usa en el
 * laboratorio para probar el pipeline; en un caso real habria que
 * restringir el origen al dominio del frontend.
 */
@RestController
@CrossOrigin(origins = "*")
public class HelloController {

    private static final String APP_VERSION = "1.0.0";

    @GetMapping("/api/hello")
    public Map<String, Object> hello() {
        return Map.of(
                "message", "Hola desde el backend de referencia (Java 17 + Spring Boot 3)",
                "timestamp", Instant.now().toString()
        );
    }

    @GetMapping("/api/version")
    public Map<String, String> version() {
        return Map.of(
                "component", "reference-backend",
                "version", APP_VERSION,
                "javaVersion", System.getProperty("java.version")
        );
    }
}
