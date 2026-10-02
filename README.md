# EscudoCall Android

App nativa de Android que **bloquea de verdad** las llamadas: corta desconocidos,
números privados/ocultos y lista negra, y **deja pasar SIEMPRE a los contactos**.

Usa el mecanismo oficial de Android `CallScreeningService` (rol
*Identificación de llamadas y spam*), el mismo que usan Truecaller y similares.

## Cómo se compila

GitHub Actions lo hace solo: cada push a `main` ejecuta
`.github/workflows/build.yml`, que compila el APK debug firmado y lo publica
en el release `latest` de este repositorio.

Descarga directa (link estable):

```
https://github.com/SEMAIN2026/escudocall-android/releases/latest/download/EscudoCall.apk
```

## Variables de compilación

El APK recibe las credenciales de sincronización vía variables de entorno al
compilar (secrets del repositorio):

- `TURSO_URL` — URL de la base de datos Turso (libsql)
- `TURSO_TOKEN` — token de acceso

Sin ellas, la app funciona igual en modo local (sin sincronización).

## Primer arranque (permisos)

1. **Contactos** — para saber quién es tu gente (nunca se bloquean).
2. **Notificaciones** (opcional) — aviso cuando se corta una llamada.
3. **Activar protección** — Android abre la ventana del sistema
   *«Identificación de llamadas y spam»*: elige EscudoCall y actívala.
   Ese es el permiso real de bloqueo de llamadas.

## Prioridad de decisiones

1. Lista negra → SIEMPRE se corta (aunque esté en contactos)
2. Número privado/oculto → se corta (ajuste)
3. Contactos → SIEMPRE pasan
4. Lista blanca (sincronizada del panel web) → pasan
5. Internacional (no +52) → se corta (ajuste)
6. Desconocidos (no en contactos) → se cortan (ajuste)
