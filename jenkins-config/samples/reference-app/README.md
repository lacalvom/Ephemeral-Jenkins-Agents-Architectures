# reference-app — Aplicación de ejemplo para el pipeline de referencia

Backend (Java 17 + Spring Boot 3 + Maven) y frontend (Angular 20 + npm)
mínimos pero **funcionales**, usados exclusivamente para probar el
`reference-pipeline` del laboratorio `jenkins-podman-lab`.

No usan base de datos ni SCM: Ansible copia este directorio
directamente al workspace del agente en cada `deploy.sh` (ver
`ansible/roles/podman_host/tasks/main.yml`), y el pipeline los
compila/empaqueta usando agentes efímeros de Podman.

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

## Cómo se relaciona con el pipeline

El `reference-pipeline` (Jenkins) hace, en este orden:

1. **Construcción Backend**: `mvn clean package` dentro de un contenedor
   `ubi9/openjdk-17` → genera `backend/target/reference-backend.jar`.
2. **Construcción Frontend**: `npm install && npm run build` dentro de
   un contenedor `ubi9/nodejs-20` → genera
   `frontend/dist/reference-frontend/browser/`.
3. **Empaquetar Imagen Backend**: `podman build -f backend/Dockerfile .`
   (contexto = raíz del workspace) → imagen `reference-backend:latest`.
4. **Empaquetar Imagen Frontend**: `podman build -f frontend/Dockerfile .`
   → imagen `reference-frontend:latest`.

Los `Dockerfile` de este directorio **no compilan nada**: solo copian
los artefactos ya generados en los stages 1 y 2. Por eso, si quieres
reconstruir las imágenes manualmente fuera de Jenkins, tienes que
compilar primero (ver más abajo).

## Probarlo manualmente (sin Jenkins)

### 1. Compilar el backend

```bash
cd backend
podman run --rm -v "$(pwd)":/build:z -w /build \
  registry.access.redhat.com/ubi9/openjdk-17:latest \
  mvn -B -ntp clean package
```

Esto genera `backend/target/reference-backend.jar` y ejecuta los tests
(`HelloControllerTest`).

### 2. Compilar el frontend

```bash
cd frontend
podman run --rm -v "$(pwd)":/build:z -w /build \
  registry.access.redhat.com/ubi9/nodejs-20:latest \
  bash -c "npm install && npm run build"
```

Esto genera `frontend/dist/reference-frontend/browser/`.

### 3. Construir las imágenes

Desde la raíz de `reference-app/` (el contexto de build es esta
carpeta, no `backend/` ni `frontend/`):

```bash
podman build --format docker -t reference-backend:latest  -f backend/Dockerfile  .
podman build --format docker -t reference-frontend:latest -f frontend/Dockerfile .
```

`--format docker` es necesario para que el `HEALTHCHECK` de cada
`Dockerfile` quede embebido en la imagen (Podman usa formato OCI por
defecto, que lo ignora).

### 4. Levantar el entorno completo con podman-compose

```bash
podman compose -f podman-compose.yml up -d
curl http://localhost:8080/api/hello   # backend
curl http://localhost:4200/            # frontend (o ábrelo en el navegador)
podman compose -f podman-compose.yml down
```

El frontend llama al backend **desde el navegador del usuario**, en
`http://localhost:8080` (ver `frontend/src/app/hello.service.ts`). Por
eso el `podman-compose.yml` publica el puerto 8080 del backend en el
host: sin esa publicación, la llamada del navegador fallaría aunque
los contenedores estén corriendo.

## Notas de diseño

- **Sin base de datos**: el backend responde con datos en memoria; el
  objetivo es validar el pipeline, no representar una arquitectura de
  referencia productiva.
- **URL del backend fija a `localhost:8080`**: es una decisión
  deliberada para simplificar las pruebas manuales. Si en el futuro se
  quiere desplegar detrás de un dominio real, hay que parametrizar
  `hello.service.ts` (por ejemplo con un fichero de entorno de Angular).
- **Tag `artifactory.mi-empresa.local/...`**: lo genera el pipeline
  imitando el nombre de un registry interno real (para que quede claro
  dónde se haría el `podman push` en un entorno de producción). Además,
  el pipeline crea un segundo tag `reference-backend:latest` /
  `reference-frontend:latest` sin ese prefijo, que es el que consume
  `podman-compose.yml` para no depender del número de build.
