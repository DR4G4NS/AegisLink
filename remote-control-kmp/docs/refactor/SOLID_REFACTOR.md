# Refactorización SOLID de Aegis

## Línea base

- Rama: `codex/complete-aegis`, commit `c2bed99`.
- Hotspots: `AndroidHomeViewModel.kt` (5709 líneas), `MainActivity.kt` (3595),
  `DesktopAgent.kt` (2274) y `RelayServer.kt` (683).
- La base ya contiene `AndroidRemoteSessionCoordinator` y
  `DesktopRemoteSessionCoordinator`; se conservarán como límites de ciclo de
  sesión y no se duplicará su lógica.
- Validación inicial verde con JDK 21 temporal y SDK Android local temporal:
  `:app-android:testDebugUnitTest`, `:app-desktop:desktop-agent:test` y
  `:relay-server:relay-main:test`.
- La primera ejecución con JDK 25 falló en el compilador Kotlin (`25.0.4`), y
  sin SDK Android falló por `platforms;android-35`/`build-tools;34.0.0`; son
  limitaciones del entorno, resueltas para esta sesión con herramientas
  temporales fuera del repositorio.

## Hotspots y responsabilidades actuales

- Android: `AndroidHomeViewModel` era composición, perfiles, pairing,
  relay/E2EE, WebRTC/input/clipboard, terminal, SFTP, WOL, estado y limpieza.
  `MainActivity` mezclaba ciclo Android, navegación y todas las pantallas
  Compose. Ahora la ViewModel es una fachada de aproximadamente 1.9k líneas y
  la actividad tiene 48 líneas; las capacidades viven en coordinadores y la UI
  en archivos por pantalla. `AndroidRelayE2eeCoordinator` posee las identidades
  pares y los canales efímeros de protocolo; `CancellableResult` evita convertir
  cancelaciones estructuradas en errores de UI.
- Escritorio: `DesktopAgent` mezclaba composición, pairing/trust, relay,
  sesiones, input/clipboard, settings, autostart, OpenSSH y recuperación.
  Ahora conserva la API pública y el `StateFlow`, pero delega en coordinadores;
  su archivo tiene aproximadamente 777 líneas. La recuperación y revocación
  fail-closed viven en `DesktopTrustCoordinator`.
- Relay: `relayServerModule` instalaba plugins y contenía health, identidad,
  sesiones, TURN y los dos WebSockets en una única función. Ahora compone
  dependencias e instala cinco grupos de rutas; el archivo tiene 234 líneas.

## Invariantes que no se pueden alterar

- Pairing QR v3 sigue siendo firmado, de un solo uso, limitado al objetivo y
  con expiración; nunca transporta secretos permanentes.
- Identidad P-256 etiquetada, trust completo, aprobación visible, revocación y
  rotación siguen siendo fail-closed.
- El relay continúa opaco: autenticación, rate limiting, URLs, payloads y
  cierres WebSocket no cambian.
- Input/clipboard remoto permanecen bloqueados hasta E2EE autenticado; SSH/SFTP
  conserva pinning y rutas TCP explícitas.
- Cada sesión conserva su generación, cancelación estructurada y cierre
  determinista; un resultado tardío no puede actualizar una sesión nueva.

## Fases de extracción

1. Composition roots manuales Android, escritorio y relay.
2. Coordinadores Android por capacidades, manteniendo la ViewModel como fachada.
3. Pantallas Compose separadas por feature, con `MainActivity` delgada.
4. Coordinadores de pairing, relay, sesión y settings detrás de `DesktopAgent`.
5. Grupos de rutas Ktor detrás de `relayServerModule`.
6. Revisión de ownership, Detekt, suite canónica y documentación final.

## Estado

| Fase | Estado | Evidencia |
|---|---|---|
| Línea base | Completada | Pruebas enfocadas verdes; ver arriba |
| Composition roots | Completada | `AegisAndroidAppGraph`, `AndroidHomeViewModelFactory`, `DesktopAgentFactory`, `DesktopAgentDependencies` y `RelayServerDependencies` |
| Android por capacidades | Completada | Coordinadores de perfiles, pairing, terminal, SFTP, relay/E2EE e interacción/sesión remota |
| UI Android | Completada | `AegisAndroidApp` y pantallas/componentes separados; `MainActivity` delgada |
| DesktopAgent fachada | Completada | Pairing, settings, relay/operaciones, relay/sesión y sesión separados |
| Relay por rutas | Completada | Health, identidad, sesiones y WebSockets extraídos bajo `routes/` |
| Limpieza y validación global | Completada | SQLDelight, `check`, Detekt, KtLint, lint Android y las suites afectadas pasan con JDK 21 |

## Validación final

- Pasan las pruebas enfocadas: `:app-android:testDebugUnitTest`,
  `:app-desktop:desktop-agent:test` y `:relay-server:relay-main:test`.
- También pasan las pruebas dirigidas de `core-pairing`, `core-security`,
  `protocol-models`, `protocol-tests`, `desktop-input` y `desktop-webrtc`.
- Pasan compilación, Detekt, KtLint y tests de los módulos desktop, relay y
  Android; también pasa `:app-android:lintRelease`.
- `verifySqlDelightMigration` pasa después de registrar el provider real de
  `tools/sqldelight-worker-init` y comprobar el descriptor ServiceLoader contra
  la clase compilada.
- Las fábricas Linux reciben todos los marcadores como entradas explícitas y
  comparten la precedencia XDG_SESSION_TYPE → WAYLAND_DISPLAY → DISPLAY.
- `DesktopAgent` invalida generaciones al detenerse, cancela su scope propio,
  cierra coordinadores y el child `sshd` de forma idempotente; el protocolo
  local se mantiene en `DesktopLocalProtocolCoordinator`.
- Linux preflight, captura Portal/PipeWire, smoke loopback OpenSSH/SFTP y
  packaging Arch están automatizados. El selector visual del portal y el
  recorrido Android↔Linux permanecen como gate físico.

## Fallos preexistentes relevantes

- Los baselines existentes de Detekt se conservaron; no se creó ninguno nuevo
  ni se usaron suppressions globales. Las incidencias observables de
  `desktop-input` que bloqueaban la suite global se corrigieron en código y con
  pruebas de regresión.
- La validación física Android↔Windows, Windows elevado, firma de release,
  relay durable y matrices remotas siguen siendo gates del producto descritos
  en `STATUS.md`; no son sustituibles por pruebas unitarias.
- El SDK Android usado para la validación se mantuvo fuera del repositorio
  (`platforms;android-35` y `build-tools;34.0.0`); no se modificó el proyecto
  para ocultar su ausencia.
