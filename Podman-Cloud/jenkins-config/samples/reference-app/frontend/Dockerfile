# =====================================================================
# Dockerfile del frontend de referencia (reference-frontend)
# =====================================================================
# Igual que en el backend: este Dockerfile NO compila la aplicacion
# (eso lo hace el stage "Construccion Frontend (Node/npm)" del pipeline
# con npm run build). Aqui solo se empaqueta el resultado ya generado
# (dist/reference-frontend/browser) dentro de una imagen Nginx minima.
#
# IMPORTANTE: el contexto de build es la raiz del workspace (ver
# "podman build -f frontend/Dockerfile ." en reference-pipeline.groovy),
# por eso el COPY usa "frontend/dist/..." en vez de "dist/...".
#
# La imagen ubi9/nginx-126 escucha por defecto en el puerto 8080 (no
# privilegiado) y corre como usuario no-root (uid 1001).
# =====================================================================

FROM registry.access.redhat.com/ubi9/nginx-126:latest

LABEL org.opencontainers.image.title="reference-frontend" \
      org.opencontainers.image.description="Frontend de ejemplo (Angular 20) para el pipeline de referencia de Ephemeral-Jenkins-Agents-Architectures (Podman-Cloud)" \
      org.opencontainers.image.source="Ephemeral-Jenkins-Agents-Architectures/Podman-Cloud/jenkins-config/samples/reference-app/frontend"

COPY frontend/dist/reference-frontend/browser/ /opt/app-root/src/

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=15s --retries=5 \
    CMD curl -sf http://localhost:8080/ || exit 1

# El CMD por defecto de la imagen base (heredado del ENTRYPOINT S2I)
# solo imprime un mensaje de ayuda y termina; hay que invocar
# explicitamente el script que realmente arranca nginx en foreground.
CMD ["/usr/libexec/s2i/run"]
