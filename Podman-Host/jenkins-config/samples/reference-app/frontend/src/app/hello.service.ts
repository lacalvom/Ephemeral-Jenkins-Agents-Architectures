import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Respuesta del endpoint GET /api/hello del backend de referencia.
 */
export interface HelloResponse {
  message: string;
  timestamp: string;
}

/**
 * Respuesta del endpoint GET /api/version del backend de referencia.
 */
export interface VersionResponse {
  component: string;
  version: string;
  javaVersion: string;
}

/**
 * Cliente HTTP hacia el backend de referencia.
 *
 * La URL esta fijada a http://localhost:8080 de forma deliberada (ver
 * README.md de esta app): las llamadas las hace el navegador del
 * usuario, no el contenedor del frontend, asi que basta con publicar
 * el puerto 8080 del backend en el host (ver podman-compose.yml) para
 * que esto funcione sin importar donde corran los contenedores.
 */
@Injectable({ providedIn: 'root' })
export class HelloService {
  private readonly backendBaseUrl = 'http://localhost:8080';

  constructor(private readonly http: HttpClient) {}

  getHello(): Observable<HelloResponse> {
    return this.http.get<HelloResponse>(`${this.backendBaseUrl}/api/hello`);
  }

  getVersion(): Observable<VersionResponse> {
    return this.http.get<VersionResponse>(`${this.backendBaseUrl}/api/version`);
  }
}
