import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { App } from './app';

describe('App', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    const app = fixture.componentInstance;
    expect(app).toBeTruthy();

    // detectChanges() dispara ngOnInit(), que a su vez lanza dos
    // peticiones HTTP (hello + version); las respondemos aqui para
    // que httpMock.verify() no falle al final del test.
    fixture.detectChanges();
    httpMock.expectOne('http://localhost:8080/api/hello').flush({
      message: 'Hola de prueba',
      timestamp: '2026-01-01T00:00:00Z',
    });
    httpMock.expectOne('http://localhost:8080/api/version').flush({
      component: 'reference-backend',
      version: '1.0.0',
      javaVersion: '17',
    });
  });

  it('should render el mensaje del backend tras la respuesta HTTP', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    httpMock.expectOne('http://localhost:8080/api/hello').flush({
      message: 'Hola desde el backend de referencia',
      timestamp: '2026-01-01T00:00:00Z',
    });
    httpMock.expectOne('http://localhost:8080/api/version').flush({
      component: 'reference-backend',
      version: '1.0.0',
      javaVersion: '17',
    });

    fixture.detectChanges();
    const compiled = fixture.nativeElement as HTMLElement;
    expect(compiled.querySelector('.card__message')?.textContent).toContain(
      'Hola desde el backend de referencia'
    );
  });
});
