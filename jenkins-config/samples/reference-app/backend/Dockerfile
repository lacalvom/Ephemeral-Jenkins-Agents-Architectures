# =====================================================================
# Dockerfile del backend de referencia (reference-backend)
# =====================================================================
# Este Dockerfile NO compila la aplicacion (eso ya lo hace el stage
# "Construccion Backend (Java/Maven)" del pipeline, en un contenedor
# aparte con mvn). Aqui solo se empaqueta el jar ya construido dentro
# de una imagen minima de ejecucion.
#
# IMPORTANTE: el contexto de build es la raiz del workspace (ver
# "podman build -f backend/Dockerfile ." en reference-pipeline.groovy),
# por eso el COPY usa la ruta "backend/target/..." en vez de "target/...".
# =====================================================================

FROM registry.access.redhat.com/ubi9/openjdk-17-runtime:latest

LABEL org.opencontainers.image.title="reference-backend" \
      org.opencontainers.image.description="Backend de ejemplo (Java 17 + Spring Boot 3) para el pipeline de referencia de jenkins-podman-lab" \
      org.opencontainers.image.source="jenkins-podman-lab/jenkins-config/samples/reference-app/backend"

WORKDIR /deployments

COPY backend/target/reference-backend.jar /deployments/app.jar

EXPOSE 8080

# La imagen base ubi9/openjdk-17-runtime ya corre como usuario no-root
# (185) por convencion de Red Hat, no hace falta declarar USER.

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=5 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/deployments/app.jar"]
