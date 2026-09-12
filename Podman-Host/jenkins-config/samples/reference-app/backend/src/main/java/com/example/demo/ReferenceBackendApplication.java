package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto de entrada de la aplicacion.
 *
 * Este backend es deliberadamente minimo: su unico proposito es servir
 * como artefacto de ejemplo para validar el pipeline de referencia del
 * laboratorio jenkins-podman-lab (compilacion con Maven, empaquetado en
 * imagen de contenedor con Podman, y arranque via podman-compose).
 */
@SpringBootApplication
public class ReferenceBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReferenceBackendApplication.class, args);
    }
}
