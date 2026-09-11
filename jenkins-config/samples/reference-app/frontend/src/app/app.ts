import { Component, OnInit, signal } from '@angular/core';
import { HelloService } from './hello.service';

@Component({
  selector: 'app-root',
  imports: [],
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App implements OnInit {
  protected readonly title = signal('reference-frontend');

  protected readonly backendMessage = signal<string>('Consultando backend...');
  protected readonly backendTimestamp = signal<string>('');
  protected readonly backendVersion = signal<string>('');
  protected readonly backendError = signal<string>('');

  constructor(private readonly helloService: HelloService) {}

  ngOnInit(): void {
    this.helloService.getHello().subscribe({
      next: (res) => {
        this.backendMessage.set(res.message);
        this.backendTimestamp.set(res.timestamp);
      },
      error: () => {
        this.backendError.set(
          'No se pudo contactar con el backend en http://localhost:8080. ' +
          '¿Esta arrancado el contenedor reference-backend con el puerto 8080 publicado?'
        );
      }
    });

    this.helloService.getVersion().subscribe({
      next: (res) => this.backendVersion.set(`${res.component} v${res.version} (Java ${res.javaVersion})`),
      error: () => { /* el error principal ya se muestra desde getHello() */ }
    });
  }
}
