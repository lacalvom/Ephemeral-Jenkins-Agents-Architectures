# reference-app — Aplicación de ejemplo para el pipeline de referencia

Backend (Java 17 + Spring Boot 3 + Maven) y frontend (Angular 20 + npm)
mínimos pero **funcionales**, empleados exclusivamente para probar el
`reference-pipeline` del laboratorio `Podman-Cloud`.

No se utiliza base de datos ni SCM: Ansible copia este directorio
directamente al workspace del agente en cada `deploy.sh` (ver
`ansible/roles/podman_host/tasks/main.yml`), y el pipeline los
compila y empaqueta mediante agentes efímeros de Podman.

## Estructura

```
reference-app/
├── backend/             # Spring Boot 3, Java 17, expone /api/hello y /api/version
│   ├── pom.xml
│   ├── src/
│   └── Dockerfile       # empaqueta backend/target/*.jar (ya compilado)
├── frontend/            # Angular 20 standalone, consume el backend
│   ├── package.json
│   ├── angular.json
│   ├── src/
│   └── Dockerfile       # empaqueta frontend/dist/.../browser (ya compilado)
└── podman-compose.yml   # entorno de prueba manual (NO lo usa el pipeline)
```

## Relación con el pipeline

El `reference-pipeline` (Jenkins) ejecuta, en este orden:

1. **Construcción Backend**: `mvn clean package` dentro del agente
   `agent-maven-jdk17` → genera `backend/target/reference-backend.jar`.
2. **Construcción Frontend**: `npm install && npm run build` dentro del
   agente `agent-node20` → genera
   `frontend/dist/reference-frontend/browser/`.
3. **Empaquetar Imagen Backend**: `podman build -f backend/Dockerfile .`
   (contexto = raíz del workspace) → imagen `reference-backend:latest`.
4. **Empaquetar Imagen Frontend**: `podman build -f frontend/Dockerfile .`
   → imagen `reference-frontend:latest`.

Los `Dockerfile` de este directorio **no compilan nada**: solo copian los
artefactos generados en los stages 1 y 2. Por ello, para reconstruir las
imágenes manualmente fuera de Jenkins se compila primero (ver más abajo).

## Prueba manual (sin Jenkins)

### 1. Compilar el backend

El `pom.xml` activa `maven-toolchains-plugin` (JDK 17), por lo que se le debe
proporcionar un `toolchains.xml`. La opción más sencilla es utilizar la
imagen-agente, que ya lo incluye en `/opt/toolchains/toolchains.xml`:

```bash
cd backend
podman run --rm -v "$(pwd)":/build:z -w /build --user 0 \
  localhost/agent-maven-jdk17:latest \
  mvn -t /opt/toolchains/toolchains.xml -B -ntp clean package
```

Esto genera `backend/target/reference-backend.jar` y ejecuta los tests
(`HelloControllerTest`).

> Si la compilación se realiza con una imagen JDK 17 "pelada" (p. ej.
> `ubi9/openjdk-17`), se debe aportar un `toolchains.xml` propio y
> proporcionarlo con `mvn -t ...`; en caso contrario el build falla con
> `Cannot find matching toolchain definitions` (ver ADR-0006).

### 2. Compilar el frontend

```bash
cd frontend
podman run --rm -v "$(pwd)":/build:z -w /build \
  registry.access.redhat.com/ubi9/nodejs-20:latest \
  bash -c "npm install && npm run build"
```

Esto genera `frontend/dist/reference-frontend/browser/`.

### 3. Construir las imágenes

Desde la raíz de `reference-app/` (el contexto de build es esta carpeta,
no `backend/` ni `frontend/`):

```bash
podman build --format docker -t reference-backend:latest  -f backend/Dockerfile  .
podman build --format docker -t reference-frontend:latest -f frontend/Dockerfile .
```

`--format docker` es necesario para que el `HEALTHCHECK` de cada `Dockerfile`
quede embebido en la imagen (Podman usa formato OCI por defecto, que lo
ignora).

### 4. Levantar el entorno completo con `podman-compose`

```bash
podman compose -f podman-compose.yml up -d
curl http://localhost:8080/api/hello   # backend
curl http://localhost:4200/            # frontend (o ábrelo en el navegador)
podman compose -f podman-compose.yml down
```

El frontend llama al backend **desde el navegador del usuario**, en
`http://localhost:8080` (ver `frontend/src/app/hello.service.ts`). Por
ello, el `podman-compose.yml` publica el puerto 8080 del backend en el
host: sin esa publicación, la llamada del navegador fallaría aunque los
contenedores estuvieran en ejecución.

## Notas de diseño

- **Sin base de datos:** el backend responde con datos en memoria; el
  objetivo es validar el pipeline, no representar una arquitectura de
  referencia productiva.
- **URL del backend fija a `localhost:8080`:** es una decisión
  deliberada para simplificar las pruebas manuales. Para desplegar detrás
  de un dominio real, se parametriza `hello.service.ts` (por ejemplo
  mediante un fichero de entorno de Angular).
- **Tag `artifactory.mi-empresa.local/...`:** lo genera el pipeline como
  imitación del nombre de un registro interno real (para evidenciar
  dónde se realizaría `podman push` en un entorno de producción). Además,
  el pipeline crea un segundo tag `reference-backend:latest` /
  `reference-frontend:latest` sin ese prefijo, que es el que consume
  `podman-compose.yml` para no depender del número de build.
